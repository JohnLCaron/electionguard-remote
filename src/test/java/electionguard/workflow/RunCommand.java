package electionguard.workflow;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Run a command line program asynchronously
 */
public class RunCommand implements Callable<Boolean> {
  final String name;
  final String outputDir;
  final String[] args;

  Process process;
  boolean statusReturn;
  Throwable thrownException;

  RunCommand(String name, String outputDir, ListeningExecutorService service, String... args) {
    this.name = name;
    this.outputDir = outputDir;
    this.args = args;

    ListenableFuture<Boolean> future = service.submit(this);
    Futures.addCallback(
            future,
            new FutureCallback<>() {
              public void onSuccess(Boolean status) {
                statusReturn = status;
              }

              public void onFailure(Throwable thrown) {
                thrownException = thrown;
              }
            },
            service);
  }

  @Override
  public Boolean call() throws Exception {
    return run(args);
  }

  public boolean waitFor(long nsecs) throws InterruptedException {
    if (process != null) {
      return process.waitFor(nsecs, TimeUnit.SECONDS);
    }
    return false;
  }

  public int kill() {
    if (process != null) {
      process.destroyForcibly();
      try {
        return process.waitFor();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
    return -1;
  }

  public void show() throws IOException {
    System.out.printf("-----------------------------------------%n");
    System.out.printf("Command %s%n", String.join(" ", args));

    System.out.printf("---StdOut---%n");
    try (Stream<String> lines = Files.lines(getStdOutFile().toPath())) {
      lines.forEach(line -> System.out.printf("%s%n", line));
    }

    System.out.printf("---StdErr---%n");
    try (Stream<String> lines = Files.lines(getStdErrFile().toPath())) {
      lines.forEach(line -> System.out.printf("%s%n", line));
    }

    System.out.printf("---Done status = %s%n", this.statusReturn);
  }

  File getStdOutFile() {
    return new File(outputDir + name + ".stdout");
  }

  File getStdErrFile() {
    return new File(outputDir + name + ".stderr");
  }

  private boolean run(String... args) throws IOException {
    System.out.printf(">Running command %s%n", String.join(" ", args));
    ProcessBuilder builder = new ProcessBuilder(args)
            .redirectOutput(getStdOutFile())
            .redirectError(getStdErrFile());
    this.process = builder.start();
    return true;
  }
}
