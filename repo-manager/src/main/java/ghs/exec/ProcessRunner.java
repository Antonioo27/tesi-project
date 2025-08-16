package ghs.exec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;

public final class ProcessRunner {

  public record Result(int exit, String output) {}

  /** Esegue il comando con timeout, drena stdout/stderr, ritorna solo l'exit code. */
  public static int run(List<String> cmd, Path cwd, Duration timeout) {
    return runCapture(cmd, cwd, timeout).exit();
  }

  /** Come run(...), ma cattura l'output (per classificare gli errori di warm-up). */
  public static Result runCapture(
    List<String> cmd,
    Path cwd,
    Duration timeout
  ) {
    ExecutorService ioPool = Executors.newSingleThreadExecutor();
    final StringBuilder sb = new StringBuilder(4096);
    try {
      ProcessBuilder pb = new ProcessBuilder(cmd);
      if (cwd != null) pb.directory(cwd.toFile());
      pb.redirectErrorStream(true);

      final Process p = pb.start();
      Future<?> pump = ioPool.submit(() -> {
        try (
          BufferedReader r = new BufferedReader(
            new InputStreamReader(p.getInputStream())
          )
        ) {
          String line;
          while ((line = r.readLine()) != null) {
            System.out.println(line);
            sb.append(line).append('\n');
          }
        } catch (IOException ignored) {}
      });

      boolean finished;
      if (timeout.isZero()) {
        p.waitFor();
        finished = true;
      } else {
        finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) destroyProcessTree(p);
      }
      try {
        pump.get(5, TimeUnit.SECONDS);
      } catch (Exception ignored) {}
      return new Result(finished ? p.exitValue() : -1, sb.toString());
    } catch (Exception e) {
      System.err.println("Errore processo: " + e.getMessage());
      return new Result(-1, sb.toString());
    } finally {
      ioPool.shutdownNow();
    }
  }

  private static void destroyProcessTree(Process p) {
    try {
      ProcessHandle.of(p.pid()).ifPresent(ph -> {
        ph.descendants().forEach(child -> child.destroyForcibly());
        ph.destroyForcibly();
      });
    } catch (Exception ignored) {}
  }
}
