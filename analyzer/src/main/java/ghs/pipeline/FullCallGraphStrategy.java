// file: src/main/java/ghs/analyzer/pipeline/FullCallGraphStrategy.java
package ghs.pipeline;

import ghs.discovery.TestDiscovery;
import ghs.graph.*;
import ghs.heuristics.*;
import ghs.model.*;
import ghs.sootupview.ViewFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.io.File;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream; // se usi Files.list senza FQCN
import sootup.callgraph.CallGraph;
import sootup.callgraph.ClassHierarchyAnalysisAlgorithm;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.signatures.MethodSignature;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JrtFileSystemAnalysisInputLocation;
import sootup.java.core.JavaSootMethod;
import java.util.function.Function;
import sootup.java.core.views.JavaView;

/**
 * Strategia "full": costruisce il call-graph (CHA) per un batch di test
 * e analizza ciascun test con BFS + euristiche (focal class/method) + scoring.
 *
 * NOTA IMPORTANTE:
 * - Se la FocalMethodHeuristic è di tipo AssertionAwareFocalMethodHeuristic,
 * qui settiamo il contesto (testMethod, module) PRIMA di selezionare il focal
 * method,
 * così l'euristica può leggere il bytecode del test (target/test-classes).
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
    List<AnalysisInputLocation> locs = new ArrayList<>();
    locs.add(new JavaClassPathAnalysisInputLocation(module.resolve("target/classes").toString()));
    locs.add(new JavaClassPathAnalysisInputLocation(module.resolve("target/test-classes").toString()));
    locs.add(new JrtFileSystemAnalysisInputLocation()); // JDK

    JavaView view = viewFactory.create(locs);

    ClassHierarchyAnalysisAlgorithm cha = new ClassHierarchyAnalysisAlgorithm(view);
    List<MethodSignature> entries = batch.stream()
        .map(JavaSootMethod::getSignature)
        .collect(Collectors.toList());
    CallGraph cg = cha.initialize(entries);

    Function<String, String> simpleName = fqn -> {
      int i = fqn.lastIndexOf('.');
      return i >= 0 ? fqn.substring(i + 1) : fqn;
    };

    List<TestRecord> results = new ArrayList<>(batch.size());
    for (JavaSootMethod tm : batch) {
      if (methodHeu instanceof AssertionAwareFocalMethodHeuristic a) {
        a.setContext(tm, module);
      }

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
    System.gc();
    return results;
  }
}
