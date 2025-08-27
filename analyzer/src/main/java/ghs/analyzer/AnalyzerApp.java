package ghs.analyzer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.json.JSONObject;
import sootup.callgraph.CallGraph;
import sootup.callgraph.ClassHierarchyAnalysisAlgorithm;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.signatures.MethodSignature;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JrtFileSystemAnalysisInputLocation;
import sootup.java.core.AnnotationUsage;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;

public final class AnalyzerApp {

  public static void main(String[] args) throws Exception {
    Map<String, String> cli = parseArgs(args);

    Path baseDir = Paths.get(cli.getOrDefault("base", "cloned_repos"));
    Path outArg = Paths.get(cli.getOrDefault("out", "analysis.jsonl"));

    int maxDepth = getInt(cli, "maxDepth", 3);
    boolean prune = getBool(cli, "pruneLibs", true);
    int maxVisited = getInt(cli, "maxVisited", 25_000);
    boolean append = getBool(cli, "append", false);
    boolean split = getBool(cli, "splitByRepo", false);
    boolean useJars = getBool(cli, "useJars", false);
    int batchSize = getInt(cli, "batchSize", 50);

    // Resume
    boolean resume = cli.containsKey("resume")
      ? getBool(cli, "resume", true)
      : getBoolOpt("resume", "RESUME", true);
    boolean resumeReset = cli.containsKey("resumeReset")
      ? getBool(cli, "resumeReset", false)
      : getBoolOpt("resumeReset", "RESUME_RESET", false);

    // Cap/filtri jar & batching per view
    int maxJars = getInt(cli, "maxJars", -1); // -1 = nessun cap
    int ignoreJarsIfTestsOver = getInt(cli, "ignoreJarsIfTestsOver", -1); // -1 = disattivo
    int batchesPerView = getInt(cli, "batchesPerView", 0); // 0 = riusa sempre la view

    // Auto-tuning
    boolean autoTune = cli.containsKey("autoTune")
      ? getBool(cli, "autoTune", true)
      : getBoolOpt("autoTune", "AUTO_TUNE", true);
    int bigThr = cli.containsKey("bigThreshold")
      ? getInt(cli, "bigThreshold", 1000)
      : getIntOpt("bigThreshold", "BIG_THRESHOLD", 1000);
    int hugeThr = cli.containsKey("hugeThreshold")
      ? getInt(cli, "hugeThreshold", 5000)
      : getIntOpt("hugeThreshold", "HUGE_THRESHOLD", 5000);
    int autoBatchBig = cli.containsKey("autoBatchBig")
      ? getInt(cli, "autoBatchBig", 200)
      : getIntOpt("autoBatchBig", "AUTO_BATCH_BIG", 200);
    int autoBatchHuge = cli.containsKey("autoBatchHuge")
      ? getInt(cli, "autoBatchHuge", 300)
      : getIntOpt("autoBatchHuge", "AUTO_BATCH_HUGE", 300);
    int autoVisitedBig = cli.containsKey("autoVisitedBig")
      ? getInt(cli, "autoVisitedBig", 3000)
      : getIntOpt("autoVisitedBig", "AUTO_VISITED_BIG", 3000);
    int autoVisitedHuge = cli.containsKey("autoVisitedHuge")
      ? getInt(cli, "autoVisitedHuge", 2500)
      : getIntOpt("autoVisitedHuge", "AUTO_VISITED_HUGE", 2500);
    boolean autoFastHeuristic = cli.containsKey("autoFastHeuristic")
      ? getBool(cli, "autoFastHeuristic", true)
      : getBoolOpt("autoFastHeuristic", "AUTO_FAST_HEURISTIC", true);

    // --- filtro opzionale: considera solo repo elencate in un file (uno per riga) ---
    String onlyFromPath = cli.get("onlyFrom");
    final Set<String> allowRepos = (onlyFromPath == null ||
        onlyFromPath.isBlank())
      ? Set.of()
      : loadRepoFilter(Paths.get(onlyFromPath));
    if (!allowRepos.isEmpty()) {
      System.out.println(
        "Filtro onlyFrom attivo: " + allowRepos.size() + " repo"
      );
    }

    if (!split) {
      Path outDir = outArg.toAbsolutePath().getParent();
      if (outDir != null) Files.createDirectories(outDir);

      OpenOption[] outOpts = append
        ? new OpenOption[] {
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
          StandardOpenOption.APPEND,
        }
        : new OpenOption[] {
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE,
        };

      try (
        BufferedWriter out = Files.newBufferedWriter(
          outArg,
          StandardCharsets.UTF_8,
          outOpts
        )
      ) {
        for (Path module : findMavenModules(baseDir)) {
          String repo = repoName(baseDir, module);
          if (!allowRepos.isEmpty() && !allowRepos.contains(repo)) {
            System.out.println("   (skip repo non in onlyFrom): " + repo);
            continue;
          }
          analyzeModule(
            baseDir,
            module,
            out,
            maxDepth,
            prune,
            maxVisited,
            batchSize,
            useJars,
            maxJars,
            ignoreJarsIfTestsOver,
            batchesPerView,
            resume,
            resumeReset,
            autoTune,
            bigThr,
            hugeThr,
            autoBatchBig,
            autoBatchHuge,
            autoVisitedBig,
            autoVisitedHuge,
            autoFastHeuristic
          );
        }
      }
    } else {
      // split per repo: "out" è una cartella con <repo>.jsonl
      Files.createDirectories(outArg);
      Set<String> seenRepos = new HashSet<>();

      for (Path module : findMavenModules(baseDir)) {
        String repo = repoName(baseDir, module);
        if (!allowRepos.isEmpty() && !allowRepos.contains(repo)) {
          System.out.println("   (skip repo non in onlyFrom): " + repo);
          continue;
        }

        Path repoOut = outArg.resolve(repo + ".jsonl");
        boolean doAppend = append || seenRepos.contains(repo);
        OpenOption[] outOpts = doAppend
          ? new OpenOption[] {
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
          }
          : new OpenOption[] {
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
          };

        try (
          BufferedWriter out = Files.newBufferedWriter(
            repoOut,
            StandardCharsets.UTF_8,
            outOpts
          )
        ) {
          analyzeModule(
            baseDir,
            module,
            out,
            maxDepth,
            prune,
            maxVisited,
            batchSize,
            useJars,
            maxJars,
            ignoreJarsIfTestsOver,
            batchesPerView,
            resume,
            resumeReset,
            autoTune,
            bigThr,
            hugeThr,
            autoBatchBig,
            autoBatchHuge,
            autoVisitedBig,
            autoVisitedHuge,
            autoFastHeuristic
          );
        }
        seenRepos.add(repo);
      }
    }

    System.out.println("\n✅ Analisi completata.");
  }

  // ---------------- core ----------------

  private static void analyzeModule(
    Path baseDir,
    Path module,
    BufferedWriter out,
    int maxDepth,
    boolean pruneLibs,
    int maxVisited,
    int batchSize,
    boolean useJarsParam,
    int maxJarsCapParam,
    int ignoreJarsIfTestsOverParam,
    int batchesPerViewParam,
    boolean resume,
    boolean resumeReset,
    boolean autoTune,
    int bigThr,
    int hugeThr,
    int autoBatchBig,
    int autoBatchHuge,
    int autoVisitedBig,
    int autoVisitedHuge,
    boolean autoFastHeuristic
  ) throws Exception {
    System.out.println("Modulo: " + baseDir.relativize(module));

    // --- input (classi prod/test + eventuali JAR trovati) ---
    ModuleInputs inputs = resolveInputsForModule(module);
    if (
      !Files.isDirectory(inputs.prodClasses) ||
      !Files.isDirectory(inputs.testClasses)
    ) {
      System.out.println("   (skip: mancano classi prod/test)");
      return;
    }

    // Warmup view (senza JAR per velocità) per scoprire i test
    List<AnalysisInputLocation> warmupLocs = new ArrayList<>();
    warmupLocs.add(
      new JavaClassPathAnalysisInputLocation(inputs.prodClasses.toString())
    );
    warmupLocs.add(
      new JavaClassPathAnalysisInputLocation(inputs.testClasses.toString())
    );
    warmupLocs.add(new JrtFileSystemAnalysisInputLocation());
    JavaView warmupView = new JavaView(warmupLocs);

    // classi del progetto (per distinguere da librerie)
    Set<String> projectProdClasses = listClassFQNs(inputs.prodClasses);
    Set<String> projectTestClasses = listClassFQNs(inputs.testClasses);
    Set<String> projectAllClasses = new HashSet<>(projectProdClasses);
    projectAllClasses.addAll(projectTestClasses);

    // metodi @Test
    List<JavaSootMethod> testMethods = warmupView
      .getClasses()
      .flatMap(c -> c.getMethods().stream())
      .filter(AnalyzerApp::isJUnitOrTestNGTest)
      .collect(Collectors.toList());

    if (testMethods.isEmpty()) {
      System.out.println("   Nessun @Test trovato.");
      return;
    }

    // --- AUTO-TUNING per moduli grandi ---
    int effBatchSize = batchSize;
    int effMaxVisited = maxVisited;
    int effBatchesPerView = batchesPerViewParam;
    boolean effUseJars = useJarsParam;
    boolean fastMode = false;

    if (autoTune) {
      int nTests = testMethods.size();
      if (nTests >= hugeThr) {
        effBatchSize = Math.max(batchSize, autoBatchHuge);
        effMaxVisited = Math.min(maxVisited, autoVisitedHuge);
        effBatchesPerView = 0;
        effUseJars = false;
        fastMode = autoFastHeuristic;
        System.out.printf(
          "   autoTune: HUGE module (%d tests) → batch=%d, maxVisited=%d, fast=%s%n",
          nTests,
          effBatchSize,
          effMaxVisited,
          String.valueOf(fastMode)
        );
      } else if (nTests >= bigThr) {
        effBatchSize = Math.max(batchSize, autoBatchBig);
        effMaxVisited = Math.min(maxVisited, autoVisitedBig);
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

    // Selezione effettiva JAR (se abilitati)
    List<Path> effectiveJars;
    if (!effUseJars) {
      effectiveJars = List.of();
      System.out.println("   (noJars) dipendenze (jar) ignorate");
    } else {
      List<Path> jars = inputs.dependencyJars;
      if (
        ignoreJarsIfTestsOverParam >= 0 &&
        testMethods.size() > ignoreJarsIfTestsOverParam
      ) {
        System.out.println(
          "   (useJars) ignorati perché tests > " + ignoreJarsIfTestsOverParam
        );
        effectiveJars = List.of();
      } else if (maxJarsCapParam >= 0 && jars.size() > maxJarsCapParam) {
        effectiveJars = jars.subList(0, maxJarsCapParam);
        System.out.println(
          "   (useJars) cap JAR: " + effectiveJars.size() + "/" + jars.size()
        );
      } else {
        effectiveJars = jars;
        if (!effectiveJars.isEmpty()) {
          System.out.println(
            "   (useJars) dipendenze (jar): " + effectiveJars.size()
          );
        }
      }
    }

    String repoName = repoName(baseDir, module);

    // ====== CONFIG-ID per il resume (con parametri EFFETTIVI) ======
    String cfgId = String.format(
      Locale.ROOT,
      "d%d-v%d-p%s-j%s-b%d%s",
      maxDepth,
      effMaxVisited,
      pruneLibs ? "1" : "0",
      effectiveJars.isEmpty() ? "0" : "1",
      effBatchSize,
      fastMode ? "-F" : ""
    );
    Path progressFile = module
      .resolve("target")
      .resolve("analyzer-progress-" + cfgId + ".txt");
    if (resumeReset) {
      try {
        Files.deleteIfExists(progressFile);
      } catch (Exception ignored) {}
    }
    Set<String> alreadyDone = resume ? loadProgress(progressFile) : Set.of();

    // Filtro dei test già completati (se resume attivo)
    if (resume && !alreadyDone.isEmpty()) {
      int before = testMethods.size();
      testMethods = testMethods
        .stream()
        .filter(tm -> {
          MethodSignature s = tm.getSignature();
          String key =
            s.getDeclClassType().getFullyQualifiedName() +
            "#" +
            s.getSubSignature();
          return !alreadyDone.contains(key);
        })
        .collect(Collectors.toList());
      System.out.printf(
        "   resume: %d già fatti, %d da fare (cfgId=%s)%n",
        (before - testMethods.size()),
        testMethods.size(),
        cfgId
      );
    } else if (resume) {
      System.out.printf(
        "   resume: nessun progresso precedente (cfgId=%s)%n",
        cfgId
      );
    }

    // tuning stampato
    System.out.println(
      String.format("   Test methods: %d", testMethods.size())
    );
    System.out.println(
      String.format(
        "   tuning: batchSize=%d, maxDepth=%d, maxVisited=%d%s%s%s",
        effBatchSize,
        maxDepth,
        effMaxVisited,
        effectiveJars.isEmpty() ? "" : (", jars=" + effectiveJars.size()),
        effBatchesPerView > 0 ? (", batchesPerView=" + effBatchesPerView) : "",
        fastMode ? ", FAST" : ""
      )
    );

    if (testMethods.isEmpty()) {
      System.out.println("   Non resta nulla da fare per questo modulo.");
      return;
    }

    // === FAST MODE: via rapida senza Call Graph ===
    if (fastMode) {
      System.out.println("   FAST MODE: euristica su nome (no call graph)");
      // prepara progress file
      if (resume) {
        try {
          Files.createDirectories(progressFile.getParent());
          if (!Files.exists(progressFile)) Files.createFile(progressFile);
        } catch (Exception e) {
          System.out.println(
            "   (warn) impossibile preparare progress file: " + e.getMessage()
          );
        }
      }

      for (JavaSootMethod tm : testMethods) {
        MethodSignature s = tm.getSignature();
        String testClass = s.getDeclClassType().getFullyQualifiedName();
        String testMethod = s.getSubSignature().toString();
        String focalClass = guessFocalClassFromTestName(testClass);

        JSONObject row = new JSONObject();
        row.put("repo", repoName);
        row.put("module", module.toString());
        row.put("cfgId", cfgId);
        row.put("testClass", testClass);
        row.put("testMethod", testMethod);
        row.put("focalClass", focalClass);
        row.put("focalMethod", "");

        JSONObject cgStats = new JSONObject();
        cgStats.put("projectCalls", 0);
        cgStats.put("callsToFocalClass", 0);
        cgStats.put("callsToOtherProjectClasses", 0);
        cgStats.put("callsToLibraries", 0);
        cgStats.put("uniqueProjectClasses", 0);
        cgStats.put("maxDepthVisited", 0);
        row.put("cgStats", cgStats);

        row.put("usesMocks", false);
        row.put("unit_integration_score", 0.0);

        out.write(row.toString());
        out.write("\n");

        // resume progress
        if (resume) {
          String key = testClass + "#" + testMethod;
          appendLine(progressFile, key);
        }
      }
      out.flush();
      System.out.println("   FAST MODE: completato.");
      System.out.println();
      return;
    }

    // batching
    final int totalTests = testMethods.size();
    final int totalBatches = (int) Math.ceil(
      totalTests / (double) effBatchSize
    );
    final int groups = (effBatchesPerView <= 0)
      ? 1
      : (int) Math.ceil(totalBatches / (double) effBatchesPerView);

    // Apri il writer del progress in append (lo useremo dopo ogni test)
    if (resume) {
      try {
        Files.createDirectories(progressFile.getParent());
        if (!Files.exists(progressFile)) Files.createFile(progressFile);
      } catch (Exception e) {
        System.out.println(
          "   (warn) impossibile preparare progress file: " + e.getMessage()
        );
      }
    }

    // ciclo per gruppi: ricreiamo la view per limitare uso heap (o 1 sola se batchesPerView=0)
    for (int g = 0; g < groups; g++) {
      int firstBatch = (effBatchesPerView <= 0) ? 0 : g * effBatchesPerView;
      int lastBatchExcl = (effBatchesPerView <= 0)
        ? totalBatches
        : Math.min((g + 1) * effBatchesPerView, totalBatches);

      // crea view per questo gruppo
      List<AnalysisInputLocation> locs = new ArrayList<>();
      locs.add(
        new JavaClassPathAnalysisInputLocation(inputs.prodClasses.toString())
      );
      locs.add(
        new JavaClassPathAnalysisInputLocation(inputs.testClasses.toString())
      );
      for (Path jar : effectiveJars) {
        locs.add(new JavaClassPathAnalysisInputLocation(jar.toString()));
      }
      locs.add(new JrtFileSystemAnalysisInputLocation());
      JavaView view = new JavaView(locs);

      try {
        for (int b = firstBatch; b < lastBatchExcl; b++) {
          int startIdx = b * effBatchSize;
          int endIdx = Math.min(startIdx + effBatchSize, totalTests);

          // log batch + memoria
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

          // costruisci il call graph SOLO per il batch corrente
          ClassHierarchyAnalysisAlgorithm cha =
            new ClassHierarchyAnalysisAlgorithm(view);
          List<MethodSignature> entries = new ArrayList<>(batch.size());
          for (JavaSootMethod tm : batch) entries.add(tm.getSignature());
          CallGraph cg = cha.initialize(entries);

          // analizza ciascun test del batch
          for (JavaSootMethod tm : batch) {
            MethodSignature tSig = tm.getSignature();
            String testClass = tSig.getDeclClassType().getFullyQualifiedName();
            String testMethod = tSig.getSubSignature().toString();

            String candidateFocalClass = guessFocalClassFromTestName(testClass);

            Map<MethodSignature, Integer> distance = bfsFrom(
              cg,
              tSig,
              maxDepth,
              projectAllClasses,
              pruneLibs,
              effMaxVisited
            );

            List<MethodSignature> projectTargets = distance
              .keySet()
              .stream()
              .filter(ms ->
                projectProdClasses.contains(
                  ms.getDeclClassType().getFullyQualifiedName()
                )
              )
              .collect(Collectors.toList());

            Optional<MethodSignature> byName = projectTargets
              .stream()
              .filter(ms ->
                ms
                  .getDeclClassType()
                  .getFullyQualifiedName()
                  .endsWith("." + simpleName(candidateFocalClass))
              )
              .min(Comparator.comparingInt(distance::get));

            Optional<String> byMinDistanceClass = projectTargets
              .stream()
              .collect(
                Collectors.groupingBy(
                  ms -> ms.getDeclClassType().getFullyQualifiedName(),
                  Collectors.mapping(
                    distance::get,
                    Collectors.minBy(Integer::compareTo)
                  )
                )
              )
              .entrySet()
              .stream()
              .sorted(Comparator.comparingInt(e -> e.getValue().orElse(999)))
              .map(Map.Entry::getKey)
              .findFirst();

            String focalClassFqn = byName
              .map(ms -> ms.getDeclClassType().getFullyQualifiedName())
              .orElse(byMinDistanceClass.orElse(candidateFocalClass));

            List<MethodSignature> focalClassMethods = projectTargets
              .stream()
              .filter(ms ->
                ms
                  .getDeclClassType()
                  .getFullyQualifiedName()
                  .equals(focalClassFqn)
              )
              .sorted(Comparator.comparingInt(distance::get))
              .collect(Collectors.toList());

            Optional<MethodSignature> focalNonTrivial = focalClassMethods
              .stream()
              .filter(ms -> !isTrivialMethod(ms.getSubSignature().toString()))
              .findFirst();
            Optional<MethodSignature> focalMethodSig =
              focalNonTrivial.isPresent()
                ? focalNonTrivial
                : focalClassMethods.stream().findFirst();

            Set<String> uniqueProjectClasses = projectTargets
              .stream()
              .map(ms -> ms.getDeclClassType().getFullyQualifiedName())
              .collect(Collectors.toSet());

            long callsToFocal = focalClassMethods.size();
            long callsToOtherProjectClasses =
              projectTargets.size() - callsToFocal;

            long callsToLibraries = distance
              .keySet()
              .stream()
              .filter(
                ms ->
                  !projectProdClasses.contains(
                    ms.getDeclClassType().getFullyQualifiedName()
                  ) &&
                  !projectTestClasses.contains(
                    ms.getDeclClassType().getFullyQualifiedName()
                  )
              )
              .count();

            boolean usesMocks = detectMocks(cg, tSig);

            double denominator = projectTargets.isEmpty()
              ? 1.0
              : projectTargets.size();
            double raw = callsToOtherProjectClasses / denominator;
            double score = clamp(raw - (usesMocks ? 0.2 : 0.0), 0.0, 1.0);

            JSONObject row = new JSONObject();
            row.put("repo", repoName);
            row.put("module", module.toString());
            row.put("cfgId", cfgId);
            row.put("testClass", testClass);
            row.put("testMethod", testMethod);
            row.put("focalClass", focalClassFqn);
            row.put(
              "focalMethod",
              focalMethodSig.map(MethodSignature::toString).orElse("")
            );

            JSONObject cgStats = new JSONObject();
            cgStats.put("projectCalls", projectTargets.size());
            cgStats.put("callsToFocalClass", callsToFocal);
            cgStats.put(
              "callsToOtherProjectClasses",
              callsToOtherProjectClasses
            );
            cgStats.put("callsToLibraries", callsToLibraries);
            cgStats.put("uniqueProjectClasses", uniqueProjectClasses.size());
            cgStats.put(
              "maxDepthVisited",
              distance.values().stream().mapToInt(i -> i).max().orElse(0)
            );
            row.put("cgStats", cgStats);

            row.put("usesMocks", usesMocks);
            row.put("unit_integration_score", score);

            out.write(row.toString());
            out.write("\n");

            // === aggiorna progress ===
            if (resume) {
              String key = testClass + "#" + testMethod;
              appendLine(progressFile, key);
            }
          }

          // cleanup batch
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
              module.toString(),
              g,
              cfgId
            ),
            StandardCharsets.UTF_8,
            Files.exists(ooms)
              ? StandardOpenOption.APPEND
              : StandardOpenOption.CREATE
          );
        } catch (Exception ignored) {}
        throw oom; // ripropaga
      } finally {
        System.gc();
      }
    }

    System.out.println(); // riga vuota estetica
  }

  // ---------------- helpers: opzioni ----------------

  // CLI: --key value | --flag
  private static Map<String, String> parseArgs(String[] args) {
    Map<String, String> m = new HashMap<>();
    for (int idx = 0; idx < args.length; idx++) {
      String a = args[idx];
      if (!a.startsWith("--")) continue;
      String key = a.substring(2);
      String val = "true"; // flag senza valore -> true
      if (idx + 1 < args.length && !args[idx + 1].startsWith("--")) {
        val = args[++idx];
      }
      m.put(key, val);
    }
    return m;
  }

  private static boolean getBool(Map<String, String> m, String k, boolean def) {
    String v = m.get(k);
    if (v == null) return def;
    v = v.trim();
    return v.equalsIgnoreCase("true") || v.equals("1") || v.isEmpty();
  }

  private static int getInt(Map<String, String> m, String k, int def) {
    String v = m.get(k);
    if (v == null) return def;
    try {
      return Integer.parseInt(v.trim());
    } catch (NumberFormatException e) {
      return def;
    }
  }

  // helper per opzioni: prova System property, poi env, poi default
  private static boolean getBoolOpt(String sysKey, String envKey, boolean def) {
    String v = System.getProperty(sysKey);
    if (v == null) v = System.getenv(envKey);
    if (v == null) return def;
    v = v.trim().toLowerCase(Locale.ROOT);
    return (
      v.isEmpty() ||
      v.equals("1") ||
      v.equals("true") ||
      v.equals("yes") ||
      v.equals("y")
    );
  }

  private static int getIntOpt(String sysKey, String envKey, int def) {
    String v = System.getProperty(sysKey);
    if (v == null) v = System.getenv(envKey);
    if (v == null) return def;
    try {
      return Integer.parseInt(v.trim());
    } catch (NumberFormatException e) {
      return def;
    }
  }

  // ---------------- helpers: FS/Repo ----------------

  private record ModuleInputs(
    Path prodClasses,
    Path testClasses,
    List<Path> dependencyJars
  ) {}

  private static List<Path> findMavenModules(Path base) throws IOException {
    final Set<String> SKIP_DIRS = Set.of(
      "node_modules",
      ".git",
      ".hg",
      ".svn",
      "build",
      "dist",
      "out"
    );
    List<Path> modules = new ArrayList<>();

    Files.walkFileTree(
      base,
      new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult preVisitDirectory(
          Path dir,
          BasicFileAttributes attrs
        ) {
          String name = dir.getFileName() == null
            ? ""
            : dir.getFileName().toString();
          if (SKIP_DIRS.contains(name)) return FileVisitResult.SKIP_SUBTREE;
          if (attrs.isSymbolicLink()) return FileVisitResult.SKIP_SUBTREE;
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          if ("pom.xml".equals(file.getFileName().toString())) {
            Path module = file.getParent();
            boolean hasProd = Files.isDirectory(
              module.resolve("target").resolve("classes")
            );
            boolean hasTest = Files.isDirectory(
              module.resolve("target").resolve("test-classes")
            );
            if (hasProd && hasTest) modules.add(module);
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
          System.out.println(
            "   (skip path problem) " +
            file +
            " -> " +
            exc.getClass().getSimpleName()
          );
          return FileVisitResult.CONTINUE;
        }
      }
    );

    return modules;
  }

  private static ModuleInputs resolveInputsForModule(Path module)
    throws IOException {
    Path prod = module.resolve("target").resolve("classes");
    Path test = module.resolve("target").resolve("test-classes");

    Set<Path> jarSet = new LinkedHashSet<>();

    Path cp = module.resolve("target").resolve("classpath.txt");
    if (Files.isRegularFile(cp)) {
      int before = jarSet.size();
      for (String line : Files.readAllLines(cp, StandardCharsets.UTF_8)) {
        String[] entries = line.split(
          java.util.regex.Pattern.quote(File.pathSeparator)
        );
        for (String raw : entries) {
          String entry = raw.trim().replace("\"", "");
          if (entry.isEmpty() || !entry.endsWith(".jar")) continue;
          Path p = Paths.get(entry);
          if (!p.isAbsolute()) p = module
            .resolve(p)
            .toAbsolutePath()
            .normalize();
          if (Files.isRegularFile(p)) jarSet.add(p);
        }
      }
      System.out.println("   dipendenze (jar): " + (jarSet.size() - before));
    } else {
      System.out.println(
        "   (nota) nessun classpath.txt trovato ? si procede lo stesso."
      );
      Path depDir = module.resolve("target").resolve("dependency");
      if (Files.isDirectory(depDir)) {
        int before = jarSet.size();
        try (Stream<Path> s = Files.list(depDir)) {
          s.filter(p -> p.toString().endsWith(".jar")).forEach(jarSet::add);
        }
        if (jarSet.size() > before) {
          System.out.println(
            "   dipendenze (jar) trovate in target/dependency: " +
            (jarSet.size() - before)
          );
        }
      }
    }

    return new ModuleInputs(prod, test, new ArrayList<>(jarSet));
  }

  private static String repoName(Path baseDir, Path module) {
    Path rel = baseDir.relativize(module);
    return rel.getNameCount() > 0
      ? rel.getName(0).toString()
      : module.getFileName().toString();
  }

  private static Set<String> loadProgress(Path progressFile) {
    try {
      if (!Files.isRegularFile(progressFile)) return new HashSet<>();
      return Files.readAllLines(progressFile, StandardCharsets.UTF_8)
        .stream()
        .map(String::trim)
        .filter(s -> !s.isBlank())
        .collect(Collectors.toCollection(LinkedHashSet::new));
    } catch (IOException e) {
      System.out.println(
        "   (warn) impossibile leggere progress: " + e.getMessage()
      );
      return new HashSet<>();
    }
  }

  private static void appendLine(Path file, String line) {
    try {
      Files.writeString(
        file,
        line + System.lineSeparator(),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      );
    } catch (IOException e) {
      System.out.println(
        "   (warn) progress append fallito: " + e.getMessage()
      );
    }
  }

  private static Set<String> listClassFQNs(Path classesDir) throws IOException {
    if (!Files.isDirectory(classesDir)) return Set.of();
    try (Stream<Path> s = Files.walk(classesDir)) {
      return s
        .filter(Files::isRegularFile)
        .filter(p -> p.toString().endsWith(".class"))
        .map(p -> toFqn(classesDir, p))
        .collect(Collectors.toSet());
    }
  }

  private static String toFqn(Path root, Path file) {
    Path rel = root.relativize(file);
    String s = rel.toString().replace(File.separatorChar, '.');
    if (s.endsWith(".class")) s = s.substring(0, s.length() - 6);
    return s;
  }

  private static boolean isJUnitOrTestNGTest(JavaSootMethod m) {
    for (AnnotationUsage au : m.getAnnotations()) {
      String ann = au.getAnnotation().getFullyQualifiedName();
      if (
        "org.junit.Test".equals(ann) ||
        "org.junit.jupiter.api.Test".equals(ann) ||
        "org.junit.jupiter.params.ParameterizedTest".equals(ann) ||
        ann.startsWith("org.testng.annotations.Test")
      ) {
        return true;
      }
    }
    return false;
  }

  private static String guessFocalClassFromTestName(String testClassFqn) {
    int lastDot = testClassFqn.lastIndexOf('.');
    String pkg = lastDot >= 0 ? testClassFqn.substring(0, lastDot) : "";
    String simple = lastDot >= 0
      ? testClassFqn.substring(lastDot + 1)
      : testClassFqn;
    String base = simple
      .replaceFirst("^(Test)+", "")
      .replaceFirst("(Test|Tests|IT|IntTest)$", "");
    return pkg.isEmpty() ? base : pkg + "." + base;
  }

  private static String simpleName(String fqn) {
    int idx = fqn.lastIndexOf('.');
    return idx >= 0 ? fqn.substring(idx + 1) : fqn;
  }

  private static String methodNameFromSubSig(String subSig) {
    int par = subSig.indexOf('(');
    int sp = subSig.lastIndexOf(' ', par >= 0 ? par : subSig.length());
    if (par > 0 && sp >= 0 && par > sp) {
      return subSig.substring(sp + 1, par);
    }
    return subSig;
  }

  private static boolean isTrivialMethod(String subSig) {
    String name = methodNameFromSubSig(subSig);
    if (name.equals("<init>") || name.equals("<clinit>")) return true;
    if (
      name.equals("toString") ||
      name.equals("equals") ||
      name.equals("hashCode") ||
      name.equals("close") ||
      name.equals("finalize")
    ) return true;
    if (
      (name.startsWith("get") &&
        name.length() >= 4 &&
        Character.isUpperCase(name.charAt(3))) ||
      (name.startsWith("set") &&
        name.length() >= 4 &&
        Character.isUpperCase(name.charAt(3))) ||
      (name.startsWith("is") &&
        name.length() >= 3 &&
        Character.isUpperCase(name.charAt(2)))
    ) {
      return true;
    }
    return false;
  }

  private static Map<MethodSignature, Integer> bfsFrom(
    CallGraph cg,
    MethodSignature start,
    int maxDepth,
    Set<String> projectAllClasses,
    boolean pruneLibs,
    int maxVisited
  ) {
    Map<MethodSignature, Integer> dist = new LinkedHashMap<>();
    ArrayDeque<MethodSignature> q = new ArrayDeque<>();
    dist.put(start, 0);
    q.add(start);

    while (!q.isEmpty()) {
      if (dist.size() >= maxVisited) break;

      MethodSignature u = q.poll();
      int d = dist.get(u);
      if (d >= maxDepth) continue;

      boolean uIsProject = projectAllClasses.contains(
        u.getDeclClassType().getFullyQualifiedName()
      );
      if (pruneLibs && !uIsProject && d >= 1) continue;

      cg
        .callsFrom(u)
        .forEach(call -> {
          MethodSignature v = call.getTargetMethodSignature();
          if (!dist.containsKey(v)) {
            dist.put(v, d + 1);
            if (dist.size() < maxVisited) q.add(v);
          }
        });
    }
    return dist;
  }

  private static boolean detectMocks(CallGraph cg, MethodSignature test) {
    return cg
      .callsFrom(test)
      .stream()
      .anyMatch(call -> {
        String fqn = call
          .getTargetMethodSignature()
          .getDeclClassType()
          .getFullyQualifiedName();
        return (
          fqn.startsWith("org.mockito.") ||
          fqn.startsWith("org.easymock.") ||
          fqn.startsWith("org.powermock.") ||
          fqn.startsWith("io.mockk.")
        );
      });
  }

  private static double clamp(double x, double lo, double hi) {
    return Math.max(lo, Math.min(hi, x));
  }

  // NEW: carica elenco repo consentite da file (uno per riga). Accetta repoName, path o URL.
  private static Set<String> loadRepoFilter(Path file) {
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
        .collect(Collectors.toCollection(LinkedHashSet::new));
    } catch (Exception e) {
      System.err.println(
        "Impossibile leggere onlyFrom: " + file + " → " + e.getMessage()
      );
      return Set.of();
    }
  }
}
