// file: src/main/java/ghs/analyzer/pipeline/ModuleAnalyzer.java
package ghs.analyzer.pipeline;

import ghs.analyzer.discovery.TestDiscovery;
import ghs.analyzer.io.InputResolver;
import ghs.analyzer.io.OutputSink;
import ghs.analyzer.io.ProgressStore;
import ghs.analyzer.model.AnalysisConfig;
import ghs.analyzer.model.ModuleInputs;
import ghs.analyzer.model.TestRecord;
import ghs.analyzer.sootupview.ViewFactory;
import ghs.analyzer.util.PathUtil;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JrtFileSystemAnalysisInputLocation;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;

/**
 * Analizza un singolo modulo Maven orchestrando warm-up, auto-tuning, batching e resume.
 * Dipende da astrazioni (DIP): InputResolver, ViewFactory, TestDiscovery, ProgressStore, OutputSink e AnalyzerStrategy.
 */
public final class ModuleAnalyzer {

  private final InputResolver inputResolver;
  private final ViewFactory viewFactory;
  private final TestDiscovery discovery;
  private final ProgressStore progress;
  private final OutputSink output;
  private final AnalyzerStrategy fast;
  private final AnalyzerStrategy full;

  public ModuleAnalyzer(
    InputResolver inputResolver,
    ViewFactory viewFactory,
    TestDiscovery discovery,
    ProgressStore progress,
    OutputSink output,
    AnalyzerStrategy fast,
    AnalyzerStrategy full
  ) {
    this.inputResolver = inputResolver;
    this.viewFactory = viewFactory;
    this.discovery = discovery;
    this.progress = progress;
    this.output = output;
    this.fast = fast;
    this.full = full;
  }

  /** Punto di ingresso per l'analisi di un modulo specifico. */
  public void analyzeModule(Path baseDir, Path module, AnalysisConfig cfg)
    throws Exception {
    System.out.println("Modulo: " + baseDir.relativize(module));

    // 1) Risolvi input (classi prod/test, JAR opzionali)
    ModuleInputs inputs = inputResolver.resolveInputsForModule(module);
    if (
      !Files.isDirectory(inputs.prodClasses()) ||
      !Files.isDirectory(inputs.testClasses())
    ) {
      System.out.println("   (skip: mancano classi prod/test)");
      return;
    }

    // 2) Warm-up view: scopro i test ed evito JAR per velocità
    JavaView warmupView = createWarmupView(inputs);

    // 3) Indici progetto (prod/test/all)
    Set<String> projectProdClasses = listClassFQNs(inputs.prodClasses());
    Set<String> projectTestClasses = listClassFQNs(inputs.testClasses());
    Set<String> projectAllClasses = new HashSet<>(projectProdClasses);
    projectAllClasses.addAll(projectTestClasses);

    // 4) Discovery test
    List<JavaSootMethod> testMethods = discovery.discover(warmupView);
    if (testMethods.isEmpty()) {
      System.out.println("   Nessun @Test trovato.");
      return;
    }

    // 5) Auto-tuning per dimensione modulo
    Tuning tuning = tune(cfg, testMethods.size());

    // 6) Resume/Progress
    String repoName = PathUtil.repoName(baseDir, module);
    String cfgId = makeCfgId(cfg, tuning);
    if (cfg.resumeReset()) progress.reset(module, cfgId);
    Set<String> already = cfg.resume()
      ? progress.load(module, cfgId)
      : Set.of();
    if (cfg.resume() && !already.isEmpty()) {
      int before = testMethods.size();
      testMethods = testMethods
        .stream()
        .filter(tm -> {
          var s = tm.getSignature();
          String key =
            s.getDeclClassType().getFullyQualifiedName() +
            "#" +
            s.getSubSignature();
          return !already.contains(key);
        })
        .collect(Collectors.toList());
      System.out.printf(
        "   resume: %d già fatti, %d da fare (cfgId=%s)%n",
        (before - testMethods.size()),
        testMethods.size(),
        cfgId
      );
    } else if (cfg.resume()) {
      System.out.printf(
        "   resume: nessun progresso precedente (cfgId=%s)%n",
        cfgId
      );
    }

    // 7) Log configurazione effettiva
    System.out.println("   Test methods: " + testMethods.size());
    System.out.println(
      String.format(
        "   tuning: batchSize=%d, maxDepth=%d, maxVisited=%d%s%s%s",
        tuning.batchSize(),
        cfg.maxDepth(),
        tuning.maxVisited(),
        tuning.useJars() ? ", jars=*" : "",
        tuning.batchesPerView() > 0
          ? (", batchesPerView=" + tuning.batchesPerView())
          : "",
        tuning.fastMode() ? ", FAST" : ""
      )
    );

    if (testMethods.isEmpty()) {
      System.out.println("   Non resta nulla da fare per questo modulo.");
      return;
    }

    AnalyzerStrategy strategy = tuning.fastMode() ? fast : full;
    AnalyzerStrategy.ProjectIndex index = new AnalyzerStrategy.ProjectIndex(
      projectProdClasses,
      projectTestClasses,
      projectAllClasses
    );

    // 8) Batching con gruppi (ricreazione view demandata alla strategy FULL)
    final int total = testMethods.size();
    final int batchSize = tuning.batchSize();
    final int totalBatches = (int) Math.ceil(total / (double) batchSize);
    final int groups = tuning.batchesPerView() <= 0
      ? 1
      : (int) Math.ceil(totalBatches / (double) tuning.batchesPerView());

    for (int g = 0; g < groups; g++) {
      int firstBatch = tuning.batchesPerView() <= 0
        ? 0
        : g * tuning.batchesPerView();
      int lastBatchExcl = tuning.batchesPerView() <= 0
        ? totalBatches
        : Math.min((g + 1) * tuning.batchesPerView(), totalBatches);

      try {
        for (int b = firstBatch; b < lastBatchExcl; b++) {
          int startIdx = b * batchSize;
          int endIdx = Math.min(startIdx + batchSize, total);

          long usedMB =
            (Runtime.getRuntime().totalMemory() -
              Runtime.getRuntime().freeMemory()) /
            (1024 * 1024);
          long maxMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
          System.out.printf(
            "   batch %d/%d [%d..%d)%n",
            (b + 1),
            totalBatches,
            startIdx,
            endIdx
          );
          System.out.printf("   mem %d/%d MiB%n", usedMB, maxMB);

          List<JavaSootMethod> batch = testMethods.subList(startIdx, endIdx);
          List<TestRecord> results = strategy.analyzeBatch(
            repoName,
            module,
            cfgId,
            batch,
            index,
            new AnalysisConfig(
              cfg.baseDir(),
              cfg.outPath(),
              cfg.maxDepth(),
              cfg.pruneLibs(),
              tuning.maxVisited(),
              batchSize,
              cfg.splitByRepo(),
              cfg.append(),
              tuning.useJars(),
              cfg.resume(),
              cfg.resumeReset(),
              cfg.maxJars(),
              cfg.ignoreJarsIfTestsOver(),
              tuning.batchesPerView(),
              cfg.autoTune(),
              cfg.bigThr(),
              cfg.hugeThr(),
              cfg.autoBatchBig(),
              cfg.autoBatchHuge(),
              cfg.autoVisitedBig(),
              cfg.autoVisitedHuge(),
              cfg.autoFastHeuristic(),
              cfg.onlyFromFile()
            )
          );

          for (TestRecord r : results) {
            output.write(r);
            if (cfg.resume()) {
              String key = r.testClass() + "#" + r.testMethod();
              progress.append(module, cfgId, key);
            }
          }
          System.gc();
        }
      } catch (OutOfMemoryError oom) {
        try {
          Path ooms = Paths.get("oom-modules.txt");
          Files.writeString(
            ooms,
            String.format(
              Locale.ROOT,
              "%s %s group=%d cfg=%s%n",
              repoName,
              module,
              g,
              cfgId
            ),
            StandardCharsets.UTF_8,
            Files.exists(ooms)
              ? StandardOpenOption.APPEND
              : StandardOpenOption.CREATE
          );
        } catch (Exception ignored) {}
        throw oom;
      } finally {
        System.gc();
      }
    }

    System.out.println(); // riga vuota estetica
  }

  // ================= helpers =================

  private JavaView createWarmupView(ModuleInputs inputs) {
    List<AnalysisInputLocation> warmupLocs = new ArrayList<>();
    warmupLocs.add(
      new JavaClassPathAnalysisInputLocation(inputs.prodClasses().toString())
    );
    warmupLocs.add(
      new JavaClassPathAnalysisInputLocation(inputs.testClasses().toString())
    );
    warmupLocs.add(new JrtFileSystemAnalysisInputLocation());
    return viewFactory.create(warmupLocs);
  }

  private static Set<String> listClassFQNs(Path classesDir)
    throws java.io.IOException {
    if (!Files.isDirectory(classesDir)) return Set.of();
    try (Stream<Path> s = Files.walk(classesDir)) {
      return s
        .filter(Files::isRegularFile)
        .filter(p -> p.toString().endsWith(".class"))
        .map(p -> PathUtil.toFqn(classesDir, p))
        .collect(Collectors.toSet());
    }
  }

  private static String makeCfgId(AnalysisConfig cfg, Tuning t) {
    return String.format(
      Locale.ROOT,
      "d%d-v%d-p%s-j%s-b%d%s",
      cfg.maxDepth(),
      t.maxVisited(),
      cfg.pruneLibs() ? "1" : "0",
      t.useJars() ? "1" : "0",
      t.batchSize(),
      t.fastMode() ? "-F" : ""
    );
  }

  private static Tuning tune(AnalysisConfig cfg, int nTests) {
    int effBatchSize = cfg.batchSize();
    int effMaxVisited = cfg.maxVisited();
    int effBatchesPerView = cfg.batchesPerView();
    boolean effUseJars = cfg.useJars();
    boolean fastMode = false;

    if (cfg.autoTune()) {
      if (nTests >= cfg.hugeThr()) {
        effBatchSize = Math.max(cfg.batchSize(), cfg.autoBatchHuge());
        effMaxVisited = Math.min(cfg.maxVisited(), cfg.autoVisitedHuge());
        effBatchesPerView = 0;
        effUseJars = false;
        fastMode = cfg.autoFastHeuristic();
        System.out.printf(
          "   autoTune: HUGE module (%d tests) → batch=%d, maxVisited=%d, fast=%s%n",
          nTests,
          effBatchSize,
          effMaxVisited,
          String.valueOf(fastMode)
        );
      } else if (nTests >= cfg.bigThr()) {
        effBatchSize = Math.max(cfg.batchSize(), cfg.autoBatchBig());
        effMaxVisited = Math.min(cfg.maxVisited(), cfg.autoVisitedBig());
        effBatchesPerView = 0;
        effUseJars = false;
        System.out.printf(
          "   autoTune: BIG module (%d tests) → batch=%d, maxVisited=%d%n",
          nTests,
          effBatchSize,
          effMaxVisited
        );
      }
    }

    return new Tuning(
      effBatchSize,
      effMaxVisited,
      effBatchesPerView,
      effUseJars,
      fastMode
    );
  }

  private record Tuning(
    int batchSize,
    int maxVisited,
    int batchesPerView,
    boolean useJars,
    boolean fastMode
  ) {}
}
