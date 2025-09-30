package ghs.graph;

import sootup.callgraph.CallGraph;
import sootup.core.signatures.MethodSignature;

public final class MockUsageDetector {

  public boolean usesMocks(CallGraph cg, MethodSignature test) {
    return cg.callsFrom(test).stream().anyMatch(call -> {
      String fqn = call.getTargetMethodSignature().getDeclClassType().getFullyQualifiedName();
      String sub = call.getTargetMethodSignature().getSubSignature().toString();
      return fqn.startsWith("org.mockito.")
          || fqn.startsWith("org.easymock.")
          || fqn.startsWith("org.powermock.")
          || fqn.startsWith("io.mockk.")
      // segnali tipici Mockito:
          || (fqn.startsWith("org.mockito.") && (sub.contains(" when(") || sub.contains(" verify(") ||
              sub.contains(" spy(") || sub.contains(" mock(") ||
              sub.contains(" openMocks(") || sub.contains(" initMocks(")));
    });
  }
}
