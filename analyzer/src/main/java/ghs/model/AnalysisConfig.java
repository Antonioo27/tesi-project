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
String onlyFromFile
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
o.onlyFrom().map(java.nio.file.Path::toString).orElse("")
);
}
}