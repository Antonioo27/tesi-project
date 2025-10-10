package ghs.cli;

import java.nio.file.Path;
import java.util.*;

/**
 * Contenitore immutabile delle opzioni fornite via CLI/ENV.
 *
 * Ruolo nel sistema:
 * - Viene popolato da CliParser.parse(String[]) con default sensati.
 * - È la sorgente di verità dei parametri "di run".
 * - Viene convertito in AnalysisConfig (stringhe+primitivi) per essere
 * passato lungo la pipeline e serializzato con meno attrito.
 *
 * Gruppi di parametri:
 * - IO: base, out, splitByRepo, append
 * - Traversal: maxDepth, maxVisited
 * - Batching/Resume: batchSize, resume, resumeReset, batchesPerView
 * - Autotune: autoTune, bigThr/hugeThr, autoBatch/autoVisited*
 * - Filtro repo: onlyFrom
 * - Robustezza: preflightN, preflightMinHeadroomMb, skipOnOom
 */
public record CliOptions(
                Path base,
                Path out,
                int maxDepth,
                int maxVisited,
                boolean append,
                boolean splitByRepo,
                int batchSize,
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
                Optional<Path> onlyFrom,
                int preflightN,
                int preflightMinHeadroomMb,
                boolean skipOnOom) {
}