package ghs.analyzer.pipeline;

import ghs.analyzer.discovery.TestDiscovery;
import ghs.analyzer.graph.*;
import ghs.analyzer.heuristics.*;
import ghs.analyzer.model.*;
import ghs.analyzer.sootupview.ViewFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.io.File;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;     // se usi Files.list senza FQCN
import sootup.callgraph.CallGraph;
import sootup.callgraph.ClassHierarchyAnalysisAlgorithm;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.signatures.MethodSignature;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JrtFileSystemAnalysisInputLocation;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;

public final class FullCallGraphStrategy implements AnalyzerStrategy {

  private final ViewFactory viewFactory;
  private final TestDiscovery discovery;
  private final FocalClassHeuristic classHeu;
  private final FocalMethodHeuristic methodHeu;
  private final BfsTraverser bfs;
  private final MockUsageDetector mocks;
  private final UnitIntegrationScorer scorer;
  private final CallGraphAnalyzer analyzer;
  private final TestClassifier classifier;

  public FullCallGraphStrategy(
    ViewFactory viewFactory,
    TestDiscovery discovery,
    FocalClassHeuristic classHeu,
    FocalMethodHeuristic methodHeu,
    BfsTraverser bfs,
    MockUsageDetector mocks,
    UnitIntegrationScorer scorer,
    TestClassifier classifier
  ) {
    this.viewFactory = viewFactory;
    this.discovery = discovery;
    this.classHeu = classHeu;
    this.methodHeu = methodHeu;
    this.bfs = bfs;
    this.mocks = mocks;
    this.scorer = scorer;
    this.analyzer = new ChaCallGraphAnalyzer(bfs, mocks, scorer, classifier);
    this.classifier = classifier;
  }

  @Override
  public List<TestRecord> analyzeBatch(
    String repo,
    Path module,
    String cfgId,
    List<JavaSootMethod> batch,
    ProjectIndex idx,
    AnalysisConfig cfg
  ) throws Exception {
    List<AnalysisInputLocation> locs = new ArrayList<>();
    // prod + test
    locs.add(
      new JavaClassPathAnalysisInputLocation(
        module.resolve("target/classes").toString()
      )
    );
    locs.add(
      new JavaClassPathAnalysisInputLocation(
        module.resolve("target/test-classes").toString()
      )
    );

    // JAR di dipendenze (se richiesti)
    if (cfg.useJars()) {
      List<Path> jars = findDependencyJars(module);
      if (cfg.maxJars() >= 0 && jars.size() > cfg.maxJars()) {
        jars = jars.subList(0, cfg.maxJars());
      }
      for (Path jar : jars) {
        locs.add(new JavaClassPathAnalysisInputLocation(jar.toString()));
      }
    }

    // JDK
    locs.add(new JrtFileSystemAnalysisInputLocation());

    JavaView view = viewFactory.create(locs);

    ClassHierarchyAnalysisAlgorithm cha = new ClassHierarchyAnalysisAlgorithm(
      view
    );
    List<MethodSignature> entries = batch
      .stream()
      .map(JavaSootMethod::getSignature)
      .collect(Collectors.toList());
    CallGraph cg = cha.initialize(entries);

    java.util.function.Function<String, String> simpleName = fqn -> {
      int i = fqn.lastIndexOf('.');
      return i >= 0 ? fqn.substring(i + 1) : fqn;
    };

    List<TestRecord> results = new ArrayList<>(batch.size());
    for (JavaSootMethod tm : batch) {
      results.add(
        analyzer.analyzeOne(
          repo,
          module,
          cfgId,
          cg,
          tm,
          idx.projectProdClasses(),
          idx.projectTestClasses(),
          idx.projectAllClasses(),
          cfg.maxDepth(),
          cfg.pruneLibs(),
          cfg.maxVisited(),
          simpleName,
          classHeu,
          methodHeu
        )
      );
    }
    System.gc();
    return results;
  }

  // ===== helper locale =====
  private static List<Path> findDependencyJars(Path module) {
    List<Path> out = new ArrayList<>();
    Path cp = module.resolve("target").resolve("classpath.txt");

    try {
      if (Files.isRegularFile(cp)) {
        for (String line : Files.readAllLines(
          cp,
          java.nio.charset.StandardCharsets.UTF_8
        )) {
          for (String raw : line.split(
            java.util.regex.Pattern.quote(File.pathSeparator)
          )) {
            String entry = raw.trim().replace("\"", "");
            if (entry.endsWith(".jar")) {
              Path p = Paths.get(entry);
              if (!p.isAbsolute()) p = module
                .resolve(p)
                .toAbsolutePath()
                .normalize();
              if (Files.isRegularFile(p)) out.add(p);
            }
          }
        }
      } else {
        Path depDir = module.resolve("target").resolve("dependency");
        if (Files.isDirectory(depDir)) {
          try (java.util.stream.Stream<Path> s = Files.list(depDir)) {
            s.filter(p -> p.toString().endsWith(".jar")).forEach(out::add);
          }
        }
      }
    } catch (Exception ignored) {}
    return out;
  }
}
