package electionguard.keyceremony;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.github.michaelbull.result.Result;
import com.google.common.flogger.FluentLogger;
import electionguard.core.ElementModP;
import electionguard.core.GroupContext;
import electionguard.core.SchnorrProof;
import electionguard.protogen.CommonRpcProto;
import electionguard.protogen.RemoteKeyCeremonyProto;
import electionguard.protogen.RemoteKeyCeremonyTrusteeProto;
import electionguard.protogen.RemoteKeyCeremonyTrusteeServiceGrpc;
import electionguard.publish.Publisher;
import electionguard.publish.PublisherMode;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Formatter;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static electionguard.protoconvert.CommonConvertKt.importElementModP;
import static electionguard.protoconvert.CommonConvertKt.importHashedCiphertext;
import static electionguard.protoconvert.CommonConvertKt.importSchnorrProof;
import static electionguard.protoconvert.CommonConvertKt.publishHashedCiphertext;
import static electionguard.protoconvert.CommonConvertKt.publishSchnorrProof;
import static electionguard.util.KUtils.productionGroup;

/** A Remote Trustee with a KeyCeremonyTrustee delegate, communicating over gRpc. */
class RunRemoteKeyCeremonyTrustee extends RemoteKeyCeremonyTrusteeServiceGrpc.RemoteKeyCeremonyTrusteeServiceImplBase {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Random random = new Random();

  private static class CommandLine {
    @Parameter(names = {"-name"}, order = 0, description = "Guardian name", required = true)
    String name;

    @Parameter(names = {"-port"}, order = 1, description = "This KeyCeremonyRemoteTrustee port")
    int port = 0;

    @Parameter(names = {"-serverPort"}, order = 2, description = "The KeyCeremonyRemote server port")
    int serverPort = 17111;

    @Parameter(names = {"-out"}, order = 3, description = "Directory where the Guardian state is written", required = true)
    String outputDir;

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
    String progName = RunRemoteKeyCeremonyTrustee.class.getName();
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
    System.out.printf("*** KeyCeremonyRemote %s with args %s %s%n", serverUrl, cmdLine.name, url);

    // first contact the KeyCeremonyRemote "server" to get parameters
    KeyCeremonyRemoteProxy proxy = new KeyCeremonyRemoteProxy(serverUrl);
    RemoteKeyCeremonyProto.RegisterKeyCeremonyTrusteeResponse response = proxy.registerTrustee(cmdLine.name, url);
    proxy.shutdown();
    if (!response.getError().isEmpty()) {
      System.out.printf("    registerTrustee error %s%n", response.getError());
      throw new RuntimeException(response.getError());
    }
    System.out.printf("    response %s %d %d %s%n", response.getGuardianId(),
            response.getGuardianXCoordinate(),
            response.getQuorum(),
            response.getConstants()
            );

    // Now start up our own 'RemoteTrustee' Service
    try {
      RunRemoteKeyCeremonyTrustee keyCeremony = new RunRemoteKeyCeremonyTrustee(
              response.getGuardianId(),
              response.getGuardianXCoordinate(),
              response.getQuorum(),
              cmdLine.outputDir);

      keyCeremony.start(port);
      keyCeremony.blockUntilShutdown();
      System.exit(0);

    } catch (Throwable t) {
      System.out.printf("*** KeyCeremonyRemote FAILURE%n");
      t.printStackTrace();
      System.exit(3);
    }
  }

  private static boolean isLocalPortFree(int port) {
    System.out.printf("Try %d port ", port);
    try {
      new ServerSocket(port).close();
      System.out.printf("free%n");
      return true;
    } catch (IOException e) {
      System.out.printf("taken%n");
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

    System.out.printf("---- KeyCeremonyRemoteService started, listening on %d ----%n", port);
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
  final GroupContext group = productionGroup();
  final KeyCeremonyTrustee delegate;
  final String trusteeDir;
  final Publisher publisher;

  RunRemoteKeyCeremonyTrustee(String id,
                              int xCoordinate,
                              int quorum,
                              String trusteeDir) throws IOException {
    this.delegate = new KeyCeremonyTrustee(group, id, xCoordinate, quorum);
    this.trusteeDir = trusteeDir;

    // fail fast on bad output directory
    publisher = new Publisher(trusteeDir, PublisherMode.createIfMissing);
    Formatter errors = new Formatter();
    if (!publisher.validateOutputDir(errors)) {
      System.out.printf("*** Publisher validateOutputDir FAILED on %s%n%s", trusteeDir, errors);
      throw new FileNotFoundException(errors.toString());
    }
  }

  @Override
  public void sendPublicKeys(RemoteKeyCeremonyTrusteeProto.PublicKeySetRequest request,
                             StreamObserver<RemoteKeyCeremonyTrusteeProto.PublicKeySet> responseObserver) {

    RemoteKeyCeremonyTrusteeProto.PublicKeySet.Builder response = RemoteKeyCeremonyTrusteeProto.PublicKeySet.newBuilder();
    try {
      Result<PublicKeys, String> result = delegate.sendPublicKeys();
      if (result.component2() != null) {
        response.setError(result.component2());
        logger.atInfo().log("KeyCeremonyRemoteTrustee %s sendPublicKeys error %s", delegate.id(), result.component2());
      } else {
        PublicKeys keyset = result.component1();
        response.setOwnerId(keyset.getGuardianId())
                .setGuardianXCoordinate(keyset.getGuardianXCoordinate());
        keyset.getCoefficientProofs().forEach(p -> {
          electionguard.protogen.SchnorrProof proto = publishSchnorrProof(p);
          response.addCoefficientProofs(proto);
        });
        logger.atInfo().log("KeyCeremonyRemoteTrustee %s sendPublicKeys", delegate.id());
      }
    } catch (Throwable t) {
      logger.atSevere().withCause(t).log("KeyCeremonyRemoteTrustee %s sendPublicKeys failed", delegate.id());
      t.printStackTrace();
      response.setError("KeyCeremonyRemoteTrustee sendPublicKeys failed:" + t.getMessage());
    }

    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  public void receivePublicKeys(RemoteKeyCeremonyTrusteeProto.PublicKeySet proto,
                                StreamObserver<CommonRpcProto.ErrorResponse> responseObserver) {

    CommonRpcProto.ErrorResponse.Builder response = CommonRpcProto.ErrorResponse.newBuilder();
    try {
      List<ElementModP> commitments = proto.getCoefficientComittmentsList().stream()
              .map(p -> importElementModP(group, p))
              .toList();
      List<SchnorrProof> proofs = proto.getCoefficientProofsList().stream()
              .map(p -> importSchnorrProof(group, p))
              .toList();
      PublicKeys keyset = new PublicKeys(
              proto.getOwnerId(),
              proto.getGuardianXCoordinate(),
              commitments,
              proofs);
      Result<PublicKeys, String> result = delegate.receivePublicKeys(keyset);
      if (result.component2() != null) {
        response.setError(result.component2());
      }
      logger.atInfo().log("KeyCeremonyRemoteTrustee receivePublicKeys from %s", proto.getOwnerId());

    } catch (Throwable t) {
      logger.atSevere().withCause(t).log("KeyCeremonyRemoteTrustee receivePublicKeys from %s failed", proto.getOwnerId());
      t.printStackTrace();
      response.setError(t.getMessage());
    }

    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  public void sendSecretKeyShare(RemoteKeyCeremonyTrusteeProto.PartialKeyBackupRequest request,
                                   StreamObserver<RemoteKeyCeremonyTrusteeProto.PartialKeyBackup> responseObserver) {

    RemoteKeyCeremonyTrusteeProto.PartialKeyBackup.Builder response = RemoteKeyCeremonyTrusteeProto.PartialKeyBackup.newBuilder();
    try {
      Result<SecretKeyShare, String> result = delegate.sendSecretKeyShare(request.getGuardianId());

      if (result.component2() == null) {
        SecretKeyShare backup = result.component1();
        response.setGeneratingGuardianId(backup.getGeneratingGuardianId())
                .setDesignatedGuardianId(backup.getDesignatedGuardianId())
                .setDesignatedGuardianXCoordinate(backup.getDesignatedGuardianXCoordinate())
                .setEncryptedCoordinate(publishHashedCiphertext(backup.getEncryptedCoordinate()));
      } else {
        response.setError(result.component2());
      }
      logger.atInfo().log("KeyCeremonyRemoteTrustee sendPartialKeyBackup %s", request.getGuardianId());

    } catch (Throwable t) {
      logger.atSevere().withCause(t).log("KeyCeremonyRemoteTrustee sendPartialKeyBackup failed");
      t.printStackTrace();
      response.setError(t.getMessage());
    }

    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  public void receiveSecretKeyShare(RemoteKeyCeremonyTrusteeProto.PartialKeyBackup proto,
                                     StreamObserver<RemoteKeyCeremonyTrusteeProto.PartialKeyVerification> responseObserver) {

    RemoteKeyCeremonyTrusteeProto.PartialKeyVerification.Builder response = RemoteKeyCeremonyTrusteeProto.PartialKeyVerification.newBuilder();
    try {
      SecretKeyShare backup = new SecretKeyShare(
              proto.getGeneratingGuardianId(),
              proto.getDesignatedGuardianId(),
              proto.getDesignatedGuardianXCoordinate(),
              importHashedCiphertext(group, proto.getEncryptedCoordinate())
      );

      Result<SecretKeyShare, String> result = delegate.receiveSecretKeyShare(backup);

      if (result.component2() == null) {
        SecretKeyShare sshare = result.component1();

        response.setGeneratingGuardianId(sshare.getGeneratingGuardianId())
                .setDesignatedGuardianId(sshare.getDesignatedGuardianId())
                .setDesignatedGuardianXCoordinate(backup.getDesignatedGuardianXCoordinate());
      } else {
        response.setError(result.component2());
      }
      logger.atInfo().log("KeyCeremonyRemoteTrustee verifyPartialKeyBackup %s", proto.getGeneratingGuardianId());

    } catch (Throwable t) {
      logger.atSevere().withCause(t).log("KeyCeremonyRemoteTrustee verifyPartialKeyBackup failed");
      t.printStackTrace();
      response.setError(t.getMessage());
    }

    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  public void saveState(com.google.protobuf.Empty request,
                        StreamObserver<CommonRpcProto.ErrorResponse> responseObserver) {
    CommonRpcProto.ErrorResponse.Builder response = CommonRpcProto.ErrorResponse.newBuilder();
    try {
      publisher.writeTrustee(trusteeDir, this.delegate);
      System.out.printf("TrusteeFromKeyCeremony %s%n", this.delegate);
      logger.atInfo().log("KeyCeremonyRemoteTrustee saveState %s", delegate.id());

    } catch (Throwable t) {
      logger.atSevere().withCause(t).log("KeyCeremonyRemoteTrustee saveState failed");
      t.printStackTrace();
      response.setError(t.getMessage());
    }

    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  public void finish(CommonRpcProto.FinishRequest request,
                     StreamObserver<CommonRpcProto.ErrorResponse> responseObserver) {
    CommonRpcProto.ErrorResponse.Builder response = CommonRpcProto.ErrorResponse.newBuilder();
    boolean ok = true;
    try {
      logger.atInfo().log("KeyCeremonyRemoteTrustee finish ok = %s", request.getAllOk());

    } catch (Throwable t) {
      logger.atSevere().withCause(t).log("KeyCeremonyRemoteTrustee finish failed");
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
