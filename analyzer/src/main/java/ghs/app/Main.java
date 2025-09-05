package ghs.analyzer.app;

import ghs.analyzer.cli.*;
import ghs.analyzer.discovery.*;
import ghs.analyzer.graph.*;
import ghs.analyzer.heuristics.*;
import ghs.analyzer.io.*;
import ghs.analyzer.model.*;
import ghs.analyzer.pipeline.*;
import ghs.analyzer.sootupview.*;
import java.nio.file.*;

public final class Main {

  public static void main(String[] args) throws Exception {
    CliOptions opts = CliParser.parse(args);
    AnalysisConfig cfg = AnalysisConfig.from(opts);

    // I/O e risorse
    ModuleScanner scanner = new DefaultModuleScanner();
    InputResolver inputResolver = new DefaultInputResolver();
    ProgressStore progress = new FileProgressStore();
    OutputSink out = new JsonlOutputSink(cfg);

    // SootUp & discovery
    ViewFactory viewFactory = new DefaultViewFactory();
    TestDiscovery discovery = new JUnitTestDiscovery();

    // Heuristics & graph
    FocalClassHeuristic classHeu = new NameBasedFocalClassHeuristic();
    FocalMethodHeuristic methodHeu = new NameAndDistanceFocalMethodHeuristic();
    BfsTraverser bfs = new BfsTraverser();
    MockUsageDetector mocks = new MockUsageDetector();
    UnitIntegrationScorer scorer = new UnitIntegrationScorer();

    // Strategie
    AnalyzerStrategy fast = new FastHeuristicStrategy(classHeu);
    AnalyzerStrategy full = new FullCallGraphStrategy(
      viewFactory,
      discovery,
      classHeu,
      methodHeu,
      bfs,
      mocks,
      scorer
    );

    ModuleAnalyzer moduleAnalyzer = new ModuleAnalyzer(
      inputResolver,
      viewFactory,
      discovery,
      progress,
      out,
      fast,
      full
    );

    AnalyzerPipeline pipeline = new AnalyzerPipeline(
      scanner,
      moduleAnalyzer,
      cfg
    );
    pipeline.run(Paths.get(cfg.baseDir()));
    out.close();

    System.out.println("\n✅ Analisi completata.");
  }
}
