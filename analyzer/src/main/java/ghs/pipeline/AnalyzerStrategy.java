package ghs.analyzer.pipeline;

import ghs.analyzer.model.*;
import java.nio.file.Path;
import java.util.List;
import sootup.java.core.JavaSootMethod;

public interface AnalyzerStrategy {
  /** Analizza i test del modulo e restituisce eventuali record da scrivere. */
  List<TestRecord> analyzeBatch(
    String repoName,
    Path module,
    String cfgId,
    List<JavaSootMethod> batch,
    ProjectIndex idx,
    AnalysisConfig cfg
  ) throws Exception;

  record ProjectIndex(
    java.util.Set<String> projectProdClasses,
    java.util.Set<String> projectTestClasses,
    java.util.Set<String> projectAllClasses
  ) {}
}
