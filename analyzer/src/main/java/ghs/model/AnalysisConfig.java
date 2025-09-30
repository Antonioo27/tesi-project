package ghs.model;

import ghs.cli.CliOptions;

/**
 * Configurazione immutabile usata dall'app durante runtime.
 * Viene costruita tramite AnalysisConfig.from(CliOptions).
 *
 * Nota: contiene molti parametri — documentare ogni campo nella tesi.
 */
public record AnalysisConfig(
    String baseDir,
    String outPath,
    int maxDepth,
    int maxVisited,
    int batchSize,
    boolean splitByRepo,
    boolean append,
    boolean resume,
    boolean resumeReset,
    int batchesPerView,
    boolean autoTune,
    int bigThr,
    int hugeThr,
    int autoBatchBig,
    int autoBatchHuge,
    int autoVisitedBig,
    int autoVisitedHuge,
    String onlyFromFile,
    int preflightN,
    int preflightMinHeadroomMb,
    boolean skipOnOom,
    int integrationMinProjectClasses,
    int integrationMinProjectMethods,
    double highConcentrationThreshold) {
  public static AnalysisConfig from(CliOptions o) {
    return new AnalysisConfig(
        o.base().toString(),
        o.out().toString(),
        o.maxDepth(),
        o.maxVisited(),
        o.batchSize(),
        o.splitByRepo(),
        o.append(),
        o.resume(),
        o.resumeReset(),
        o.batchesPerView(),
        o.autoTune(),
        o.bigThr(),
        o.hugeThr(),
        o.autoBatchBig(),
        o.autoBatchHuge(),
        o.autoVisitedBig(),
        o.autoVisitedHuge(),
        o.onlyFrom().map(java.nio.file.Path::toString).orElse(""),
        o.preflightN(),
        o.preflightMinHeadroomMb(),
        o.skipOnOom(),
        o.integrationMinProjectClasses(),
        o.integrationMinProjectMethods(),
        o.highConcentrationThreshold());
  }
}