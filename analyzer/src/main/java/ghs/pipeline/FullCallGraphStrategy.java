// file: src/main/java/ghs/analyzer/pipeline/FullCallGraphStrategy.java
package ghs.pipeline;

import ghs.discovery.TestDiscovery;
import ghs.graph.*;
import ghs.heuristics.*;
import ghs.model.*;
import ghs.sootupview.ViewFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import sootup.callgraph.CallGraph;
import sootup.callgraph.ClassHierarchyAnalysisAlgorithm;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.signatures.MethodSignature;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JrtFileSystemAnalysisInputLocation;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;

/**
 * Strategia "full": costruisce il call-graph (CHA) per un batch di test
 * e analizza ciascun test con BFS + euristiche (focal class/method) + scoring.
 *
 * NOTE IMPORTANTI:
 * - L'euristica del focal method è ora stateless: il contesto del test viene
 * costruito in ChaCallGraphAnalyzer (FocalMethodContext) e passato alla
 * FocalMethodHeuristic.selectFocalMethod(ctx). Qui non serve più settare alcun
 * contesto.
 */
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
      TestClassifier classifier) {
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
      AnalysisConfig cfg) throws Exception {

    // Input locations: prod + test + JDK
    List<AnalysisInputLocation> locs = new ArrayList<>();
    locs.add(new JavaClassPathAnalysisInputLocation(module.resolve("target/classes").toString()));
    locs.add(new JavaClassPathAnalysisInputLocation(module.resolve("target/test-classes").toString()));
    locs.add(new JrtFileSystemAnalysisInputLocation()); // JDK rt

    JavaView view = viewFactory.create(locs);

    // Call graph CHA inizializzato con i metodi di test del batch
    ClassHierarchyAnalysisAlgorithm cha = new ClassHierarchyAnalysisAlgorithm(view);
    List<MethodSignature> entries = batch.stream()
        .map(JavaSootMethod::getSignature)
        .collect(Collectors.toList());
    CallGraph cg = cha.initialize(entries);

    // Helper per ottenere il simple name da FQN
    Function<String, String> simpleName = fqn -> {
      int i = fqn.lastIndexOf('.');
      return i >= 0 ? fqn.substring(i + 1) : fqn;
    };

    List<TestRecord> results = new ArrayList<>(batch.size());
    for (JavaSootMethod tm : batch) {
      // Nessun setContext: il contesto sarà costruito in ChaCallGraphAnalyzer
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
              cfg.maxVisited(),
              simpleName,
              classHeu,
              methodHeu));
    }

    System.gc(); // best-effort per rilasciare memoria tra batch
    return results;
  }
}
