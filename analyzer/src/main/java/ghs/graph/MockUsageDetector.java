package ghs.analyzer.graph;

import sootup.callgraph.CallGraph;
import sootup.core.signatures.MethodSignature;

public final class MockUsageDetector {

  public boolean usesMocks(CallGraph cg, MethodSignature test) {
    return cg
      .callsFrom(test)
      .stream()
      .anyMatch(call -> {
        String fqn = call
          .getTargetMethodSignature()
          .getDeclClassType()
          .getFullyQualifiedName();
        return (
          fqn.startsWith("org.mockito.") ||
          fqn.startsWith("org.easymock.") ||
          fqn.startsWith("org.powermock.") ||
          fqn.startsWith("io.mockk.")
        );
      });
  }
}
