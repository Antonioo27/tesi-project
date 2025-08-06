package ghs.lfschecker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

/**
 * Screening di massa dei repository Maven:
 *  1. Clona rapidamente con JGit (shallow, ramo principale se esiste, altrimenti master).
 *  2. Esegue "mvn verify" (test compilati ma non eseguiti).
 *  3. Se la build fallisce e il repo usa Git-LFS, lo aggiunge a needs-lfs.txt.
 *  4. (Facoltativo) seconda passata con git-lfs sui repo falliti.
 *
 *  Requisiti:
 *   - Java 17+
 *   - git & git-lfs nel PATH
 *   - Variabile d’ambiente GITHUB_TOKEN per repo privati / rate-limit alto
 */
public class MavenLfsVerifier {

  private static final Path BASE_DIR = Paths.get("cloned_repos");
  private static final Path NEEDS_LFS_FILE = Paths.get("needs-lfs.txt");
  private static final Path OK_FILE = Paths.get("build-ok.txt");
  private static String LOCAL_REPO; // cache Maven dedicata

  /** repo che hanno compilato (test saltati) durante **questo** run */
  private static final List<String> completed = Collections.synchronizedList(
    new ArrayList<>()
  );

  /** URL Git con credenziali hard-coded → skip */
  private static final Pattern URL_WITH_CREDENTIALS = Pattern.compile(
    "^https?://[^/\\s]+:[^@\\s]+@.*$"
  );

  public static void main(String[] args) throws Exception {
    LOCAL_REPO = System.getenv()
      .getOrDefault("MAVEN_LOCAL_REPO", "C:\\temp\\m2-cache");

    if (args.length == 1 && "--lfs".equals(args[0])) {
      secondPassWithLfs();
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
      .filter(s -> !s.isBlank())
      .collect(Collectors.toList());

    Files.createDirectories(BASE_DIR);
    List<String> needsLfs = new ArrayList<>();

    ExecutorService pool = Executors.newFixedThreadPool(
      Math.max(4, Runtime.getRuntime().availableProcessors())
    );

    for (String url : urls) pool.submit(() -> handleRepository(url, needsLfs));

    pool.shutdown();

    /* monitor ogni 30 s, senza timeout globale */
    while (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
      int active = ((ThreadPoolExecutor) pool).getActiveCount();
      System.out.printf("Thread Maven attivi: %d%n", active);
    }

    /* salva (o aggiorna) needs-lfs.txt */
    Files.write(NEEDS_LFS_FILE, needsLfs, StandardCharsets.UTF_8);
    System.out.println("Salvato needs-lfs.txt → " + NEEDS_LFS_FILE);

    /* riepilogo di fine run */
    System.out.println("\nRepository compilati in questo run:");
    completed.forEach(r -> System.out.println("   • " + r));
  }

  /* ================================================================ */
  /*                         REPO HANDLER                             */
  /* ================================================================ */

  private static void handleRepository(String url, List<String> needsLfs) {
    if (URL_WITH_CREDENTIALS.matcher(url).matches()) {
      System.out.println("Skip repo con credenziali: " + url);
      return;
    }

    String name = repoName(url);
    Path dest = BASE_DIR.resolve(name);

    try {
      if (Files.exists(dest)) {
        System.out.println("Repo già presente, salto clone: " + name);
      } else {
        System.out.println("Clonando (JGit) " + name);
        cloneWithJGit(url, dest);
      }

      if (runMavenVerify(dest)) { // BUILD SUCCESS
        completed.add(name);
        appendBuildOk(name); // <-- persistenza immediata
        System.out.println("✅ Build OK (tests skipped): " + name);
        return;
      }

      /* BUILD KO → verifica LFS */
      if (usesLfs(dest)) {
        synchronized (needsLfs) {
          needsLfs.add(url);
        }
        System.out.println("Build KO ma usa LFS: " + name);
      } else {
        System.out.println("Build KO: " + name);
      }
    } catch (Exception ex) {
      System.err.println("Errore su " + name + ": " + ex.getMessage());
    }
  }

  /* ---------------------------------------------------------------- */
  /*                        PERSISTENZA build-ok                      */
  /* ---------------------------------------------------------------- */

  /** Scrive <name> in build-ok.txt, creando il file se manca. */
  private static synchronized void appendBuildOk(String name) {
    try {
      Files.write(
        OK_FILE,
        List.of(name),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      );
    } catch (IOException e) {
      System.err.println(
        "Impossibile scrivere build-ok.txt: " + e.getMessage()
      );
    }
  }

  /* ================================================================ */
  /*                          CLONE METHODS                           */
  /* ================================================================ */

  private static void cloneWithJGit(String url, Path dest) throws Exception {
    String token = System.getenv("GITHUB_TOKEN");

    try { // branch main
      cloneOnce(url, dest, token, "refs/heads/main");
      return;
    } catch (Exception ignore) {
      deleteDirectory(dest);
    }

    try { // branch master
      cloneOnce(url, dest, token, "refs/heads/master");
      return;
    } catch (Exception ignore) {
      deleteDirectory(dest);
    }

    /* default branch remoto */
    String headRef = remoteHead(url, token);
    System.out.println("🔍 Default branch: " + headRef);
    cloneOnce(url, dest, token, headRef);
  }

  private static String remoteHead(String url, String token) throws Exception {
    var cmd = Git.lsRemoteRepository().setRemote(url).setHeads(true);
    if (token != null && !token.isBlank()) {
      cmd.setCredentialsProvider(
        new UsernamePasswordCredentialsProvider(token, "")
      );
    }
    return cmd
      .call()
      .stream()
      .filter(r -> r.getName().equals("HEAD"))
      .findFirst()
      .map(r -> r.getTarget().getName())/* refs/heads/xyz */
      .orElseThrow(() -> new IllegalStateException("HEAD remoto non trovato"));
  }

  private static void cloneOnce(
    String url,
    Path dest,
    String token,
    String ref
  ) throws Exception {
    CloneCommand cmd = Git.cloneRepository()
      .setURI(url)
      .setDirectory(dest.toFile())
      .setDepth(1)
      .setCloneAllBranches(false)
      .setBranchesToClone(List.of(ref))
      .setBranch(ref);
    if (token != null && !token.isBlank()) {
      cmd.setCredentialsProvider(
        new UsernamePasswordCredentialsProvider(token, "")
      );
    }
    cmd.call();
  }

  /* ================================================================ */
  /*                         BUILD / MAVEN                            */
  /* ================================================================ */

  private static boolean runMavenVerify(Path repoDir) throws Exception {
    String mvn = System.getProperty("os.name").toLowerCase().contains("win")
      ? "mvn.cmd"
      : "mvn";
    List<String> cmd = List.of(
      mvn,
      "-Dmaven.repo.local=" + LOCAL_REPO, //  ←← AGGIUNTO
      "-B",
      "-DskipTests",
      "verify"
    );

    int exit = runProcess(cmd, repoDir);
    return exit == 0;
  }

  private static int runProcess(List<String> cmd, Path cwd) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(cmd);
    if (cwd != null) pb.directory(cwd.toFile());
    pb.redirectErrorStream(true);
    Process p = pb.start();

    try (
      BufferedReader r = new BufferedReader(
        new InputStreamReader(p.getInputStream())
      )
    ) {
      String line;
      while ((line = r.readLine()) != null) System.out.println(line);
    }

    if (!p.waitFor(30, TimeUnit.MINUTES)) {
      System.err.println("Timeout (30 min) – kill processo");
      p.destroyForcibly();
      return -1;
    }
    return p.exitValue();
  }

  /* ================================================================ */
  /*                             UTILS                                */
  /* ================================================================ */

  private static boolean usesLfs(Path repoDir) {
    Path attr = repoDir.resolve(".gitattributes");
    if (!Files.exists(attr)) return false;
    try {
      return Files.lines(attr).anyMatch(l -> l.contains("filter=lfs"));
    } catch (IOException e) {
      return false;
    }
  }

  private static String repoName(String url) {
    String name = url.substring(url.lastIndexOf('/') + 1);
    return name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
  }

  private static void deleteDirectory(Path path) throws IOException {
    if (!Files.exists(path)) return;
    Files.walk(path)
      .sorted(Comparator.reverseOrder())
      .forEach(p -> {
        try {
          Files.delete(p);
        } catch (IOException ignored) {}
      });
  }

  /* ================================================================ */
  /*                       SECOND PASS – GIT LFS                      */
  /* ================================================================ */

  private static void secondPassWithLfs() throws Exception {
    if (!Files.exists(NEEDS_LFS_FILE)) {
      System.out.println("Nessun needs-lfs.txt: niente da fare.");
      return;
    }
    List<String> urls = Files.readAllLines(
      NEEDS_LFS_FILE,
      StandardCharsets.UTF_8
    )
      .stream()
      .filter(s -> !s.isBlank())
      .collect(Collectors.toList());

    ExecutorService pool = Executors.newFixedThreadPool(
      Math.max(4, Runtime.getRuntime().availableProcessors())
    );
    for (String url : urls) pool.submit(() -> reprocessRepoWithLfs(url));
    pool.shutdown();
    pool.awaitTermination(6, TimeUnit.HOURS);
  }

  private static void reprocessRepoWithLfs(String url) {
    String name = repoName(url);
    Path dest = BASE_DIR.resolve(name + "_lfs");
    try {
      System.out.println("Second pass (Git+LFS) su " + name);
      cloneWithGitCli(url, dest);
      if (runMavenVerify(dest)) {
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

  private static void cloneWithGitCli(String url, Path dest) throws Exception {
    if (Files.exists(dest)) deleteDirectory(dest);
    runProcess(
      List.of(
        "git",
        "clone",
        "--depth",
        "1",
        "--single-branch",
        url,
        dest.toString()
      ),
      null
    );
    runProcess(List.of("git", "lfs", "pull"), dest);
  }
}
