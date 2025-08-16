package ghs.maven;

import ghs.config.Settings;
import ghs.exec.ProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class MavenExecutor {

  public int verify(Path repoDir, Settings s) {
    String mvn = resolveMavenExecutable(repoDir);
    List<String> cmd = List.of(
      mvn,
      "-Dmaven.repo.local=" + s.localRepo,
      "--offline",
      "-B",
      "-DskipTests",
      "verify"
    );
    return ProcessRunner.run(cmd, repoDir, s.buildTimeout());
  }

  public ProcessRunner.Result warmup(Path repoDir, Settings s) {
    String mvn = resolveMavenExecutable(repoDir);
    List<String> base = new ArrayList<>(
      List.of(
        mvn,
        "-Dmaven.repo.local=" + s.localRepo,
        "-B",
        "-nsu",
        "-q",
        "-DincludePlugins=true",
        "-DincludeVersionedPlugins=true",
        "-DincludePluginDependencies=true",
        "dependency:go-offline"
      )
    );
    if (!s.warmupProfiles().isEmpty()) {
      base.add(1, "-P" + s.warmupProfiles());
    }
    return ProcessRunner.runCapture(base, repoDir, s.warmupTimeout());
  }

  private static String resolveMavenExecutable(Path repoDir) {
    boolean win = System.getProperty("os.name").toLowerCase().contains("win");
    Path mvnw = repoDir.resolve(win ? "mvnw.cmd" : "mvnw");
    if (Files.isRegularFile(mvnw) && Files.isExecutable(mvnw)) {
      return mvnw.toAbsolutePath().toString();
    }
    if (isOnPath(win ? "mvnd.cmd" : "mvnd")) {
      return win ? "mvnd.cmd" : "mvnd";
    }
    return win ? "mvn.cmd" : "mvn";
  }

  private static boolean isOnPath(String exe) {
    String path = System.getenv("PATH");
    if (path == null) return false;
    String sep = System.getProperty("os.name").toLowerCase().contains("win")
      ? ";"
      : ":";
    for (String dir : path.split(sep)) {
      if (
        java.nio.file.Files.isExecutable(java.nio.file.Paths.get(dir, exe))
      ) return true;
    }
    return false;
  }
}
