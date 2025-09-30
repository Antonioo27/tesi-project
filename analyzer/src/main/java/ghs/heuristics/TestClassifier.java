package ghs.heuristics;

import ghs.model.TestKind;
import java.util.List;
import java.util.Set;
import sootup.callgraph.CallGraph;
import sootup.java.core.JavaSootMethod;

public interface TestClassifier {
  // ORDINE DEFINITIVO: kind, classes, count
  record ClassificationResult(
      TestKind kind,
      List<String> directProjectClasses,
      int directRefsCount) {
  }

  ClassificationResult classify(
      CallGraph cg,
      JavaSootMethod testMethod,
      Set<String> projectProdClasses,
      Set<String> projectTestClasses);
}
