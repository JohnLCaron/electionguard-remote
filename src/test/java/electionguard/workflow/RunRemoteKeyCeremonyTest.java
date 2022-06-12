package electionguard.workflow;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runs the KeyCeremony remote workflow from start to finish, using remote guardians.
 * Runs the components out of the fatJar, so be sure to build that first: "./gradlew clean assemble fatJar"
 * Also be sure to keep RunRemoteWorkflowTest.classpath synched with fatjar SHAPSHOT version.
 */
public class RunRemoteKeyCeremonyTest {
  private static final String classpath = RunRemoteWorkflowTest.classpath;
  private static final String REMOTE_TRUSTEE = "remoteTrustee";
  private static final String CMD_OUTPUT = "/home/snake/tmp/electionguard/RunRemoteKeyCeremonyTest/";

  private static class CommandLine {
    @Parameter(names = {"-in"}, order = 0,
            description = "Directory to read input election manifest", required = true)
    String inputDir;

    @Parameter(names = {"-nguardians"}, order = 2, description = "Number of guardians to create", required = true)
    int nguardians = 6;

    @Parameter(names = {"-quorum"}, order = 3, description = "Number of guardians that make a quorum", required = true)
    int quorum = 5;

    @Parameter(names = {"-trusteeDir"}, order = 4,
            description = "Directory to write Trustee serializations", required = true)
    String trusteeDir;

    @Parameter(names = {"-out"}, order = 5,
            description = "Directory to write output election record", required = true)
    String encryptDir;

    @Parameter(names = {"-cmdOutput"}, order = 9,
            description = "Directory where command output is written")
    String cmdOutput;

    @Parameter(names = {"-h", "--help"}, order = 99, description = "Display this help and exit", help = true)
    boolean help = false;

    private final JCommander jc;

    public CommandLine(String progName, String[] args) throws ParameterException {
      this.jc = new JCommander(this);
      this.jc.parse(args);
      jc.setProgramName(String.format("java -classpath electionguard-remote-all.jar %s", progName));
    }

    public void printUsage() {
      jc.usage();
    }
  }

  public static void main(String[] args) throws IOException {
    String progName = RunRemoteKeyCeremonyTest.class.getName();
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

    String cmdOutput = cmdLine.cmdOutput != null ? cmdLine.cmdOutput : CMD_OUTPUT;

    ListeningExecutorService service = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(11));
    List<RunCommand> running = new ArrayList<>();

    // PerformKeyCeremony
    RunCommand keyCeremonyRemote = new RunCommand("RunRemoteKeyCeremony", cmdOutput, service,
            "java",
            "-classpath", classpath,
            "electionguard.keyceremony.RunRemoteKeyCeremony",
            "-in", cmdLine.inputDir,
            "-out", cmdLine.encryptDir,
            "-nguardians", Integer.toString(cmdLine.nguardians),
            "-quorum", Integer.toString(cmdLine.quorum)
            );
    running.add(keyCeremonyRemote);
    try {
      Thread.sleep(1000);
    } catch (Throwable e) {
      e.printStackTrace();
      System.exit(1);
    }

    for (int i=1; i <= cmdLine.nguardians; i++) {
      RunCommand command = new RunCommand("RunRemoteTrustee" + i, cmdOutput, service,
              "java",
              "-classpath", classpath,
              "electionguard.keyceremony.RunRemoteTrustee",
              "-name", REMOTE_TRUSTEE + i,
              "-out", cmdLine.trusteeDir);
      running.add(command);
    }

    try {
      if (!keyCeremonyRemote.waitFor(30)) {
        System.out.format("Kill RunRemoteKeyCeremony = %d%n", keyCeremonyRemote.kill());
      }
    } catch (Throwable e) {
      e.printStackTrace();
    }
    System.out.printf("*** RunRemoteKeyCeremony finished time elapsed = %d ms%n", stopwatch.elapsed(TimeUnit.MILLISECONDS));

    try {
      for (RunCommand command : running) {
        command.kill();
        command.show();
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

}
