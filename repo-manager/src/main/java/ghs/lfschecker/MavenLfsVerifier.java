package ghs.lfschecker;

import ghs.classify.RepoClassifier;
import ghs.classify.RepoClassifier.WarmupRepoIssue;
import ghs.config.Settings;
import ghs.exec.ProcessRunner;
import ghs.git.GitCloner;
import ghs.maven.MavenExecutor;
import ghs.scan.ArtifactScanner;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MavenLfsVerifier {

  private static final Pattern URL_WITH_CREDENTIALS = Pattern.compile(
    "^https?://[^/\\s]+:[^@\\s]+@.*$"
  );

  // Stato condiviso
  private static final Set<String> SKIP_BUILD = ConcurrentHashMap.newKeySet();
  private static final List<String> COMPLETED = Collections.synchronizedList(
    new ArrayList<>()
  );

  public static void main(String[] args) throws Exception {
    Settings s = Settings.fromEnv();

    if (args.length == 1 && "--lfs".equals(args[0])) {
      secondPassWithLfs(s);
      return;
    }
    if (args.length != 1) {
      System.err.println("Usage: java MavenLfsVerifier <repos.txt>");
      System.err.println("       java MavenLfsVerifier --lfs");
      System.exit(1);
    }

    List<String> urls = Files.readAllLines(
      Paths.get(args[0]),
      StandardCharsets.UTF_8
    )
      .stream()
      .filter(x -> !x.isBlank())
      .collect(Collectors.toList());

    Files.createDirectories(s.baseDir());
    List<String> needsLfs = new ArrayList<>();
    RepoClassifier classifier = new RepoClassifier();
    GitCloner git = new GitCloner();
    MavenExecutor mvn = new MavenExecutor();
    ArtifactScanner scanner = new ArtifactScanner();

    // clean *.lastUpdated opzionale
    if (s.cleanLastUpdated()) {
      int removed = classifier.cleanLastUpdated(Paths.get(s.localRepo()));
      System.out.println(
        "Rimossi " +
        removed +
        " file *.lastUpdated nella cache: " +
        s.localRepo()
      );
    }

    /* ---------- clone parallelo ---------- */
    System.out.println("Clonazione repository …");
    ExecutorService clonePool = Executors.newFixedThreadPool(
      Math.max(4, Runtime.getRuntime().availableProcessors())
    );
    Map<String, Path> repoToPath = new ConcurrentHashMap<>();
    for (String url : urls) {
      clonePool.submit(() -> {
        Path dest = cloneRepository(url, s, git);
        if (dest != null) repoToPath.put(url, dest);
      });
    }
    clonePool.shutdown();
    clonePool.awaitTermination(6, TimeUnit.HOURS);

    /* ---------- warm-up parallelo bounded ---------- */
    System.out.println(
      "Warm-up dependency:go-offline in parallelo controllato …"
    );
    int cores = Runtime.getRuntime().availableProcessors();
    int k = Math.min(4, Math.max(2, cores));
    ExecutorService warmPool = Executors.newFixedThreadPool(k);
    for (Map.Entry<String, Path> e : repoToPath.entrySet()) {
      warmPool.submit(() ->
        warmUpRepo(e.getKey(), e.getValue(), s, mvn, classifier)
      );
    }
    warmPool.shutdown();
    warmPool.awaitTermination(6, TimeUnit.HOURS);

    /* ---------- build parallela offline ---------- */
    System.out.println("Build parallele – fase verify …");
    ExecutorService buildPool = Executors.newFixedThreadPool(
      Math.max(4, Runtime.getRuntime().availableProcessors())
    );
    for (Map.Entry<String, Path> entry : repoToPath.entrySet()) {
      String url = entry.getKey();
      if (SKIP_BUILD.contains(url)) continue;
      Path dir = entry.getValue();
      buildPool.submit(() -> handleBuild(url, dir, s, mvn, scanner, needsLfs));
    }
    buildPool.shutdown();
    while (!buildPool.awaitTermination(30, TimeUnit.SECONDS)) {
      int active = ((ThreadPoolExecutor) buildPool).getActiveCount();
      System.out.printf("Thread Maven attivi: %d%n", active);
    }

    /* ---------- output ---------- */
    Files.write(s.needsLfsFile(), needsLfs, StandardCharsets.UTF_8);
    System.out.println("\nSalvato needs-lfs.txt → " + s.needsLfsFile());

    Files.write(
      s.okFile(),
      COMPLETED,
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING
    );
    System.out.println("\nRepository compilati in questo run:");
    COMPLETED.forEach(r -> System.out.println("   • " + r));
  }

  /* ================= orchestrazione per repo ================= */

  private static Path cloneRepository(String url, Settings s, GitCloner git) {
    if (URL_WITH_CREDENTIALS.matcher(url).matches()) {
      System.out.println("Skip repo con credenziali: " + url);
      return null;
    }
    String name = repoName(url);
    Path dest = s.baseDir().resolve(name);
    try {
      if (Files.exists(dest)) {
        System.out.println("Repo già presente, salto clone: " + name);
        return dest;
      }
      System.out.println("Clonando (JGit) " + name);
      return git.cloneShallowJGit(url, dest);
    } catch (Exception ex) {
      System.err.println("Errore clone " + name + ": " + ex.getMessage());
      return null;
    }
  }

  private static void warmUpRepo(
    String repoUrl,
    Path repoDir,
    Settings s,
    MavenExecutor mvn,
    RepoClassifier classifier
  ) {
    if (Files.notExists(repoDir.resolve("pom.xml"))) return;

    // Pre-filtro JDK8/tools.jar
    if (classifier.requiresJdk8Tools(repoDir)) {
      System.out.println("Richiede JDK8 (tools.jar): " + repoDir.getFileName());
      RepoClassifier.appendLine(s.needsJdk8File(), repoUrl);
      SKIP_BUILD.add(repoUrl);
      return;
    }

    String lastOutput = "";
    for (int attempt = 1; attempt <= s.maxRetries(); attempt++) {
      ProcessRunner.Result res = mvn.warmup(repoDir, s);
      if (res.exit() == 0) {
        System.out.println("✅ Warm-up OK: " + repoUrl);
        return;
      }
      lastOutput = res.output();
      System.err.printf(
        "⚠️  go-offline fallito (%d/%d) per %s%n",
        attempt,
        s.maxRetries(),
        repoDir.getFileName()
      );
      sleepSilently(2);
    }

    WarmupRepoIssue issue = classifier.classifyWarmupFailure(lastOutput);
    if (issue != WarmupRepoIssue.NONE) {
      String note =
        switch (issue) {
          case HTTP_401 -> "  # 401 Unauthorized durante go-offline";
          case HTTP_403 -> "  # 403 Forbidden durante go-offline";
          case HTTP_409 -> "  # 409 Conflict durante go-offline";
          case SNAPSHOT_MISSING -> "  # SNAPSHOT mancante/non pubblicato durante go-offline";
          default -> "";
        };

      RepoClassifier.appendLine(s.needsExtRepoFile(), repoUrl + note);
      SKIP_BUILD.add(repoUrl);
    }
    System.err.println("Warm-up NON riuscito per " + repoDir.getFileName());
  }

  private static void handleBuild(
    String url,
    Path repoDir,
    Settings s,
    MavenExecutor mvn,
    ArtifactScanner scanner,
    List<String> needsLfs
  ) {
    String name = repoName(url);
    try {
      // slack 2s per sicurezza su FS con risoluzione grossolana
      Instant start = Instant.now().minusSeconds(2);

      if (mvn.verify(repoDir, s) == 0) {
        // Artefatti "nuovi" (JAR/WAR/EAR) + directories target/classes con .class
        List<Path> jarArtifacts = scanner.listProducedArtifacts(repoDir, start);
        List<Path> classDirs = scanner.listClassesDirs(repoDir, start);

        List<Path> artifacts = new ArrayList<>(
          jarArtifacts.size() + classDirs.size()
        );
        artifacts.addAll(jarArtifacts);
        artifacts.addAll(classDirs);

        if (!artifacts.isEmpty()) {
          COMPLETED.add(name);
          System.out.println("Build OK (tests skipped): " + name);
          artifacts.forEach(p ->
            System.out.println(
              "   ↳ artefatto: " + ArtifactScanner.relToBase(s.baseDir(), p)
            )
          );

          // opzionale: genera classpath.txt per i moduli rilevati
          maybeGenerateClasspath(artifacts, s);
        } else {
          // Fallback: artefatti preesistenti
          List<Path> existing = new ArrayList<>();
          existing.addAll(
            scanner.listProducedArtifacts(repoDir, Instant.EPOCH)
          );
          existing.addAll(scanner.listClassesDirs(repoDir, Instant.EPOCH));

          if (!existing.isEmpty()) {
            COMPLETED.add(name);
            System.out.println(
              "BUILD SUCCESS (artefatti preesistenti): " + name
            );
            existing.forEach(p ->
              System.out.println(
                "   ↳ artefatto: " + ArtifactScanner.relToBase(s.baseDir(), p)
              )
            );
            maybeGenerateClasspath(existing, s);
          } else {
            System.out.println(
              "BUILD SUCCESS ma nessun artefatto trovato: " + name
            );
            if (usesLfs(repoDir)) {
              synchronized (needsLfs) {
                needsLfs.add(url);
              }
              System.out.println(
                "Contrassegnato per seconda passata con LFS: " + name
              );
            }
          }
        }
      } else {
        if (usesLfs(repoDir)) {
          synchronized (needsLfs) {
            needsLfs.add(url);
          }
          System.out.println("Build KO ma usa LFS: " + name);
        } else {
          System.out.println("Build KO: " + name);
        }
      }
    } catch (Exception ex) {
      System.err.println("Errore build " + name + ": " + ex.getMessage());
    }
  }

  /* ================= util locali ================= */

  /**
   * Se GENERATE_CLASSPATH=1, per ogni artefatto che appartiene a un modulo Maven
   * (JAR in target/… o directory target/classes) genera target/classpath.txt
   * tramite dependency:build-classpath (scope configurabile via CLASSPATH_SCOPE).
   */
  private static void maybeGenerateClasspath(List<Path> artifacts, Settings s) {
    if (
      !"1".equals(System.getenv().getOrDefault("GENERATE_CLASSPATH", "0"))
    ) return;
    String scope = System.getenv().getOrDefault("CLASSPATH_SCOPE", "compile");

    // deduplica moduli: da .../module/target/classes o .../module/target/*.jar → modulo = .../module
    Set<Path> modules = new LinkedHashSet<>();
    for (Path p : artifacts) {
      Path module = null;
      if (Files.isDirectory(p)) { // target/classes
        Path target = p.getParent(); // .../target
        module = (target != null) ? target.getParent() : null; // .../module
      } else { // file jar/war/ear in .../target
        Path target = p.getParent(); // .../target
        module = (target != null) ? target.getParent() : null; // .../module
      }
      if (module != null && Files.isDirectory(module)) {
        modules.add(module);
      }
    }

    for (Path module : modules) {
      try {
        String mvnCmd = resolveMavenExecutable(module);
        Path out = module.resolve("target").resolve("classpath.txt");
        Files.createDirectories(out.getParent());
        List<String> cmd = List.of(
          mvnCmd,
          "-Dmaven.repo.local=" + s.localRepo(),
          "--offline",
          "-q",
          "-DincludeScope=" + scope,
          "-Dmdep.outputFile=" + out.toAbsolutePath().toString(),
          "dependency:build-classpath"
        );
        int exit = ProcessRunner.run(cmd, module, Duration.ofMinutes(5));
        if (exit == 0) {
          System.out.println(
            "   ↳ classpath.txt generato: " +
            ArtifactScanner.relToBase(s.baseDir(), out)
          );
        } else {
          System.out.println(
            "   ↳ classpath.txt NON generato per " +
            ArtifactScanner.relToBase(s.baseDir(), module)
          );
        }
      } catch (Exception e) {
        System.out.println(
          "   ↳ classpath.txt errore su " +
          ArtifactScanner.relToBase(s.baseDir(), module) +
          ": " +
          e.getMessage()
        );
      }
    }
  }

  // Piccolo helper locale (evita dipendenza circolare). Duplicato volutamente qui.
  private static String resolveMavenExecutable(Path repoDir) {
    boolean win = System.getProperty("os.name").toLowerCase().contains("win");
    Path mvnw = repoDir.resolve(win ? "mvnw.cmd" : "mvnw");
    if (Files.isRegularFile(mvnw) && Files.isExecutable(mvnw)) {
      return mvnw.toAbsolutePath().toString(); // wrapper del repo
    }
    if (isOnPath(win ? "mvnd.cmd" : "mvnd")) {
      return win ? "mvnd.cmd" : "mvnd"; // Maven Daemon se presente
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
      if (Files.isExecutable(Paths.get(dir, exe))) return true;
    }
    return false;
  }

  /* ================= vari utility rimaste qui ================= */

  private static String repoName(String url) {
    String name = url.substring(url.lastIndexOf('/') + 1);
    return name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
  }

  private static boolean usesLfs(Path repoDir) {
    Path attr = repoDir.resolve(".gitattributes");
    if (!Files.exists(attr)) return false;
    try (java.util.stream.Stream<String> lines = Files.lines(attr)) {
      return lines.anyMatch(l -> l.contains("filter=lfs"));
    } catch (Exception e) {
      return false;
    }
  }

  private static void sleepSilently(int seconds) {
    try {
      TimeUnit.SECONDS.sleep(seconds);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  /* ---------------- seconda pass LFS ---------------- */
  private static void secondPassWithLfs(Settings s) throws Exception {
    if (!Files.exists(s.needsLfsFile())) {
      System.out.println("Nessun needs-lfs.txt: niente da fare.");
      return;
    }
    List<String> urls = Files.readAllLines(
      s.needsLfsFile(),
      StandardCharsets.UTF_8
    )
      .stream()
      .filter(x -> !x.isBlank())
      .collect(Collectors.toList());

    GitCloner git = new GitCloner();
    MavenExecutor mvn = new MavenExecutor();

    ExecutorService pool = Executors.newFixedThreadPool(
      Math.max(4, Runtime.getRuntime().availableProcessors())
    );
    for (String url : urls) pool.submit(() ->
      reprocessRepoWithLfs(url, s, git, mvn)
    );
    pool.shutdown();
    pool.awaitTermination(6, TimeUnit.HOURS);
  }

  private static void reprocessRepoWithLfs(
    String url,
    Settings s,
    GitCloner git,
    MavenExecutor mvn
  ) {
    String name = repoName(url);
    Path dest = s.baseDir().resolve(name + "_lfs");
    try {
      System.out.println("Second pass (Git+LFS) su " + name);
      git.cloneWithLfsCli(url, dest, s);
      if (mvn.verify(dest, s) == 0) {
        System.out.println("Build OK con LFS: " + name);
      } else {
        System.out.println("Ancora KO dopo LFS: " + name);
      }
    } catch (Exception ex) {
      System.err.println(
        "Errore seconda pass su " + name + ": " + ex.getMessage()
      );
    }
  }
}
