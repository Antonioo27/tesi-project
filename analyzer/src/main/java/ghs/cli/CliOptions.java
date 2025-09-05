package ghs.analyzer.cli;


import java.nio.file.Path;
import java.util.*;


public record CliOptions(
Path base,
Path out,
int maxDepth,
boolean pruneLibs,
int maxVisited,
boolean append,
boolean splitByRepo,
boolean useJars,
int batchSize,
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
Optional<Path> onlyFrom
) {}