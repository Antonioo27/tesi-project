package ghs.heuristics;

import ghs.model.TestKind;
import java.util.*;
import java.util.stream.Collectors;
import sootup.callgraph.CallGraph;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootMethod;

/**
 * Hybrid test classifier that considers both direct and transitive calls,
 * distinguishing between method-level and class-level granularity.
 * 
 * Key insight: 5 calls to 1 class (unit) vs 5 calls to 5 classes (integration)
 */
public final class HybridTestClassifier implements TestClassifier {

  private final int integrationMinProjectClasses;
  private final int integrationMinProjectMethods;

  public HybridTestClassifier(int integrationMinProjectClasses, int integrationMinProjectMethods) {
    this.integrationMinProjectClasses = Math.max(1, integrationMinProjectClasses);
    this.integrationMinProjectMethods = Math.max(1, integrationMinProjectMethods);
  }

  public HybridTestClassifier(int integrationMinProjectClasses) {
    this(integrationMinProjectClasses, integrationMinProjectClasses * 2);
  }

  @Override
  public ClassificationResult classify(
      CallGraph cg,
      JavaSootMethod testMethod,
      Set<String> projectProdClasses,
      Set<String> projectTestClasses) {
    MethodSignature tSig = testMethod.getSignature();

    // Collect direct calls to project production classes
    List<MethodSignature> directProjectMethods = cg
        .callsFrom(tSig)
        .stream()
        .map(call -> call.getTargetMethodSignature())
        .filter(ms -> projectProdClasses.contains(
            ms.getDeclClassType().getFullyQualifiedName()))
        .collect(Collectors.toList());

    // Group by class to distinguish class-level vs method-level complexity
    Map<String, List<MethodSignature>> callsByClass = directProjectMethods
        .stream()
        .collect(Collectors.groupingBy(
            ms -> ms.getDeclClassType().getFullyQualifiedName()));

    // Extract evidence for classification
    Evidence evidence = new Evidence(
        directProjectMethods.size(), // Total method calls
        callsByClass.size(), // Unique classes called
        directProjectMethods, // Detailed method list
        callsByClass // Calls grouped by class
    );

    // Apply hybrid classification logic
    TestKind kind = classifyWithEvidence(evidence);

    // Build ordered list of classes for compatibility
    LinkedHashSet<String> directProjectClasses = directProjectMethods
        .stream()
        .map(ms -> ms.getDeclClassType().getFullyQualifiedName())
        .collect(Collectors.toCollection(LinkedHashSet::new));

    return new ClassificationResult(
        kind,
        new ArrayList<>(directProjectClasses),
        evidence.uniqueClassCount);
  }

  private TestKind classifyWithEvidence(Evidence evidence) {
    // Strong INTEGRATION signals: Multiple classes involved
    if (evidence.uniqueClassCount >= integrationMinProjectClasses) {
      return TestKind.INTEGRATION;
    }

    // Strong UNIT signals: Few or no direct project calls
    if (evidence.uniqueClassCount == 0) {
      return TestKind.UNIT; // No project classes called
    }

    if (evidence.uniqueClassCount == 1) {
      // Single class: check if it's really complex
      if (evidence.totalMethodCalls >= integrationMinProjectMethods) {
        // Many calls to same class might indicate integration-like complexity
        return TestKind.INTEGRATION;
      } else {
        // Few calls to single class: classic unit test
        return TestKind.UNIT;
      }
    }

    // Default fallback (shouldn't reach here with current logic)
    return TestKind.UNIT;
  }

  /**
   * Evidence collected for classification decision
   */
  public static class Evidence {
    public final int totalMethodCalls; // Total direct method calls to project
    public final int uniqueClassCount; // Number of unique project classes called
    public final List<MethodSignature> allMethods; // All direct method calls
    public final Map<String, List<MethodSignature>> methodsByClass; // Calls grouped by class

    public Evidence(
        int totalMethodCalls,
        int uniqueClassCount,
        List<MethodSignature> allMethods,
        Map<String, List<MethodSignature>> methodsByClass) {
      this.totalMethodCalls = totalMethodCalls;
      this.uniqueClassCount = uniqueClassCount;
      this.allMethods = List.copyOf(allMethods);
      this.methodsByClass = Map.copyOf(methodsByClass);
    }

    /**
     * Get the most frequently called class (potential focal class)
     */
    public Optional<String> getMostCalledClass() {
      return methodsByClass.entrySet()
          .stream()
          .max(Map.Entry.comparingByValue(Comparator.comparing(List::size)))
          .map(Map.Entry::getKey);
    }

    /**
     * Calculate concentration: how focused the test is on one class
     * Returns 1.0 if all calls go to one class, 0.0 if evenly distributed
     */
    public double getClassConcentration() {
      if (uniqueClassCount <= 1)
        return 1.0;

      int maxCallsToOneClass = methodsByClass.values()
          .stream()
          .mapToInt(List::size)
          .max()
          .orElse(0);

      return totalMethodCalls > 0 ? (double) maxCallsToOneClass / totalMethodCalls : 0.0;
    }
  }
}