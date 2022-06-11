package electionguard.decrypt;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.common.flogger.FluentLogger;
import electionguard.core.ElGamalCiphertext;
import electionguard.core.GroupContext;
import electionguard.protogen2.CommonRpcProto;
import electionguard.protogen2.DecryptingProto;
import electionguard.protogen2.DecryptingTrusteeProto;
import electionguard.protogen2.DecryptingTrusteeServiceGrpc;
import electionguard.util.ConvertCommonProto;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static electionguard.publish.ReaderKt.readTrustee;
import static electionguard.util.KUtils.productionGroup;

/** A Remote Trustee with a DecryptingTrustee delegate, communicating over gRpc. */
public class RunRemoteDecryptingTrustee extends DecryptingTrusteeServiceGrpc.DecryptingTrusteeServiceImplBase {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Random random = new Random();

  private static class CommandLine {
    @Parameter(names = {"-trusteeFile"}, order = 1, description = "location of serialized trustee file", required = true)
    String trusteeFile;

    @Parameter(names = {"-port"}, order = 3, description = "This DecryptingRemoteTrustee port")
    int port = 0;

    @Parameter(names = {"-serverPort"}, order = 4, description = "The DecryptingRemote server port")
    int serverPort = 17711;

    @Parameter(names = {"-h", "--help"}, order = 9, description = "Display this help and exit", help = true)
    boolean help = false;

    private final JCommander jc;

    CommandLine(String progName, String[] args) throws ParameterException {
      this.jc = new JCommander(this);
      this.jc.parse(args);
      jc.setProgramName(String.format("java -classpath electionguard-java-all.jar %s", progName));
    }

    void printUsage() {
      jc.usage();
    }
  }

  public static void main(String[] args) {
    String progName = RunRemoteDecryptingTrustee.class.getName();
    CommandLine cmdLine = null;

    try {
      cmdLine = new CommandLine(progName, args);
      if (cmdLine.help) {
        cmdLine.printUsage();
        return;
      }
    } catch (ParameterException e) {
      System.err.println(e.getMessage());
      System.err.printf("Try '%s --help' for more information.%n", progName);
      System.exit(1);
    }

    // which port? if not assigned, pick one at random
    int port = cmdLine.port;
    if (port == 0) {
      port = cmdLine.serverPort + 1 + random.nextInt(10000);
      while (!isLocalPortFree(port)) {
        port = cmdLine.serverPort + 1 + random.nextInt(10000);
      }
    }
    String url = "localhost:"+port;
    String serverUrl = "localhost:" + cmdLine.serverPort;
    System.out.printf("*** DecryptingRemoteTrustee from file %s url %s server %s%n",
            cmdLine.trusteeFile, url, serverUrl);

    // Now start up our own 'DecryptingRemoteTrustee' Service
    try {
      GroupContext group = productionGroup();
      DecryptingTrusteeIF delegate = readTrustee(group, cmdLine.trusteeFile);
      RunRemoteDecryptingTrustee trustee = new RunRemoteDecryptingTrustee(group, delegate);

      if (cmdLine.serverPort != 0) {
        // register with the DecryptingRemote "server".
        RemoteDecryptorProxy proxy = new RemoteDecryptorProxy(serverUrl);
        DecryptingProto.RegisterDecryptingTrusteeResponse response = proxy.registerTrustee(trustee.id(), url,
                trustee.delegate.xCoordinate(), trustee.delegate.electionPublicKey());
        proxy.shutdown();

        if (response == null) {
          System.out.printf("    registerTrustee returns null response%n");
          throw new RuntimeException("registerTrustee returns null response");
        }
        if (!response.getError().isEmpty()) {
          System.out.printf("    registerTrustee error %s%n", response.getError());
          throw new RuntimeException(response.getError());
        }
        System.out.printf("    registered with DecryptingRemote %n");
      }

      trustee.start(port);
      trustee.blockUntilShutdown();
      System.exit(0);

    } catch (Throwable t) {
      System.out.printf("*** DecryptingRemoteTrustee FAILURE%n");
      t.printStackTrace();
      System.exit(3);
    }
  }

  private static boolean isLocalPortFree(int port) {
    try {
      new ServerSocket(port).close();
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  ///////////////////////////////////////////////////////////////////////////
  private Server server;

  private void start(int port) throws IOException {
    server = ServerBuilder.forPort(port) //
            .addService(this) //
            // .intercept(new MyServerInterceptor())
            .build().start();

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      // Use stderr here since the logger may have been reset by its JVM shutdown hook.
      System.err.println("*** shutting down gRPC server since JVM is shutting down");
      try {
        stopit();
      } catch (InterruptedException e) {
        e.printStackTrace(System.err);
      }
      System.err.println("*** server shut down");
    }));

    System.out.printf("---- DecryptingRemoteTrustee started, listening on %d ----%n", port);
  }

  private void stopit() throws InterruptedException {
    if (server != null) {
      server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
    }
  }

  /** Await termination on the main thread since the grpc library uses daemon threads. */
  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  ////////////////////////////////////////////////////////////////////////////////
  private final GroupContext group;
  private final DecryptingTrusteeIF delegate;

  RunRemoteDecryptingTrustee(GroupContext group, DecryptingTrusteeIF delegate) {
    this.group = group;
    this.delegate = delegate;
    System.out.printf("DecryptingTrustee= %s%n", this.delegate);
  }

  String id() {
    return delegate.id();
  }

  @Override
  public void directDecrypt(DecryptingTrusteeProto.DirectDecryptionRequest request,
                            StreamObserver<DecryptingTrusteeProto.DirectDecryptionResponse> responseObserver) {

    DecryptingTrusteeProto.DirectDecryptionResponse.Builder response = DecryptingTrusteeProto.DirectDecryptionResponse.newBuilder();
    try {
      List<ElGamalCiphertext> texts = request.getTextList().stream()
              .map(t -> ConvertCommonProto.importCiphertext(group, t))
              .toList();
      List<DirectDecryptionAndProof> tuples = delegate.directDecrypt(
              group,
              texts,
              ConvertCommonProto.importElementModQ(group, request.getExtendedBaseHash()),
              null);

      List<DecryptingTrusteeProto.DirectDecryptionResult> protos = tuples.stream()
              .map(this::convertDecryptionProofTuple)
              .toList();
      response.addAllResults(protos);
      logger.atInfo().log("DecryptingRemoteTrustee partialDecrypt %s", delegate.id());
    } catch (Throwable t) {
      logger.atSevere().withCause(t).log("DecryptingRemoteTrustee partialDecrypt failed");
      String mess = t.getMessage() != null ? t.getMessage() : "Unknown";
      response.setError(mess);
    }

    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  private DecryptingTrusteeProto.DirectDecryptionResult convertDecryptionProofTuple(DirectDecryptionAndProof tuple) {
    return DecryptingTrusteeProto.DirectDecryptionResult.newBuilder()
            .setDecryption(ConvertCommonProto.publishElementModP(tuple.getPartialDecryption()))
            .setProof(ConvertCommonProto.publishChaumPedersenProof(tuple.getProof()))
            .build();
  }

  @Override
  public void compensatedDecrypt(DecryptingTrusteeProto.CompensatedDecryptionRequest request,
                                 StreamObserver<DecryptingTrusteeProto.CompensatedDecryptionResponse> responseObserver) {

    DecryptingTrusteeProto.CompensatedDecryptionResponse.Builder response = DecryptingTrusteeProto.CompensatedDecryptionResponse.newBuilder();
    try {
      List<ElGamalCiphertext > texts = request.getTextList().stream()
              .map(t -> ConvertCommonProto.importCiphertext(group, t))
              .toList();

      List<CompensatedDecryptionAndProof> tuples = delegate.compensatedDecrypt(
              group,
              request.getMissingGuardianId(),
              texts,
              ConvertCommonProto.importElementModQ(group, request.getExtendedBaseHash()),
              null);

      List<DecryptingTrusteeProto.CompensatedDecryptionResult> protos = tuples.stream()
              .map(this::convertDecryptionProofRecovery)
              .toList();
      response.addAllResults(protos);
      logger.atInfo().log("DecryptingRemoteTrustee compensatedDecrypt %s", request.getMissingGuardianId());
    } catch (Throwable t) {
      logger.atSevere().withCause(t).log("DecryptingRemoteTrustee compensatedDecrypt failed");
      String mess = t.getMessage() != null ? t.getMessage() : "Unknown";
      response.setError(mess);
    }

    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  private DecryptingTrusteeProto.CompensatedDecryptionResult convertDecryptionProofRecovery(CompensatedDecryptionAndProof tuple) {
    return DecryptingTrusteeProto.CompensatedDecryptionResult.newBuilder()
            .setDecryption(ConvertCommonProto.publishElementModP(tuple.getPartialDecryption()))
            .setProof(ConvertCommonProto.publishChaumPedersenProof(tuple.getProof()))
            .setRecoveryPublicKey(ConvertCommonProto.publishElementModP(tuple.getRecoveredPublicKeyShare()))
            .build();
  }

  @Override
  public void finish(CommonRpcProto.FinishRequest request,
                     StreamObserver<CommonRpcProto.ErrorResponse> responseObserver) {
    CommonRpcProto.ErrorResponse.Builder response = CommonRpcProto.ErrorResponse.newBuilder();
    boolean ok = true;
    try {
      logger.atInfo().log("DecryptingTrusteeProto finish ok = %s", request.getAllOk());

    } catch (Throwable t) {
      logger.atSevere().withCause(t).log("DecryptingTrusteeProto finish failed");
      t.printStackTrace();
      response.setError(t.getMessage());
      ok = false;
    }

    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
    if (server != null) {
      System.exit(request.getAllOk() && ok ? 0 : 1);
    }
  }

}
