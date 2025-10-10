package ghs.graph;

import java.util.List;
import ghs.heuristics.HeuristicResult;

/**
 * Dato grezzo prodotto dalla fase di raccolta (nessuna decisione finale).
 * Un futuro Combiner trasformerà questo in TestRecord “arricchito” (focal,
 * score, kind, ecc.).
 */
public record RawTestAnalysis(
                String repo,
                String module,
                String cfgId,
                String testClass,
                String testMethod,
                List<HeuristicResult> heuristicResults // output uniforme delle heuristic
) {
}