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
 * Also be sure to keep RunRemoteWorkflowTest.classpath synched with fatjar SHAPSHOT version.
 */
public class RunRemoteDecryptionTest {
  private static final String classpath = RunRemoteWorkflowTest.classpath;
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

    @Parameter(names = {"-cmdOutput"}, order = 9,
            description = "Directory where command output is written")
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

  public static void main(String[] args) throws IOException {
    String progName = RunRemoteDecryptionTest.class.getName();
    CommandLine cmdLine;
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

    String cmdOutput = cmdLine.cmdOutput != null ? cmdLine.cmdOutput : CMD_OUTPUT;

    int navailable = cmdLine.navailable;
    RunCommand decryptBallots = new RunCommand("RunRemoteDecryptor", cmdOutput, service,
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
      RunCommand command = new RunCommand("DecryptingRemoteTrustee" + count++, cmdOutput, service,
              "java",
              "-classpath", classpath,
              "electionguard.decrypt.RunRemoteDecryptingTrustee",
              "-trusteeFile", cmdLine.trusteeDir + "/" + trusteeFilename
              );
      running.add(command);
      if (count >= navailable) {
        break;
      }
    }

    try {
      if (!decryptBallots.waitFor(300)) {
        System.out.format("Kill RunRemoteDecryptor = %d%n", decryptBallots.kill());
      }
    } catch (Throwable e) {
      e.printStackTrace();
    }

    System.out.printf("*** RemoteDecryptor finished elapsed time = %d sec%n", stopwatch.elapsed(TimeUnit.SECONDS));

    try {
      for (RunCommand command : running) {
        command.kill();
        command.show();
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private static String[] trusteeFiles(String trusteeDir) {
    Path trusteePath = Path.of(trusteeDir);
    if (!Files.exists(trusteePath) || !Files.isDirectory(trusteePath)) {
      throw new RuntimeException("Trustee dir '" + trusteeDir + "' does not exist");
    }
    return trusteePath.toFile().list();
  }

}
