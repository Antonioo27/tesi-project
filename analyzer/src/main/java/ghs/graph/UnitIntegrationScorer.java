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
