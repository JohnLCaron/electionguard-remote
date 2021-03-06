package electionguard.decrypt;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.flogger.FluentLogger;
import electionguard.ballot.ElectionInitialized;
import electionguard.ballot.EncryptedBallot;
import electionguard.ballot.PlaintextTally;
import electionguard.ballot.DecryptingGuardian;
import electionguard.ballot.EncryptedTally;
import electionguard.core.GroupContext;
import electionguard.input.ManifestInputValidation;
import electionguard.input.ValidationMessages;
import electionguard.publish.Consumer;
import electionguard.publish.ElectionRecord;
import electionguard.publish.Publisher;
import electionguard.ballot.DecryptionResult;
import electionguard.ballot.TallyResult;
import electionguard.protogen2.DecryptingProto;
import electionguard.protogen2.DecryptingServiceGrpc;
import electionguard.publish.PublisherMode;
import electionguard.util.ConvertCommonProto;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Formatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static electionguard.publish.ElectionRecordFactoryKt.electionRecordFromConsumer;
import static electionguard.util.KUtils.productionGroup;

/**
 * A command line program to decrypt a tally and optionally a collection of ballots with remote Guardians.
 * It opens up a channel to allow guardians to register with it.
 * It waits until navailable guardians register, then starts the decryption.
 * <p>
 * For command line help:
 * <strong>
 * <pre>
 *  java -classpath electionguard-java-all.jar electionguard.decrypting.DecryptingRemote --help
 * </pre>
 * </strong>
 */
public class RunRemoteDecryptor {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static class CommandLine {
    @Parameter(names = {"-in"}, order = 0,
            description = "Directory containing input election record and encrypted ballots and tally", required = true)
    String encryptDir;

    @Parameter(names = {"-out"}, order = 1,
            description = "Directory where augmented election record is published", required = true)
    String outputDir;

    @Parameter(names = {"-navailable"}, order = 2, description = "Number of available Guardians", required = true)
    int navailable;

    @Parameter(names = {"-port"}, order = 3, description = "The port to run the server on")
    int port = 17711;

    @Parameter(names = {"-decryptSpoiled"}, order = 3, description = "Decrypt the spoiled ballots")
    boolean decryptSpoiled = false;

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
    GroupContext group = productionGroup();
    String progName = RunRemoteDecryptor.class.getName();
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

    boolean allOk = false;
    RunRemoteDecryptor decryptor = null;
    try {
      Consumer consumer = new Consumer(cmdLine.encryptDir, group);
      ElectionRecord electionRecord = electionRecordFromConsumer(consumer);
      ManifestInputValidation validator = new ManifestInputValidation(electionRecord.manifest());
      ValidationMessages mess = validator.validate();
      if (mess.hasErrors()) {
        System.out.printf("*** ElectionInputValidation FAILED on %s%n%s", cmdLine.encryptDir, mess);
        System.exit(1);
      }

      // check that outputDir exists and can be written to
      Publisher publisher = new Publisher(cmdLine.outputDir, PublisherMode.createIfMissing);
      Formatter errors = new Formatter();
      if (!publisher.validateOutputDir(errors)) {
        System.out.printf("*** Publisher validateOutputDir FAILED on %s%n%s", cmdLine.encryptDir, errors);
        System.exit(2);
      }

      decryptor = new RunRemoteDecryptor(group, consumer, electionRecord,
              cmdLine.encryptDir, cmdLine.outputDir, cmdLine.navailable, cmdLine.decryptSpoiled, publisher);
      decryptor.start(cmdLine.port);

      System.out.print("Waiting for guardians to register: elapsed seconds = ");
      Stopwatch stopwatch = Stopwatch.createStarted();
      while (!decryptor.ready()) {
        System.out.printf("%s ", stopwatch.elapsed(TimeUnit.SECONDS));
        try {
          Thread.sleep(5000);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
      System.out.printf("%n");

      allOk = decryptor.runDecryption();

    } catch (Throwable t) {
      System.out.printf("*** DecryptingMediatorRunner FAILURE%n");
      t.printStackTrace();
      allOk = false;

    } finally {
      if (decryptor != null) {
        decryptor.shutdownRemoteTrustees(allOk);
      }
    }

    System.exit(allOk ? 0 : 1);
  }

  ///////////////////////////////////////////////////////////////////////////
  private Server server;

  private void start(int port) throws IOException {
    server = ServerBuilder.forPort(port) //
            .addService(new DecryptingRegistrationService()) //
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

    System.out.printf("---- DecryptingRemote started, listening on %d ----%n", port);
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

  ///////////////////////////////////////////////////////////////////////////
  final Stopwatch stopwatch = Stopwatch.createUnstarted();

  final GroupContext group;
  final Consumer consumer;
  final ElectionRecord electionRecord;
  final String encryptDir;
  final String outputDir;
  final int navailable;
  final boolean decryptSpoiled;
  final Publisher publisher;

  final int nguardians;
  final int quorum;
  final TallyResult tallyResult;
  final EncryptedTally encryptedTally;
  final ElectionInitialized electionInitialized;
  final List<RemoteDecryptingTrusteeProxy> trusteeProxies = Collections.synchronizedList(new ArrayList<>());

  List<PlaintextTally> spoiledDecryptedTallies;
  List<DecryptingGuardian> availableGuardians;
  boolean startedDecryption = false;
  PlaintextTally decryptedTally;

  RunRemoteDecryptor(GroupContext group, Consumer consumer, ElectionRecord electionRecord,
                     String encryptDir, String outputDir,
                     int navailable, boolean decryptSpoiled, Publisher publisher) {
    this.group = group;
    this.consumer = consumer;
    this.electionRecord = electionRecord;
    this.encryptDir = encryptDir;
    this.outputDir = outputDir;
    this.navailable = navailable;
    this.publisher = publisher;
    this.decryptSpoiled = decryptSpoiled;

    this.nguardians = electionRecord.numberOfGuardians();
    this.quorum = electionRecord.quorum();
    this.electionInitialized = electionRecord.electionInit();
    this.encryptedTally = electionRecord.encryptedTally();
    this.tallyResult = consumer.readTallyResult().component1();

    Preconditions.checkArgument(this.navailable >= this.quorum,
            String.format("Available guardians (%d) must be >= quorum (%d)", this.navailable, this.quorum));
    Preconditions.checkArgument(this.navailable <= this.nguardians,
            String.format("Available guardians (%d) must be <= nguardians (%d)", this.navailable, this.nguardians));

    System.out.printf("DecryptingRemote startup at %s%n", LocalDateTime.now());
    System.out.printf("DecryptingRemote quorum = %d available = %d nguardians = %d%n", this.quorum, this.navailable, this.nguardians);
    stopwatch.start();
  }

  boolean ready() {
    return trusteeProxies.size() == this.navailable;
  }

  private boolean runDecryption() {

    List<String> trusteeNames = trusteeProxies.stream().map(it -> it.id()).toList();
    List<String> missingGuardians = electionRecord.guardians().stream()
                    .map(it -> it.getGuardianId())
                    .filter(guardianId -> !trusteeNames.contains(guardianId))
                    .toList();

    Decryption decryptor = new Decryption(group, electionInitialized, trusteeProxies, missingGuardians);
    this.decryptedTally = decryptor.decrypt(this.encryptedTally);

    if (this.decryptSpoiled) {
      for (EncryptedBallot spoiled : consumer.iterateSpoiledBallots()) {
        this.spoiledDecryptedTallies.add(decryptor.decryptBallot(spoiled));
      }
      System.out.printf("spoiledDecryptedTallies count = %d%n", spoiledDecryptedTallies.size());
    }

    boolean ok;
    try {
      publish(encryptDir, this.tallyResult, decryptor.getAvailableGuardians());
      ok = true;
    } catch (IOException e) {
      e.printStackTrace();
      ok = false;
    }

    System.out.printf("*** RunRemoteDecryptor %s%n", ok ? "SUCCESS" : "FAILURE");
    return ok;
  }

  private void shutdownRemoteTrustees(boolean allOk) {
    System.out.printf("Shutdown Remote Trustees%n");
    // tell the remote trustees to finish
    for (RemoteDecryptingTrusteeProxy trustee : trusteeProxies) {
      try {
        boolean ok = trustee.finish(allOk);
        System.out.printf(" DecryptingRemoteTrusteeProxy %s shutdown was success = %s%n", trustee.id(), ok);
      } catch (Throwable t) {
        t.printStackTrace();
      }
    }

    // close the proxy channels
    boolean shutdownOk = true;
    for (RemoteDecryptingTrusteeProxy trustee : trusteeProxies) {
      if (!trustee.shutdown()) {
        shutdownOk = false;
      }
    }
    System.out.printf(" Proxy channel shutdown was success = %s%n", shutdownOk);
  }

  void publish(String inputDir, TallyResult tallyResult, List<DecryptingGuardian> decryptingGuardians) throws IOException {

    DecryptionResult results = new DecryptionResult(
            tallyResult,
            this.decryptedTally,
            decryptingGuardians,
            Map.of(
                    "CreatedBy", "RunRemoteDecryptor",
                    "CreatedOn", Instant.now().toString(),
                    "CreatedFromDir", inputDir
            )
    );

    publisher.writeDecryptionResult(results);
    // publisher.copyAcceptedBallots(inputDir);
  }

  //////////////////////////////////////////////////////////////////////////////////////////

  private synchronized RemoteDecryptingTrusteeProxy registerTrustee(DecryptingProto.RegisterDecryptingTrusteeRequest request) {
    for (RemoteDecryptingTrusteeProxy proxy : trusteeProxies) {
      if (proxy.id().equalsIgnoreCase(request.getGuardianId())) {
        throw new IllegalArgumentException("Already have a guardian id=" + request.getGuardianId());
      }
    }
    RemoteDecryptingTrusteeProxy.Builder builder = RemoteDecryptingTrusteeProxy.builder();
    builder.setTrusteeId(request.getGuardianId());
    builder.setUrl(request.getRemoteUrl());
    builder.setXCoordinate(request.getGuardianXCoordinate());
    builder.setElectionPublicKey(ConvertCommonProto.importElementModP(group, request.getPublicKey()));
    RemoteDecryptingTrusteeProxy trustee = builder.build();
    trusteeProxies.add(trustee);
    return trustee;
  }

  private class DecryptingRegistrationService extends DecryptingServiceGrpc.DecryptingServiceImplBase {

    @Override
    public void registerTrustee(DecryptingProto.RegisterDecryptingTrusteeRequest request,
                                StreamObserver<DecryptingProto.RegisterDecryptingTrusteeResponse> responseObserver) {

      System.out.printf("DecryptingRemote registerTrustee %s url %s %n", request.getGuardianId(), request.getRemoteUrl());

      if (startedDecryption) {
        responseObserver.onNext(DecryptingProto.RegisterDecryptingTrusteeResponse.newBuilder()
                .setError("Already started Decryption").build());
        responseObserver.onCompleted();
        return;
      }

      DecryptingProto.RegisterDecryptingTrusteeResponse.Builder response = DecryptingProto.RegisterDecryptingTrusteeResponse.newBuilder();
      try {
        RemoteDecryptingTrusteeProxy trustee = RunRemoteDecryptor.this.registerTrustee(request);
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
        logger.atInfo().log("DecryptingRemote registerTrustee %s", trustee.id());

      } catch (Throwable t) {
        logger.atSevere().withCause(t).log("DecryptingRemote registerTrustee failed");
        t.printStackTrace();
        response.setError(t.getMessage());
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
      }
    }
  }

}
