package electionguard.workflow;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.common.base.Stopwatch;

import electionguard.ballot.ElectionInitialized;
import electionguard.ballot.PlaintextBallot;
import electionguard.core.GroupContext;
import electionguard.encrypt.CheckType;
import electionguard.publish.Consumer;
import electionguard.publish.ElectionRecord;
import electionguard.publish.Publisher;
import electionguard.publish.PublisherMode;
import electionguard.verifier.Verifier;
import electionguard.input.RandomBallotProvider;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static electionguard.encrypt.RunBatchEncryptionKt.batchEncryption;
import static electionguard.publish.ElectionRecordFactoryKt.electionRecordFromConsumer;
import static electionguard.tally.RunAccumulateTallyKt.runAccumulateBallots;
import static electionguard.util.KUtils.productionGroup;

/**
 * Runs the entire workflow from start to finish, using remote guardians.
 * Runs the components out of the fatJar, so be sure to build that first: "./gradlew clean assemble fatJar"
 * Also be sure to keep RunRemoteWorkflowTest.classpath synched with fatjar SHAPSHOT version.
 */
public class RunRemoteWorkflowTest {
  public static final String classpath = "build/libs/electionguard-remote-1.0-SNAPSHOT-all.jar";

  private static class CommandLine {
    @Parameter(names = {"-in"}, order = 0,
            description = "Directory to read input election manifest", required = true)
    String inputDir;

    @Parameter(names = {"-nguardians"}, order = 2, description = "Number of guardians to create", required = true)
    int nguardians = 6;

    @Parameter(names = {"-quorum"}, order = 3, description = "Number of guardians that make a quorum", required = true)
    int quorum = 5;

    @Parameter(names = {"-trusteeDir"}, order = 4,
            description = "Directory to write Trustee serializations")
    String trusteeDir;

    @Parameter(names = {"-nballots"}, order = 6,
            description = "number of ballots to generate", required = true)
    int nballots;

    @Parameter(names = {"-navailable"}, order = 7, description = "Number of guardians available for decryption")
    int navailable = 0;

    @Parameter(names = {"-out"}, order = 8,
            description = "Directory to write election record", required = true)
    String outputDir;

    @Parameter(names = {"-cmdOutput"}, order = 8,
            description = "Directory to write command output")
    String cmdOutput;

    @Parameter(names = {"-h", "--help"}, order = 99, description = "Display this help and exit", help = true)
    boolean help = false;

    private final JCommander jc;

    public CommandLine(String progName, String[] args) throws ParameterException {
      this.jc = new JCommander(this);
      this.jc.parse(args);
      jc.setProgramName(String.format("java -classpath electionguard-java-all.jar %s", progName));
    }

    public void printUsage() {
      jc.usage();
    }
  }

  public static void main(String[] args) {
    String progName = RunRemoteWorkflowTest.class.getName();
    CommandLine cmdLine;
    Stopwatch stopwatchAll = Stopwatch.createStarted();
    Stopwatch stopwatch = Stopwatch.createStarted();

    try {
      cmdLine = new CommandLine(progName, args);
      if (cmdLine.help) {
        cmdLine.printUsage();
        return;
      }

    } catch (ParameterException e) {
      System.err.println(e.getMessage());
      System.err.printf("Try '%s --help' for more information.%n", progName);
      return;
    }

    GroupContext group = productionGroup();
    String privateDir =  cmdLine.outputDir + "/private_data";
    String trusteeDir =  (cmdLine.trusteeDir != null) ? cmdLine.trusteeDir : privateDir + "/trustees";
    String ballotsDir =  privateDir + "/input";
    String invalidDir =  privateDir + "/invalid";
    String cmdDir =  (cmdLine.cmdOutput != null) ? cmdLine.cmdOutput : cmdLine.outputDir + "/cmdOutput";

    try {
      System.out.printf("%n1 KeyCeremony =============================================================%n");
      // Run RemoteKeyCeremony
      RunRemoteKeyCeremonyTest.main(
              new String[] {
                      "-in", cmdLine.inputDir,
                      "-out", cmdLine.outputDir,
                      "-nguardians", Integer.toString(cmdLine.nguardians),
                      "-quorum", Integer.toString(cmdLine.quorum),
                      "-trusteeDir", trusteeDir,
                      "-cmdOutput", cmdDir
              }
      );

      // LOOK how do we know if it worked?

      System.out.printf("*** keyCeremonyRemote elapsed = %d ms%n", stopwatch.elapsed(TimeUnit.MILLISECONDS));

      System.out.printf("%n2 Encrypt =============================================================%n");
      stopwatch.reset().start();

      // create fake ballots
      Consumer consumerIn = new Consumer(cmdLine.outputDir, group);
      ElectionInitialized electionInit = consumerIn.readElectionInitialized().component1();
      RandomBallotProvider ballotProvider = new RandomBallotProvider(electionInit.manifest(), cmdLine.nballots);
      List<PlaintextBallot> ballots = ballotProvider.ballots();
      Publisher publisher = new Publisher(ballotsDir, PublisherMode.createIfMissing);
      publisher.writePlaintextBallot(ballotsDir, ballots);
      System.out.printf("RandomBallotProvider created %d fake ballots%n", ballots.size());

      // encrypt
      batchEncryption(group, cmdLine.outputDir, cmdLine.outputDir, ballotsDir, invalidDir, true, 11,
              "createdBy", CheckType.None);

      long msecs = stopwatch.elapsed(TimeUnit.MILLISECONDS);
      double secsPer = (.001 * msecs) / cmdLine.nballots;
      System.out.printf("*** encryptBallots elapsed = %d sec %.3f per ballot%n", stopwatch.elapsed(TimeUnit.SECONDS), secsPer);
      stopwatch.reset().start();

      System.out.printf("%n3 Accumulate Tally =============================================================%n");
      stopwatch.reset().start();

      runAccumulateBallots(group, cmdLine.outputDir, cmdLine.outputDir, "RunRemoteWorkflowTest", "RunRemoteWorkflowTest");

      System.out.printf("*** accumTally elapsed = %d sec%n", stopwatch.elapsed(TimeUnit.SECONDS));

      System.out.printf("%n4 Decryption =============================================================%n");
      stopwatch.reset().start();

      int navailable = cmdLine.navailable > 0 ? cmdLine.navailable : cmdLine.quorum;
      List<Integer> present = IntStream.range(1, navailable + 1)
              .boxed()
              .collect(Collectors.toList());

      // Run RemoteDecryption
      RunRemoteDecryptionTest.main(
              new String[] {
                      "-in", cmdLine.outputDir,
                      "-out", cmdLine.outputDir,
                      "-navailable", Integer.toString(navailable),
                      "-trusteeDir", trusteeDir,
                      "-cmdOutput", cmdDir
              }
      );

      System.out.printf("*** decryptBallots elapsed = %d sec%n", stopwatch.elapsed(TimeUnit.SECONDS));

      System.out.printf("%n5 Verify =============================================================%n");
      stopwatch.reset().start();

      ElectionRecord record = electionRecordFromConsumer(new Consumer(cmdLine.outputDir, group));
      Verifier verifier = new Verifier(record, 11);
      boolean ok = verifier.verify();
      System.out.printf("Verify is ok = %s%n", ok);

      System.out.printf("*** verifyElectionRecord elapsed = %d sec%n", stopwatch.elapsed(TimeUnit.SECONDS));

      System.out.printf("%n*** All took = %d sec%n", stopwatchAll.elapsed(TimeUnit.SECONDS));
    } catch (Exception e) {
      e.printStackTrace();
      System.exit(1);
    }
    System.exit(0);
  }

}
