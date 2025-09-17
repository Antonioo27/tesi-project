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

    // 5) Preflight (mini-CHA + controllo headroom)
    if (cfg.preflightN() > 0) {
      boolean ok = preflightOk(
        warmupView,
        testMethods,
        Math.min(cfg.preflightN(), testMethods.size()),
        cfg.preflightMinHeadroomMb()
      );
      if (!ok) {
        String repoName = PathUtil.repoName(baseDir, module);
        System.out.println(
          "   skip: preflight fallito (headroom insufficiente o errore)"
        );
        recordSkip(
          repoName,
          module,
          "preflight-failed",
          testMethods.size(),
          projectAllClasses.size()
        );
        return;
      }
    }

    // 6) Auto-tuning per dimensione modulo
    Tuning tuning = tune(cfg, testMethods.size());

    // 6.1 useJars "effettivo": rispetta anche ignoreJarsIfTestsOver
    boolean effUseJars = tuning.useJars();
    if (
      cfg.ignoreJarsIfTestsOver() >= 0 &&
      testMethods.size() > cfg.ignoreJarsIfTestsOver()
    ) {
      effUseJars = false;
    }

    // 7) Resume/Progress (cfgId coerente con useJars effettivo)
    String repoName = PathUtil.repoName(baseDir, module);
    String cfgId = String.format(
      Locale.ROOT,
      "d%d-v%d-p%s-j%s-b%d%s",
      cfg.maxDepth(),
      tuning.maxVisited(),
      cfg.pruneLibs() ? "1" : "0",
      effUseJars ? "1" : "0",
      tuning.batchSize(),
      tuning.fastMode() ? "-F" : ""
    );

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

    // 8) Log configurazione effettiva
    System.out.println("   Test methods: " + testMethods.size());
    System.out.println(
      String.format(
        "   tuning: batchSize=%d, maxDepth=%d, maxVisited=%d%s%s%s",
        tuning.batchSize(),
        cfg.maxDepth(),
        tuning.maxVisited(),
        effUseJars ? ", jars=*" : "",
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

    // 9) Batching con gruppi (ricreazione view demandata alla strategy FULL)
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

          // AnalysisConfig derivata con i parametri EFFETTIVI (incluso effUseJars)
          AnalysisConfig effectiveCfg = new AnalysisConfig(
            cfg.baseDir(),
            cfg.outPath(),
            cfg.maxDepth(),
            cfg.pruneLibs(),
            tuning.maxVisited(),
            batchSize,
            cfg.splitByRepo(),
            cfg.append(),
            effUseJars, // 👈 usa l'uso JAR effettivo
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
            cfg.onlyFromFile(),
            cfg.preflightN(),
            cfg.preflightMinHeadroomMb(),
            cfg.skipOnOom(),
            cfg.integrationMinProjectClasses(),
            cfg.integrationMinProjectMethods(),
            cfg.highConcentrationThreshold()
          );

          List<TestRecord> results = strategy.analyzeBatch(
            repoName,
            module,
            cfgId,
            batch,
            index,
            effectiveCfg
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

        // Log & skip modulo se richiesto
        recordSkip(
          repoName,
          module,
          "oom",
          testMethods.size(),
          projectAllClasses.size()
        );
        if (cfg.skipOnOom()) {
          System.out.println(
            "   OOM → skip modulo e continuo con il prossimo."
          );
          System.gc();
          return; // salta questo modulo
        } else {
          throw oom; // comportamento precedente
        }
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

  private static boolean preflightOk(
    sootup.java.core.views.JavaView view,
    java.util.List<sootup.java.core.JavaSootMethod> tests,
    int n,
    int minHeadroomMb
  ) {
    long headroomBefore = headroomMb();
    try {
      sootup.callgraph.ClassHierarchyAnalysisAlgorithm cha =
        new sootup.callgraph.ClassHierarchyAnalysisAlgorithm(view);
      java.util.List<sootup.core.signatures.MethodSignature> entries =
        new java.util.ArrayList<>(Math.min(n, tests.size()));
      for (int i = 0; i < Math.min(n, tests.size()); i++) entries.add(
        tests.get(i).getSignature()
      );
      sootup.callgraph.CallGraph cg = cha.initialize(entries);
      if (!entries.isEmpty()) cg
        .callsFrom(entries.get(0))
        .stream()
        .limit(3)
        .count();
    } catch (Throwable t) {
      return false;
    }
    long headroomNow = Math.min(headroomBefore, headroomMb());
    return minHeadroomMb <= 0 || headroomNow >= minHeadroomMb;
  }

  private static long headroomMb() {
    Runtime rt = Runtime.getRuntime();
    long used = rt.totalMemory() - rt.freeMemory();
    long max = rt.maxMemory();
    return (max - used) / (1024L * 1024L);
  }

  private static void recordSkip(
    String repo,
    java.nio.file.Path module,
    String reason,
    int tests,
    int classes
  ) {
    try {
      java.nio.file.Path f = java.nio.file.Paths.get("skipped-modules.txt");
      String line = String.format(
        java.util.Locale.ROOT,
        "%s\t%s\treason=%s\ttests=%d\tclasses=%d%n",
        repo,
        module.toString(),
        reason,
        tests,
        classes
      );
      java.nio.file.Files.writeString(
        f,
        line,
        java.nio.charset.StandardCharsets.UTF_8,
        java.nio.file.Files.exists(f)
          ? java.nio.file.StandardOpenOption.APPEND
          : java.nio.file.StandardOpenOption.CREATE
      );
    } catch (Exception ignored) {}
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
