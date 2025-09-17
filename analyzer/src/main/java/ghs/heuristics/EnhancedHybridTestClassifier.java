package ghs.analyzer.heuristics;

import ghs.analyzer.model.TestKind;
import java.util.*;
import java.util.stream.Collectors;
import sootup.callgraph.CallGraph;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootMethod;

/**
 * Enhanced hybrid test classifier that considers both direct and transitive calls,
 * with sophisticated analysis of call patterns and class relationships.
 */
public final class EnhancedHybridTestClassifier implements TestClassifier {

  private final int integrationMinProjectClasses;
  private final int integrationMinProjectMethods;
  private final double highConcentrationThreshold;

  public EnhancedHybridTestClassifier(
    int integrationMinProjectClasses, 
    int integrationMinProjectMethods,
    double highConcentrationThreshold
  ) {
    this.integrationMinProjectClasses = Math.max(1, integrationMinProjectClasses);
    this.integrationMinProjectMethods = Math.max(1, integrationMinProjectMethods);
    this.highConcentrationThreshold = Math.max(0.0, Math.min(1.0, highConcentrationThreshold));
  }

  public EnhancedHybridTestClassifier(int integrationMinProjectClasses) {
    this(integrationMinProjectClasses, integrationMinProjectClasses * 3, 0.8);
  }

  @Override
  public ClassificationResult classify(
    CallGraph cg,
    JavaSootMethod testMethod,
    Set<String> projectProdClasses,
    Set<String> projectTestClasses
  ) {
    MethodSignature tSig = testMethod.getSignature();

    // Collect direct calls evidence
    DirectCallEvidence directEvidence = analyzeDirectCalls(cg, tSig, projectProdClasses);
    
    // Apply hybrid classification logic
    TestKind kind = classifyWithMultipleFactors(directEvidence);

    return new ClassificationResult(
      kind,
      new ArrayList<>(directEvidence.uniqueClasses),
      directEvidence.uniqueClassCount
    );
  }

  /**
   * Enhanced classification that considers multiple factors
   */
  private TestKind classifyWithMultipleFactors(DirectCallEvidence evidence) {
    // === STRONG INTEGRATION SIGNALS ===
    
    // Multiple classes: clear integration
    if (evidence.uniqueClassCount >= integrationMinProjectClasses) {
      return TestKind.INTEGRATION;
    }

    // High method count with low concentration: indicates cross-class complexity
    if (evidence.totalMethodCalls >= integrationMinProjectMethods && 
        evidence.getClassConcentration() < highConcentrationThreshold) {
      return TestKind.INTEGRATION;
    }

    // === STRONG UNIT SIGNALS ===
    
    // No project classes: pure framework/library test
    if (evidence.uniqueClassCount == 0) {
      return TestKind.UNIT;
    }

    // Single class with high concentration: focused unit test
    if (evidence.uniqueClassCount == 1 && 
        evidence.getClassConcentration() >= highConcentrationThreshold) {
      return TestKind.UNIT;
    }

    // === BORDERLINE CASES ===
    
    // Single class, moderate complexity: lean towards unit
    if (evidence.uniqueClassCount == 1) {
      return TestKind.UNIT;
    }

    // Default for unclear cases
    return TestKind.UNIT;
  }

  /**
   * Analyze direct calls to project production classes
   */
  private DirectCallEvidence analyzeDirectCalls(
    CallGraph cg, 
    MethodSignature testSig, 
    Set<String> projectProdClasses
  ) {
    // Get all direct calls to project methods
    List<MethodSignature> directProjectMethods = cg
      .callsFrom(testSig)
      .stream()
      .map(call -> call.getTargetMethodSignature())
      .filter(ms -> projectProdClasses.contains(
        ms.getDeclClassType().getFullyQualifiedName()
      ))
      .collect(Collectors.toList());

    // Group by class
    Map<String, List<MethodSignature>> callsByClass = directProjectMethods
      .stream()
      .collect(Collectors.groupingBy(
        ms -> ms.getDeclClassType().getFullyQualifiedName()
      ));

    // Extract unique classes in order
    LinkedHashSet<String> uniqueClasses = directProjectMethods
      .stream()
      .map(ms -> ms.getDeclClassType().getFullyQualifiedName())
      .collect(Collectors.toCollection(LinkedHashSet::new));

    return new DirectCallEvidence(
      directProjectMethods.size(),
      callsByClass.size(),
      uniqueClasses,
      directProjectMethods,
      callsByClass
    );
  }

  /**
   * Evidence from direct call analysis
   */
  public static class DirectCallEvidence {
    public final int totalMethodCalls;
    public final int uniqueClassCount;
    public final LinkedHashSet<String> uniqueClasses;
    public final List<MethodSignature> allMethods;
    public final Map<String, List<MethodSignature>> methodsByClass;

    public DirectCallEvidence(
      int totalMethodCalls,
      int uniqueClassCount,
      LinkedHashSet<String> uniqueClasses,
      List<MethodSignature> allMethods,
      Map<String, List<MethodSignature>> methodsByClass
    ) {
      this.totalMethodCalls = totalMethodCalls;
      this.uniqueClassCount = uniqueClassCount;
      this.uniqueClasses = uniqueClasses;
      this.allMethods = List.copyOf(allMethods);
      this.methodsByClass = Map.copyOf(methodsByClass);
    }

    /**
     * Calculate how concentrated the calls are on a single class
     */
    public double getClassConcentration() {
      if (uniqueClassCount <= 1) return 1.0;
      
      int maxCallsToOneClass = methodsByClass.values()
        .stream()
        .mapToInt(List::size)
        .max()
        .orElse(0);
      
      return totalMethodCalls > 0 ? (double) maxCallsToOneClass / totalMethodCalls : 0.0;
    }

    /**
     * Get the most frequently called class (likely focal class)
     */
    public Optional<String> getPrimaryFocalClass() {
      return methodsByClass.entrySet()
        .stream()
        .max(Map.Entry.comparingByValue(Comparator.comparing(List::size)))
        .map(Map.Entry::getKey);
    }

    /**
     * Check if this looks like a single-class focused test
     */
    public boolean isHighlyFocused() {
      return uniqueClassCount == 1 || getClassConcentration() >= 0.8;
    }

    /**
     * Get detailed breakdown for debugging
     */
    public String getCallBreakdown() {
      return methodsByClass.entrySet()
        .stream()
        .map(entry -> entry.getKey() + ": " + entry.getValue().size() + " calls")
        .collect(Collectors.joining(", "));
    }
  }
}