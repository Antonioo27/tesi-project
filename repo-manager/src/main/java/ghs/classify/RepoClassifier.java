package ghs.classify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public final class RepoClassifier {

  public boolean requiresJdk8Tools(Path repoDir) {
    try (
      Stream<Path> poms = Files.walk(repoDir).filter(p ->
        p.getFileName().toString().equals("pom.xml")
      )
    ) {
      final Pattern gaTools = Pattern.compile(
        "<groupId>\\s*com\\.sun\\s*</groupId>\\s*<artifactId>\\s*tools\\s*</artifactId>|" +
        "<artifactId>\\s*tools\\s*</artifactId>\\s*<groupId>\\s*com\\.sun\\s*</groupId>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
      );

      for (Path pom : (Iterable<Path>) poms::iterator) {
        String text = Files.readString(pom);
        String lt = text.toLowerCase(Locale.ROOT);

        // indizi semplici
        if (lt.contains("com.sun:tools")) return true;
        if (lt.contains("tools.jar")) return true;

        // groupId/artifactId su righe diverse
        if (gaTools.matcher(text).find()) return true;

        // systemPath che punta a tools.jar
        if (
          lt.contains("<systempath>") && lt.contains("tools.jar")
        ) return true;
      }
    } catch (IOException ignored) {}
    return false;
  }

  public enum WarmupRepoIssue {
    NONE,
    HTTP_401,
    HTTP_403,
    HTTP_409,
    SNAPSHOT_MISSING, // <-- nuovo caso
  }

  public WarmupRepoIssue classifyWarmupFailure(String output) {
    if (output == null) return WarmupRepoIssue.NONE;
    String s = output.toLowerCase(Locale.ROOT);

    // 401 / 403 / 409 come prima
    if (
      s.contains("status code: 401") ||
      s.contains("return code is: 401") ||
      s.contains(" 401 ")
    ) {
      return WarmupRepoIssue.HTTP_401;
    }
    if (
      s.contains("status code: 403") ||
      s.contains("return code is: 403") ||
      s.contains(" 403 ")
    ) {
      return WarmupRepoIssue.HTTP_403;
    }
    if (
      s.contains("status code: 409") ||
      s.contains("return code is: 409") ||
      s.contains(" 409 ")
    ) {
      return WarmupRepoIssue.HTTP_409;
    }

    // ── SNAPSHOT non pubblicato / mancante ──────────────────────────────────────
    // Heuristiche: presenza di "-snapshot" insieme a messaggi tipici di "not found"
    if (
      (s.contains("-snapshot") || s.contains(" snapshot")) &&
      (s.contains("could not find artifact") ||
        s.contains("missing artifact") ||
        s.contains("was not found in") ||
        s.contains("failed to read artifact descriptor") ||
        s.contains("status code: 404") ||
        s.contains("return code is: 404") ||
        s.contains("is not reattempted until the update interval"))
    ) {
      return WarmupRepoIssue.SNAPSHOT_MISSING;
    }

    return WarmupRepoIssue.NONE;
  }

  public int cleanLastUpdated(Path localRepo) {
    if (localRepo == null || !Files.exists(localRepo)) return 0;
    AtomicInteger n = new AtomicInteger();
    try (Stream<Path> s = Files.walk(localRepo)) {
      s
        .filter(p -> p.getFileName().toString().endsWith(".lastUpdated"))
        .forEach(p -> {
          try {
            Files.deleteIfExists(p);
            n.incrementAndGet();
          } catch (IOException ignored) {}
        });
    } catch (IOException ignored) {}
    return n.get();
  }

  public static synchronized void appendLine(Path file, String line) {
    try {
      Files.write(
        file,
        java.util.List.of(line),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      );
    } catch (IOException e) {
      System.err.println(
        "Impossibile scrivere " + file + ": " + e.getMessage()
      );
    }
  }
}
