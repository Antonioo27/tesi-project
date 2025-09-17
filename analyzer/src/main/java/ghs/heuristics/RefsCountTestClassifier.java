package ghs.analyzer.heuristics;

import ghs.analyzer.model.TestKind;
import java.util.*;
import java.util.stream.Collectors;
import sootup.callgraph.CallGraph;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootMethod;

public final class RefsCountTestClassifier implements TestClassifier {

  private final int integrationMinProjectClasses;

  public RefsCountTestClassifier(int integrationMinProjectClasses) {
    this.integrationMinProjectClasses = Math.max(
      1,
      integrationMinProjectClasses
    );
  }

  @Override
  public ClassificationResult classify(
    CallGraph cg,
    JavaSootMethod testMethod,
    Set<String> projectProdClasses,
    Set<String> projectTestClasses
  ) {
    MethodSignature tSig = testMethod.getSignature();

    // classi di PRODUZIONE del progetto chiamate DIRETTAMENTE dal metodo di test
    LinkedHashSet<String> directProjectClasses = cg
      .callsFrom(tSig)
      .stream()
      .map(c ->
        c.getTargetMethodSignature().getDeclClassType().getFullyQualifiedName()
      )
      .filter(projectProdClasses::contains)
      .collect(Collectors.toCollection(LinkedHashSet::new));

    TestKind kind = directProjectClasses.size() >= integrationMinProjectClasses
      ? TestKind.INTEGRATION
      : TestKind.UNIT;

    // ORDINE: kind, lista, count
    return new ClassificationResult(
      kind,
      new ArrayList<>(directProjectClasses),
      directProjectClasses.size()
    );
  }
}
