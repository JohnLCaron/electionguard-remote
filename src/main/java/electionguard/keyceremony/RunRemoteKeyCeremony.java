package electionguard.keyceremony;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.github.michaelbull.result.Result;
import com.google.common.base.Stopwatch;
import com.google.common.flogger.FluentLogger;
import electionguard.ballot.Manifest;
import electionguard.core.GroupContext;
import electionguard.input.ManifestInputValidation;
import electionguard.input.ValidationMessages;
import electionguard.protogen2.RemoteKeyCeremonyProto;
import electionguard.protogen2.RemoteKeyCeremonyServiceGrpc;
import electionguard.publish.Consumer;
import electionguard.publish.ElectionRecord;
import electionguard.publish.Publisher;
import electionguard.publish.PublisherMode;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Formatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static electionguard.keyceremony.KeyCeremonyKt.keyCeremonyExchange;
import static electionguard.publish.ElectionRecordFactoryKt.electionRecordFromConsumer;
import static electionguard.util.KUtils.productionGroup;

/**
 * A command line program that performs the key ceremony with remote KeyCeremonyTrustee.
 * It opens up a channel to allow KeyCeremonyTrustee to register with it.
 * It waits until nguardians register, then starts the key ceremony.
 * <p>
 * For command line help:
 * <strong>
 * <pre>
 *  java -classpath electionguard-java-all.jar com.sunya.electionguard.keyceremony.KeyCeremonyRemote --help
 * </pre>
 * </strong>
 */
public class RunRemoteKeyCeremony {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static class CommandLine {
    @Parameter(names = {"-in"}, order = 0,
            description = "Directory containing election manifest", required = true)
    String inputDir;

    @Parameter(names = {"-out"}, order = 1,
            description = "Directory where election record is written", required = true)
    String outputDir;

    @Parameter(names = {"-nguardians"}, order = 2, description = "Number of Guardians that will be used", required = true)
    int nguardians;

    @Parameter(names = {"-quorum"}, order = 3, description = "Number of Guardians that make a quorum", required = true)
    int quorum;

    @Parameter(names = {"-port"}, order = 4, description = "The port to run the server on")
    int port = 17111;

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
    String progName = RunRemoteKeyCeremony.class.getName();
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

    GroupContext group = productionGroup();
    boolean allOk = false;
    RunRemoteKeyCeremony keyCeremony = null;
    try {
      ElectionRecord record = electionRecordFromConsumer(new Consumer(cmdLine.inputDir, group));
      ManifestInputValidation validator = new ManifestInputValidation(record.manifest());
      ValidationMessages errors = validator.validate();
      if (errors.hasErrors()) {
        System.out.printf("*** ManifestInputValidation FAILED on %s%n%s", cmdLine.inputDir, errors);
        System.exit(1);
      }

      keyCeremony = new RunRemoteKeyCeremony(record, cmdLine.outputDir);
      keyCeremony.start(cmdLine.port);

      System.out.print("Waiting for guardians to register: elapsed seconds = ");
      Stopwatch stopwatch = Stopwatch.createStarted();
      while (!keyCeremony.ready()) {
        System.out.printf("%s ", stopwatch.elapsed(TimeUnit.SECONDS));
        try {
          Thread.sleep(5000);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
      System.out.printf("%n");

      allOk = keyCeremony.runKeyCeremony();

    } catch (Throwable t) {
      System.out.printf("*** KeyCeremonyRemote FAILURE = %s%n", t.getMessage());
      t.printStackTrace();
      allOk = false;

    } finally {
      if (keyCeremony != null) {
        keyCeremony.shutdownRemoteTrustees(allOk);
      }
    }
    System.exit(allOk ? 0 : 1);
  }

  ///////////////////////////////////////////////////////////////////////////
  private Server server;

  private void start(int port) throws IOException {
    server = ServerBuilder.forPort(port) //
            .addService(new KeyCeremonyRemoteService()) //
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

    System.out.printf("---- RemoteKeyCeremony started, listening on %d ----%n", port);
  }

  private void stopit() throws InterruptedException {
    if (server != null) {
      server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
    }
  }

  ///////////////////////////////////////////////////////////////////////////
  final ElectionRecord electionRecord;
  final Manifest manifest;
  final int nguardians;
  final int quorum;
  final Publisher publisher;
  final List<RemoteTrusteeProxy> trusteeProxies = Collections.synchronizedList(new ArrayList<>());
  boolean startedKeyCeremony = false;

  RunRemoteKeyCeremony(ElectionRecord electionRecord, String outputDir) throws IOException {
    this.electionRecord = electionRecord;
    this.manifest = electionRecord.manifest();
    this.nguardians = electionRecord.numberOfGuardians();
    this.quorum = electionRecord.quorum();

    this.publisher = new Publisher(outputDir, PublisherMode.createNew);
    Formatter errors = new Formatter();
    if (!publisher.validateOutputDir(errors)) {
      System.out.printf("*** Publisher validateOutputDir FAILED on %s%n%s", outputDir, errors);
      throw new FileNotFoundException(errors.toString());
    }
  }

  boolean ready() {
    return trusteeProxies.size() == nguardians;
  }

  private boolean runKeyCeremony() {
    if (trusteeProxies.size() != nguardians) {
      throw new IllegalStateException(String.format("Need %d guardians, but only %d registered", nguardians,
              trusteeProxies.size()));
    }
    List<KeyCeremonyTrusteeIF> trusteeIfs = new ArrayList<>(trusteeProxies);
    Result<KeyCeremonyResults, String> keyCeremonyExchangeResult = keyCeremonyExchange(trusteeIfs);
    if (keyCeremonyExchangeResult.component2() != null) {
      System.out.printf("%nRemoteKeyCeremony failed error = %s%n", keyCeremonyExchangeResult.component2());
      return false;
    }
    KeyCeremonyResults results = keyCeremonyExchangeResult.component1();

    // tell the remote trustees to save their state
    boolean allOk = true;
    for (RemoteTrusteeProxy trustee : trusteeProxies) {
      if (!trustee.saveState()) {
        allOk = false;
      }
    }
    System.out.printf("%nKey Ceremony Trustees save state was success = %s%n", allOk);

    if (allOk) {
      // save the results as an "ElectionInitialized" record
      publisher.writeElectionInitialized(results.makeElectionInitialized(
              electionRecord.config(),
              Map.of("CreatedBy", "RunRemoteKeyCeremony",
                      "CreatedFromDir", electionRecord.topdir())
      ));
    }
    System.out.printf("%nRemoteKeyCeremony was success = %s%n", allOk);

    return allOk;
  }

  private void shutdownRemoteTrustees(boolean allOk) {
    System.out.printf("Shutdown Remote Trustees%n");
    // tell the remote trustees to finish
    for (RemoteTrusteeProxy trustee : trusteeProxies) {
      try {
        boolean ok = trustee.finish(allOk);
        System.out.printf(" KeyCeremonyRemoteTrusteeProxy %s shutdown was success = %s%n", trustee.id(), ok);
      } catch (Throwable t) {
        t.printStackTrace();
      }
    }

    // close the proxy channels
    boolean shutdownOk = true;
    for (RemoteTrusteeProxy trustee : trusteeProxies) {
      if (!trustee.shutdown()) {
        shutdownOk = false;
      }
    }
    System.out.printf(" Proxy channel shutdown was success = %s%n", shutdownOk);
  }

  private final AtomicInteger nextCoordinate = new AtomicInteger(0);
  synchronized RemoteTrusteeProxy registerTrustee(String guardianId, String url) {
    for (RemoteTrusteeProxy proxy : trusteeProxies) {
      if (proxy.id().toLowerCase().contains(guardianId.toLowerCase()) ||
              guardianId.toLowerCase().contains(proxy.id().toLowerCase())) {
        throw new IllegalArgumentException(
                String.format("Trying to add a guardian id '%s' equal or similar to existing '%s'",
                guardianId, proxy.id()));
      }
    }
    RemoteTrusteeProxy.Builder builder = RemoteTrusteeProxy.builder();
    int coordinate = nextCoordinate.incrementAndGet();
    builder.setTrusteeId(guardianId);
    builder.setUrl(url);
    builder.setCoordinate(coordinate);
    builder.setQuorum(this.quorum);
    RemoteTrusteeProxy trustee = builder.build();
    trusteeProxies.add(trustee);
    return trustee;
  }

  private class KeyCeremonyRemoteService extends RemoteKeyCeremonyServiceGrpc.RemoteKeyCeremonyServiceImplBase {

    @Override
    public void registerTrustee(RemoteKeyCeremonyProto.RegisterKeyCeremonyTrusteeRequest request,
                                StreamObserver<RemoteKeyCeremonyProto.RegisterKeyCeremonyTrusteeResponse> responseObserver) {

      System.out.printf("%nRemoteKeyCeremony registerTrustee %s url %s", request.getGuardianId(), request.getRemoteUrl());

      if (startedKeyCeremony) {
        responseObserver.onNext(RemoteKeyCeremonyProto.RegisterKeyCeremonyTrusteeResponse.newBuilder()
                .setError("Already started KeyCeremony")
                .build());
        responseObserver.onCompleted();
        return;
      }

      RemoteKeyCeremonyProto.RegisterKeyCeremonyTrusteeResponse.Builder response = RemoteKeyCeremonyProto.RegisterKeyCeremonyTrusteeResponse.newBuilder();
      try {
        RemoteTrusteeProxy trustee = RunRemoteKeyCeremony.this.registerTrustee(request.getGuardianId(), request.getRemoteUrl());
        response.setGuardianId(trustee.id());
        response.setGuardianXCoordinate(trustee.xCoordinate());
        response.setQuorum(trustee.quorum());
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
        logger.atInfo().log("RemoteKeyCeremony registerTrustee '%s'", trustee.id());

      } catch (Throwable t) {
        logger.atSevere().withCause(t).log("RemoteKeyCeremony registerTrustee failed");
        response.setError(t.getMessage());
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
      }
    }
  }

}
