// src/main/java/ghs/analyzer/graph/UnitIntegrationScorer.java
package ghs.analyzer.graph;

public final class UnitIntegrationScorer {

  public static final class Features {
    public final int directRefsCount;      // # classi progetto dirette (dal classificatore)
    public final int uniqueProjectClasses; // # classi progetto via BFS
    public final int maxDepthVisited;      // profondità max via BFS
    public final boolean usesMocks;        // segnali dai framework mock
    public final int projectCalls;         // # invocazioni a metodi di classi progetto (via BFS)
    public final int callsToFocalClass;    // # invocazioni a metodi della focal class (via BFS, 0 se INTEGRATION)

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

  // Formula semplice, pesi ritarabili:
  //  ↑ con molte classi dirette / spread / profondità
  //  ↓ con mock e con forte concentrazione sulla focal class (solo Unit)
  public double score(Features f) {
    double denom = f.projectCalls <= 0 ? 1.0 : (double) f.projectCalls;
    double focalShare = f.callsToFocalClass / denom; // 0..1

    double s = 0.25 * Math.max(0, f.directRefsCount - 1)
             + 0.15 * Math.max(0, f.uniqueProjectClasses - 1)
             + 0.10 * Math.max(0, f.maxDepthVisited - 1)
             - 0.20 * (f.usesMocks ? 1.0 : 0.0)
             - 0.25 * focalShare;

    if (s < 0) s = 0;
    if (s > 1) s = 1;
    return s;
  }
}
