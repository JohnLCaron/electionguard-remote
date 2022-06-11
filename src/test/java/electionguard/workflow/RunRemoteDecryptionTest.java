package electionguard.workflow;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runs the decryption, using remote guardians. Encryption and Tally Accumulation has already been run.
 * Runs the components out of the fatJar, so be sure to build that first: "./gradlew clean assemble fatJar"
 * Also be sure to keep RunStandardWorkflow.classpath synched with fatjar SHAPSHOT version.
 * <p>
 * For command line help:
 * <strong>
 * <pre>
 *  java -classpath electionguard-java-all.jar com.sunya.electionguard.workflow.RunDecryptionRemote --help
 * </pre>
 * </strong>
 */
public class RunRemoteDecryptionTest {
  public static final String classpath = RunRemoteKeyCeremonyTest.classpath;
  private static final String REMOTE_TRUSTEE = "remoteTrustee";
  private static final String CMD_OUTPUT = "/home/snake/tmp/electionguard/RunRemoteDecryptionTest/";

  private static class CommandLine {
    @Parameter(names = {"-trusteeDir"}, order = 4,
            description = "Directory containing DecryptingTrustee serializations", required = true)
    String trusteeDir;

    @Parameter(names = {"-in"}, order = 5,
            description = "Directory containing ballot encryption and tally", required = true)
    String encryptDir;

    @Parameter(names = {"-navailable"}, order = 7, description = "Number of guardians available for decryption", required = true)
    int navailable = 0;

    @Parameter(names = {"-out"}, order = 8,
            description = "Directory where complete election record is published", required = true)
    String outputDir;

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
    String progName = RunRemoteDecryptionTest.class.getName();
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

    ListeningExecutorService service = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(11));
    List<RunCommand> running = new ArrayList<>();

    System.out.printf("%n4=============================================================%n");
    // DecryptBallots
    int navailable = cmdLine.navailable;
    RunCommand decryptBallots = new RunCommand("RunRemoteDecryptor", CMD_OUTPUT, service,
            "java",
            "-classpath", classpath,
            "electionguard.decrypt.RunRemoteDecryptor",
            "-in", cmdLine.encryptDir,
            "-out", cmdLine.outputDir,
            "-navailable", Integer.toString(navailable)
    );

    running.add(decryptBallots);
    try {
      Thread.sleep(1000);
    } catch (Throwable e) {
      e.printStackTrace();
      System.exit(1);
    }

    int count = 0;
    for (String trusteeFilename : trusteeFiles(cmdLine.trusteeDir)) {
      RunCommand command = new RunCommand("DecryptingRemoteTrustee" + count++, CMD_OUTPUT, service,
              "java",
              "-classpath", classpath,
              "electionguard.decrypt.RunRemoteDecryptingTrustee",
              "-trusteeFile", cmdLine.trusteeDir + "/" + trusteeFilename
              );
      running.add(command);
    }

    try {
      if (!decryptBallots.waitFor(300)) {
        System.out.format("Kill decryptBallots = %d%n", decryptBallots.kill());
      }
    } catch (Throwable e) {
      e.printStackTrace();
    }

    System.out.printf("*** decryptBallots elapsed = %d sec%n", stopwatch.elapsed(TimeUnit.SECONDS));
    stopwatch.reset().start();

    System.out.printf("%n*** All took = %d sec%n", stopwatchAll.elapsed(TimeUnit.SECONDS));

    try {
      for (RunCommand command : running) {
        command.kill();
        command.show();
      }
    } catch (IOException e) {
      e.printStackTrace();
    }

    System.exit(0);
  }

  private static String[] trusteeFiles(String trusteeDir) {
    Path trusteePath = Path.of(trusteeDir);
    if (!Files.exists(trusteePath) || !Files.isDirectory(trusteePath)) {
      throw new RuntimeException("Trustee dir '" + trusteeDir + "' does not exist");
    }
    return trusteePath.toFile().list();
  }

}
