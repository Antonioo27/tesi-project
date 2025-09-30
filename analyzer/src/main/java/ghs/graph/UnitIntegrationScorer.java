// src/main/java/ghs/analyzer/graph/UnitIntegrationScorer.java
package ghs.graph;

public final class UnitIntegrationScorer {

  public static final class Features {
    public final int directRefsCount; // # classi progetto dirette (dal classificatore)
    public final int uniqueProjectClasses; // # classi progetto via BFS
    public final int maxDepthVisited; // profondità max via BFS
    public final boolean usesMocks; // segnali dai framework mock
    public final int projectCalls; // # invocazioni a metodi di classi progetto (via BFS)
    public final int callsToFocalClass; // # invocazioni a metodi della focal class (via BFS, 0 se INTEGRATION)

    public Features(int directRefsCount, int uniqueProjectClasses, int maxDepthVisited,
        boolean usesMocks, int projectCalls, int callsToFocalClass) {
      this.directRefsCount = directRefsCount;
      this.uniqueProjectClasses = uniqueProjectClasses;
      this.maxDepthVisited = maxDepthVisited;
      this.usesMocks = usesMocks;
      this.projectCalls = projectCalls;
      this.callsToFocalClass = callsToFocalClass;
    }
  }

  // Formula con semantica corretta:
  // ↑ verso 1.0 (INTEGRATION) con molte classi dirette / spread / profondità
  // ↓ verso 0.0 (UNIT) con mock e forte concentrazione sulla focal class
  public double score(Features f) {
    double denom = f.projectCalls <= 0 ? 1.0 : (double) f.projectCalls;
    double focalShare = f.callsToFocalClass / denom; // 0..1

    // Fattori che aumentano il punteggio verso INTEGRATION (1.0)
    // Note: directRefsCount is the primary indicator, uniqueProjectClasses can be
    // high for any test
    double integrationFactors = 0.60 * Math.max(0, f.directRefsCount - 1)
        + 0.05 * Math.max(0, f.uniqueProjectClasses - 5) // Reduced weight, higher threshold
        + 0.05 * Math.max(0, f.maxDepthVisited - 2); // Reduced weight, higher threshold

    // Fattori che spingono verso UNIT (riducono il punteggio verso 0.0)
    double unitFactors = 0.30 * (f.usesMocks ? 1.0 : 0.0) // Mock usage strongly indicates unit testing
        + 0.40 * focalShare; // High focus on single class = unit-like

    double s = integrationFactors - unitFactors;

    if (s < 0)
      s = 0;
    if (s > 1)
      s = 1;
    return s;
  }
}
