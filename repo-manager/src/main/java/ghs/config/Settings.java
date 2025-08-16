package ghs.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

public final class Settings {

  // File/dir di lavoro
  public final Path baseDir = Paths.get("cloned_repos");
  public final Path needsLfsFile = Paths.get("needs-lfs.txt");
  public final Path needsJdk8File = Paths.get("needs-jdk8.txt");
  public final Path needsExtRepoFile = Paths.get("needs-external-repo.txt");
  public final Path okFile = Paths.get("build-ok.txt");

  // Parametri runtime
  public final String localRepo;
  public final int maxRetries = 2;
  public final Duration warmupTimeout;
  public final Duration buildTimeout;
  public final Duration gitTimeout;
  public final boolean cleanLastUpdated;
  public final String warmupProfiles; // es. "prod,ci"

  private Settings(
      String localRepo,
      Duration warmup,
      Duration build,
      Duration git,
      boolean clean,
      String profiles) {
    this.localRepo = localRepo;
    this.warmupTimeout = warmup;
    this.buildTimeout = build;
    this.gitTimeout = git;
    this.cleanLastUpdated = clean;
    this.warmupProfiles = profiles;
  }

  public static Settings fromEnv() {
    String local = System.getenv().getOrDefault("MAVEN_LOCAL_REPO", "C:/temp/m2-cache");
    int wu = Integer.parseInt(System.getenv().getOrDefault("MAVEN_WARMUP_TIMEOUT_MIN", "20"));
    int bu = Integer.parseInt(System.getenv().getOrDefault("MAVEN_BUILD_TIMEOUT_MIN", "40"));
    int gt = Integer.parseInt(System.getenv().getOrDefault("GIT_TIMEOUT_MIN", "10"));
    boolean clean = "1".equals(System.getenv().getOrDefault("CLEAN_LASTUPDATED", "0"));
    String profiles = System.getenv().getOrDefault("WARMUP_PROFILES", "").trim();
    return new Settings(
        local,
        Duration.ofMinutes(wu),
        Duration.ofMinutes(bu),
        Duration.ofMinutes(gt),
        clean,
        profiles);
  }

  /* ===================== Getter richiesti dal resto del codice ===================== */

  public Path baseDir() { return baseDir; }
  public Path needsLfsFile() { return needsLfsFile; }
  public Path needsJdk8File() { return needsJdk8File; }
  public Path needsExtRepoFile() { return needsExtRepoFile; }
  public Path okFile() { return okFile; }

  public String localRepo() { return localRepo; }
  public int maxRetries() { return maxRetries; }
  public Duration warmupTimeout() { return warmupTimeout; }
  public Duration buildTimeout() { return buildTimeout; }
  public Duration gitTimeout() { return gitTimeout; }
  public boolean cleanLastUpdated() { return cleanLastUpdated; }
  public String warmupProfiles() { return warmupProfiles; }
}
