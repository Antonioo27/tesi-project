package ghs.model;

import java.util.List;

import ghs.heuristics.HeuristicResult;
import java.nio.file.Path;
import java.util.Set;

/**
 * Versione aggiornata del record finale prodotto dal Combiner.
 */
public record TestRecord(
        String repo,
        Path module,
        String cfgId,
        String testClass,
        String testMethod,
        String focalClass,
        String focalMethod,
        double unitIntegrationScore,
        TestKind testKind,
        double classificationConfidence,
        Set<String> directProjectClasses,
        Set<String> projectProdClassesTouched,
        List<HeuristicResult> heuristicResults) {
}
