package ghs.analyzer.pipeline;

import ghs.analyzer.heuristics.FocalClassHeuristic;
import ghs.analyzer.model.*;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import sootup.java.core.JavaSootMethod;

public final class FastHeuristicStrategy implements AnalyzerStrategy {

  private final FocalClassHeuristic classHeu;

  public FastHeuristicStrategy(FocalClassHeuristic classHeu) {
    this.classHeu = classHeu;
  }

  @Override
  public List<TestRecord> analyzeBatch(
    String repo,
    Path module,
    String cfgId,
    List<JavaSootMethod> batch,
    ProjectIndex idx,
    AnalysisConfig cfg
  ) {
    return batch
      .stream()
      .map(tm -> {
        var s = tm.getSignature();
        String testClass = s.getDeclClassType().getFullyQualifiedName();
        String testMethod = s.getSubSignature().toString();
        String focalClass = classHeu.guessFocalClassFromTestName(testClass);
        return new TestRecord(
          repo,
          module.toString(),
          cfgId,
          testClass,
          testMethod,
          focalClass,
          "",
          new CgStats(0, 0, 0, 0, 0, 0),
          false,
          0.0
        );
      })
      .collect(Collectors.toList());
  }
}
