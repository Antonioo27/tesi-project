package ghs.analyzer.pipeline;

import ghs.analyzer.discovery.TestDiscovery;
import ghs.analyzer.graph.*;
import ghs.analyzer.heuristics.*;
import ghs.analyzer.model.*;
import ghs.analyzer.sootupview.ViewFactory;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
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

  public FullCallGraphStrategy(
    ViewFactory viewFactory,
    TestDiscovery discovery,
    FocalClassHeuristic classHeu,
    FocalMethodHeuristic methodHeu,
    BfsTraverser bfs,
    MockUsageDetector mocks,
    UnitIntegrationScorer scorer
  ) {
    this.viewFactory = viewFactory;
    this.discovery = discovery;
    this.classHeu = classHeu;
    this.methodHeu = methodHeu;
    this.bfs = bfs;
    this.mocks = mocks;
    this.scorer = scorer;
    this.analyzer = new ChaCallGraphAnalyzer(bfs, mocks, scorer);
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
    // Crea view con prod/test + eventuali JAR
    List<AnalysisInputLocation> locs = new ArrayList<>();
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
    if (cfg.useJars()) {
      // NB: la selezione dei JAR viene fatta a monte nel ModuleAnalyzer, qui potresti ricevere un elenco già filtrato se necessario
      // In questa versione minimale lasciamo alla View l'aggiunta eventuale (estendibile)
    }
    locs.add(new JrtFileSystemAnalysisInputLocation());
    JavaView view = viewFactory.create(locs);

    // Build CG per il batch
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
}
