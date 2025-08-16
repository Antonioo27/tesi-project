package ghs.scan;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ArtifactScanner {

  /**
   * Lista di artefatti file (jar/war/ear) "nuovi" rispetto a 'since' (con slack 2s),
   * filtrando sources/javadoc/tests.
   */
  public List<Path> listProducedArtifacts(Path repoDir, Instant since) {
    final Instant cutoff = (since == null ? Instant.EPOCH : since.minusSeconds(2));
    try (Stream<Path> paths = Files.walk(repoDir)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(this::isPrimaryArtifact)
          .filter(p -> isAfter(p, cutoff))
          .collect(Collectors.toList());
    } catch (IOException e) {
      return List.of();
    }
  }

  /**
   * Trova directory "target/classes" non vuote (contengono almeno un .class)
   * modificate dopo 'since' (con slack 2s). Restituisce la directory 'target/classes'.
   */
  public List<Path> listClassesDirs(Path repoDir, Instant since) {
    final Instant cutoff = (since == null ? Instant.EPOCH : since.minusSeconds(2));
    try (Stream<Path> paths = Files.walk(repoDir)) {
      return paths
          .filter(Files::isDirectory)
          .filter(p -> p.getFileName().toString().equals("classes"))
          .filter(p -> {
            Path parent = p.getParent(); // target/
            return parent != null && parent.getFileName().toString().equals("target");
          })
          .filter(p -> containsClassFileAfter(p, cutoff))
          .collect(Collectors.toList());
    } catch (IOException e) {
      return List.of();
    }
  }

  private boolean containsClassFileAfter(Path classesDir, Instant cutoff) {
    try (Stream<Path> s = Files.walk(classesDir)) {
      return s.filter(Files::isRegularFile)
          .anyMatch(f -> {
            String name = f.getFileName().toString().toLowerCase();
            if (!name.endsWith(".class")) return false;
            try {
              return Files.getLastModifiedTime(f).toInstant().isAfter(cutoff);
            } catch (IOException e) {
              return false;
            }
          });
    } catch (IOException e) {
      return false;
    }
  }

  private boolean isPrimaryArtifact(Path p) {
    String fn = p.getFileName().toString().toLowerCase();
    boolean type = fn.endsWith(".jar") || fn.endsWith(".war") || fn.endsWith(".ear");
    if (!type) return false;
    return !(fn.endsWith("-sources.jar") || fn.endsWith("-javadoc.jar") || fn.endsWith("-tests.jar"));
  }

  private boolean isAfter(Path p, Instant since) {
    try {
      return Files.getLastModifiedTime(p).toInstant().isAfter(since);
    } catch (IOException e) {
      return false;
    }
  }

  /** Path relativo "sicuro" rispetto alla base dei repo clonati. */
  public static String relToBase(Path baseDir, Path p) {
    try {
      Path baseAbs = baseDir.toAbsolutePath().normalize();
      Path target = p.toAbsolutePath().normalize();
      if (!target.startsWith(baseAbs)) return target.toString();
      return baseAbs.relativize(target).toString();
    } catch (Exception e) {
      return p.toString();
    }
  }
}
