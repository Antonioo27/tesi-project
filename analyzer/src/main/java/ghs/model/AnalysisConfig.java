package ghs.analyzer.model;

import ghs.analyzer.cli.CliOptions;

public record AnalysisConfig(
  String baseDir,
  String outPath,
  int maxDepth,
  boolean pruneLibs,
  int maxVisited,
  int batchSize,
  boolean splitByRepo,
  boolean append,
  boolean useJars,
  boolean resume,
  boolean resumeReset,
  int maxJars,
  int ignoreJarsIfTestsOver,
  int batchesPerView,
  boolean autoTune,
  int bigThr,
  int hugeThr,
  int autoBatchBig,
  int autoBatchHuge,
  int autoVisitedBig,
  int autoVisitedHuge,
  boolean autoFastHeuristic,
  String onlyFromFile,
  int preflightN,
  int preflightMinHeadroomMb,
  boolean skipOnOom,
  int integrationMinProjectClasses,     // <-- AGGIUNTO IN CODA
  int integrationMinProjectMethods,     // minimum method calls for integration
  double highConcentrationThreshold    // threshold for high concentration (0.0-1.0)
) {
  public static AnalysisConfig from(CliOptions o) {
    return new AnalysisConfig(
      o.base().toString(),
      o.out().toString(),
      o.maxDepth(),
      o.pruneLibs(),
      o.maxVisited(),
      o.batchSize(),
      o.splitByRepo(),
      o.append(),
      o.useJars(),
      o.resume(),
      o.resumeReset(),
      o.maxJars(),
      o.ignoreJarsIfTestsOver(),
      o.batchesPerView(),
      o.autoTune(),
      o.bigThr(),
      o.hugeThr(),
      o.autoBatchBig(),
      o.autoBatchHuge(),
      o.autoVisitedBig(),
      o.autoVisitedHuge(),
      o.autoFastHeuristic(),
      o.onlyFrom().map(java.nio.file.Path::toString).orElse(""),
      o.preflightN(),
      o.preflightMinHeadroomMb(),
      o.skipOnOom(),
      o.integrationMinProjectClasses(),  // <-- accessor pubblico del record CliOptions
      o.integrationMinProjectMethods(),  // new parameters
      o.highConcentrationThreshold()     // new parameters
    );
  }
}
