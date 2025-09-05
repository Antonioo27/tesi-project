// file: src/main/java/ghs/analyzer/pipeline/AnalyzerPipeline.java
package ghs.analyzer.pipeline;

import ghs.analyzer.io.ModuleScanner;
import ghs.analyzer.model.AnalysisConfig;
import ghs.analyzer.util.PathUtil;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Set;
import java.util.stream.Collectors;

/** Orchestratore di alto livello: scansiona i moduli e delega l'analisi a ModuleAnalyzer. */
public final class AnalyzerPipeline {

  private final ModuleScanner scanner;
  private final ModuleAnalyzer analyzer;
  private final AnalysisConfig cfg;

  public AnalyzerPipeline(
    ModuleScanner scanner,
    ModuleAnalyzer analyzer,
    AnalysisConfig cfg
  ) {
    this.scanner = scanner;
    this.analyzer = analyzer;
    this.cfg = cfg;
  }

  public void run(Path baseDir) throws Exception {
    Set<String> allow = loadOnlyFrom(cfg.onlyFromFile());
    if (!allow.isEmpty()) System.out.println(
      "Filtro onlyFrom attivo: " + allow.size() + " repo"
    );

    for (Path module : scanner.findMavenModules(baseDir)) {
      String repo = PathUtil.repoName(baseDir, module);
      if (!allow.isEmpty() && !allow.contains(repo)) {
        System.out.println("   (skip repo non in onlyFrom): " + repo);
        continue;
      }
      analyzer.analyzeModule(baseDir, module, cfg);
    }
  }

  // ================= helpers =================

  private static Set<String> loadOnlyFrom(String path) {
    if (path == null || path.isBlank()) return Set.of();
    Path file = Paths.get(path);
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8)
        .stream()
        .map(String::trim)
        .filter(s -> !s.isBlank())
        .filter(s -> !s.startsWith("#"))
        .map(s -> {
          String t = s.replace('\\', '/');
          String last = t.contains("/")
            ? t.substring(t.lastIndexOf('/') + 1)
            : t;
          return last.endsWith(".git")
            ? last.substring(0, last.length() - 4)
            : last;
        })
        .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    } catch (Exception e) {
      System.err.println(
        "Impossibile leggere onlyFrom: " + file + " → " + e.getMessage()
      );
      return Set.of();
    }
  }
}
