package ghs.analyzer.graph;

public final class UnitIntegrationScorer {

  public double score(
    int projectTargets,
    int callsToOtherProjectClasses,
    boolean usesMocks
  ) {
    double denom = projectTargets == 0 ? 1.0 : projectTargets;
    double raw = callsToOtherProjectClasses / denom;
    double s = raw - (usesMocks ? 0.2 : 0.0);
    if (s < 0) s = 0;
    if (s > 1) s = 1;
    return s;
  }
}


/**
 * VERSION 2
 * public double scoreV2(int projectTargets, int callsToOtherProjectClasses,
                      boolean usesMocks, int uniqueProjectClasses, int maxDepthVisited) {
  double denom = projectTargets == 0 ? 1.0 : projectTargets;
  double ratio = callsToOtherProjectClasses / denom;

  double depthPenalty = Math.max(0, maxDepthVisited - 1) * 0.10;         // d=1→0, d=2→0.1, d=3→0.2
  double spreadPenalty = Math.max(0, uniqueProjectClasses - 1) * 0.05;    // 1 class→0, 3 classi→0.10
  double mockBonus = usesMocks ? 0.20 : 0.0;

  double s = ratio + depthPenalty + spreadPenalty - mockBonus;
  if (s < 0) s = 0; if (s > 1) s = 1;
  return s;
}

 */