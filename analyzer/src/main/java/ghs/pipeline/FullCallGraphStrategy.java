package ghs.pipeline;

import ghs.discovery.TestDiscovery;
import ghs.graph.*;
import ghs.heuristics.*;
import ghs.model.*;
import ghs.sootupview.ViewFactory;
import java.nio.file.Path;
import java.util.ArrayList;
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
import ghs.combine.TestResultCombiner;

/**
 * FullCallGraphStrategy (fase di raccolta).
 * Ora delega la raccolta a ChaCallGraphAnalyzer.collect e (TEMPORANEAMENTE)
 * mappa RawTestAnalysis -> TestRecord finché non introduciamo un Combiner.
 */
public final class FullCallGraphStrategy implements AnalyzerStrategy {

  private final ViewFactory viewFactory;
  private final TestDiscovery discovery;
  private final ChaCallGraphAnalyzer analyzer;
  private final TestResultCombiner combiner;

  public FullCallGraphStrategy(
      ViewFactory viewFactory,
      TestDiscovery discovery,
      BfsTraverser bfs,
      HeuristicEngine heuristicEngine,
      TestResultCombiner combiner) {
    this.viewFactory = viewFactory;
    this.discovery = discovery;
    this.combiner = combiner;
    this.analyzer = new ChaCallGraphAnalyzer(bfs, heuristicEngine);
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
    locs.add(new JrtFileSystemAnalysisInputLocation());

    JavaView view = viewFactory.create(locs);

    ClassHierarchyAnalysisAlgorithm cha = new ClassHierarchyAnalysisAlgorithm(view);
    List<MethodSignature> entries = batch.stream()
        .map(JavaSootMethod::getSignature)
        .collect(Collectors.toList());
    CallGraph cg = cha.initialize(entries);

    List<TestRecord> out = new ArrayList<>(batch.size());
    for (JavaSootMethod tm : batch) {
      RawTestAnalysis raw = analyzer.collect(
          repo,
          module,
          cfgId,
          cg,
          tm,
          idx.projectProdClasses(),
          idx.projectTestClasses(),
          idx.projectAllClasses(),
          cfg.maxDepth(),
          cfg.maxVisited());

      out.add(combiner.combine(raw));
    }

    System.gc();
    return out;
  }
}