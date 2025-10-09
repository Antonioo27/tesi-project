package ghs.combine;

import ghs.graph.RawTestAnalysis;
import ghs.model.TestRecord;

/**
 * Converte un RawTestAnalysis (dati grezzi) in un TestRecord (dati interpretati).
 * Implementazioni diverse possono sperimentare strategie differenti.
 */
public interface TestResultCombiner {
  TestRecord combine(RawTestAnalysis raw);
}