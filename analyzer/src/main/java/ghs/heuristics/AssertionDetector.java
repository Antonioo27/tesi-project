package ghs.analyzer.heuristics;

public final class AssertionDetector {
  private static final String[] ASSERT_FQNS = new String[] {
      "org.junit.Assert",
      "org.junit.jupiter.api.Assertions",
      "org.assertj.core.api.Assertions",
      "org.hamcrest.MatcherAssert"
  };

  public static boolean isAssertionOwnerInternal(String ownerInternalName) {
    // owner arriva in formato "org/junit/Assert"
    String fqn = ownerInternalName.replace('/', '.');
    for (String p : ASSERT_FQNS) {
      if (fqn.startsWith(p)) return true;
    }
    return false;
  }
}