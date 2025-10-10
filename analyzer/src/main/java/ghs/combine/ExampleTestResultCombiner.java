package ghs.combine;

import ghs.graph.RawTestAnalysis;
import ghs.heuristics.Candidate;
import ghs.heuristics.HeuristicResult;
import ghs.model.TestKind;
import ghs.model.TestRecord;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * Combiner senza CgStats.
 *
 * - Fonde segnali: assertion_focal_producers (forte) + name-based (debole) +
 * direct_calls (debole)
 * - Calcola interactionScore da direct_calls_metrics; in assenza usa un
 * fallback costruito
 * dalle asserzioni (distribution di assertion_focal_producers).
 * - Penalità/boost in base a mock usage.
 * - Classificazione UNIT/INTEGRATION senza cg stats (usa
 * uniqueClassCount/totalMethodCalls).
 *
 * Nota: per ridurre la ridondanza, NON utilizziamo più directProjectClasses nel
 * TestRecord.
 * Tutte le informazioni necessarie arrivano dalle euristiche.
 */
public final class ExampleTestResultCombiner implements TestResultCombiner {

  private static final double FALLBACK_MOCK_PENALTY = 0.15;

  @Override
  public TestRecord combine(RawTestAnalysis raw) {

    // 1) Focal signals
    String focalClass = detectFusedFocalClass(raw.heuristicResults());
    String focalMethod = detectFocalMethod(raw.heuristicResults());

    // 2) Metriche "dirette" dal call graph (livello 1). Se non disponibili, usiamo
    // il fallback dalle asserzioni.
    DirectMetrics dm = extractDirectCallsMetrics(raw.heuristicResults());
    if (dm == null) {
      dm = fallbackFromProducers(raw.heuristicResults()); // <-- nuovo fallback che ricostruisce
                                                          // total/unique/concentration
    }

    // 3) Interaction score (normalizzato 0..1)
    // Base: volume (totalMethodCalls) + piccolo boost/penalty in base alla
    // concentrazione per classe.
    Double interactionScore;
    if (dm != null && dm.totalMethodCalls != null) {
      double baseVol = Math.min(1.0, dm.totalMethodCalls / 16.0);
      double focusBoost = (dm.classConcentration != null)
          ? (dm.classConcentration >= 0.75 ? 0.10 : (dm.classConcentration <= 0.35 ? -0.08 : 0.0))
          : 0.0;
      interactionScore = clamp01(baseVol + focusBoost);
    } else {
      // Davvero nessuna metrica disponibile (né direct_calls né fallback asserzioni)
      interactionScore = 0.00;
    }

    // 4) Mock usage: penalità e boost (se non ci sono mock, premiamo spread/volume)
    MockStats ms = extractMockStats(raw.heuristicResults());

    double mockPenalty = computeMockPenalty(ms);
    if (mockPenalty > 0) {
      interactionScore = Math.max(0.0, interactionScore - mockPenalty);
    }

    // Boost "soft" in assenza di mock: più classi e più volume => più probabile
    // integrazione
    if (!ms.hasMocks) {
      int unique = dm != null && dm.uniqueClassCount != null ? dm.uniqueClassCount : 0;
      int total = dm != null && dm.totalMethodCalls != null ? dm.totalMethodCalls : 0;
      double spreadBoost = Math.min(0.15, Math.max(0, unique - 1) * 0.05); // fino a +0.15
      double volumeBoost = Math.min(0.10, Math.max(0, total - 3) * 0.02); // fino a +0.10
      interactionScore = clamp01(interactionScore + spreadBoost + volumeBoost);
    }

    // Pavimento per stabilità numerica
    interactionScore = Math.max(interactionScore, 0.05);

    // 5) Classificazione finale
    int unique = dm != null && dm.uniqueClassCount != null ? dm.uniqueClassCount : 0;
    int total = dm != null && dm.totalMethodCalls != null ? dm.totalMethodCalls : 0;
    TestKind kind = classify(interactionScore, unique, total, ms);
    double conf = classificationConfidence(interactionScore, kind);

    // 6) Output: TestRecord compatibile con la tua firma "snellita"
    return new TestRecord(
        raw.repo(),
        Path.of(raw.module()),
        raw.cfgId(),
        raw.testClass(),
        raw.testMethod(),
        focalClass,
        focalMethod,
        interactionScore,
        kind,
        conf,
        raw.heuristicResults());
  }

  // -------------------- Fusion focal class --------------------

  /**
   * Fonde i segnali di classe focale da:
   * - Producers (assertion_focal_producers) [forte]
   * - Name-based [debole, ma utile]
   * - Direct-calls share [debole]
   *
   * In caso di pari merito: preferisce classi viste nei producers, poi la
   * name-based esistente, poi alfabetico.
   */
  private String detectFusedFocalClass(List<HeuristicResult> results) {
    final double W_PROD = 0.65;
    final double W_NAME = 0.25;
    final double W_DIR = 0.10;

    Map<String, Double> score = new java.util.LinkedHashMap<>();
    Set<String> seenInProducers = new java.util.LinkedHashSet<>();
    String nameBasedClass = null;
    boolean nameBasedExists = false;

    // 1) Producers
    HeuristicResult hrProd = results.stream()
        .filter(r -> "assertion_focal_producers".equals(r.metricId()))
        .findFirst().orElse(null);

    if (hrProd != null) {
      if (!hrProd.candidates().isEmpty()) {
        for (var c : hrProd.candidates()) {
          String m = String.valueOf(c.value()); // esempio: "<com.example.Foo: void bar(int)>"
          String cls = classFromMethodSigString(m);
          if (cls != null) {
            score.merge(cls, W_PROD * c.confidence(), Double::sum);
            seenInProducers.add(cls);
          }
        }
      } else {
        // Se non ci sono candidati ma esiste la distribution per metodo -> ripartisci
        // peso per classe
        Object distObj = hrProd.meta().get("distribution");
        if (distObj instanceof Map<?, ?> dist) {
          Map<String, Long> byClass = new java.util.LinkedHashMap<>();
          long tot = 0L;
          for (var e : dist.entrySet()) {
            String m = String.valueOf(e.getKey());
            String cls = classFromMethodSigString(m);
            long occ = ((Number) e.getValue()).longValue();
            if (cls != null) {
              byClass.merge(cls, occ, Long::sum);
              tot += occ;
            }
          }
          if (tot > 0) {
            for (var e : byClass.entrySet()) {
              double share = e.getValue() / (double) tot;
              score.merge(e.getKey(), W_PROD * share, Double::sum);
              seenInProducers.add(e.getKey());
            }
          }
        }
      }
    }

    // 2) Name-based
    HeuristicResult hrName = results.stream()
        .filter(r -> "focal-class-candidates".equals(r.metricId()))
        .findFirst()
        .orElse(null);

    if (hrName != null && !hrName.candidates().isEmpty()) {
      var c = hrName.candidates().get(0);
      Object v = c.value();
      if (v != null) {
        nameBasedClass = v.toString();
        Object ex = c.evidence().get("existsInProject");
        nameBasedExists = (ex instanceof Boolean b) && b;
        double weight = nameBasedExists ? W_NAME : (W_NAME * 0.2);
        score.merge(nameBasedClass, weight * c.confidence(), Double::sum);
      }
    }

    // 3) Direct-calls share
    DirectMetrics dm = extractDirectCallsMetrics(results);
    if (dm == null) {
      dm = fallbackFromProducers(results); // anche qui, se serve, prova il fallback per dare un minimo di share
    }
    if (dm != null && dm.perClass != null && dm.totalMethodCalls != null && dm.totalMethodCalls > 0) {
      double total = dm.totalMethodCalls.doubleValue();
      for (var e : dm.perClass.entrySet()) {
        String cls = e.getKey();
        double cnt = e.getValue().doubleValue();
        double share = cnt / total;
        score.merge(cls, W_DIR * share, Double::sum);
      }
    }

    if (score.isEmpty())
      return null;

    double best = score.values().stream().mapToDouble(d -> d).max().orElse(Double.NEGATIVE_INFINITY);
    List<String> top = score.entrySet().stream()
        .filter(e -> Math.abs(e.getValue() - best) < 1e-9)
        .map(Map.Entry::getKey)
        .toList();

    // Tie-break: 1) producers 2) name-based (esistente) 3) alfabetico
    List<String> t1 = top.stream().filter(seenInProducers::contains).toList();
    if (!t1.isEmpty())
      top = t1;
    if (nameBasedExists && top.contains(nameBasedClass))
      return nameBasedClass;
    return top.stream().sorted().findFirst().orElse(null);
  }

  /**
   * Estrae la FQN della classe dalla stringa di signature del metodo
   * prevista nei Candidate dei producers (es. "<com.example.Foo: void
   * bar(int)>").
   */
  private static String classFromMethodSigString(String s) {
    int lt = s.indexOf('<');
    int colon = s.indexOf(':');
    if (colon < 0)
      return null;
    int start = (lt >= 0 && lt + 1 < colon) ? lt + 1 : 0;
    return s.substring(start, colon).trim();
  }

  /**
   * Stima del metodo focale: prende il primo candidato da una metrica
   * "method-like"
   * (in genere dall'euristica dei producers).
   */
  private String detectFocalMethod(List<HeuristicResult> results) {
    HeuristicResult hr = results.stream()
        .filter(r -> {
          String id = r.metricId().toLowerCase();
          return id.contains("method")
              || id.contains("assertion_focal_producers")
              || id.contains("focal_producer");
        })
        .findFirst()
        .orElse(null);
    if (hr == null || hr.candidates().isEmpty())
      return null;
    Candidate<?> c = hr.candidates().get(0);
    return c.value() == null ? null : c.value().toString();
  }

  // -------------------- Metrics extraction --------------------

  /**
   * Pacchetto di metriche che uniforma sia i dati reali (direct_calls_metrics)
   * sia il fallback ricostruito dalle asserzioni.
   */
  private static final class DirectMetrics {
    final Integer totalMethodCalls;
    final Integer uniqueClassCount;
    final Double classConcentration;
    final Map<String, Number> perClass;

    DirectMetrics(Integer t, Integer u, Double c, Map<String, Number> pc) {
      this.totalMethodCalls = t;
      this.uniqueClassCount = u;
      this.classConcentration = c;
      this.perClass = pc;
    }
  }

  /**
   * Estrae le metriche da "direct_calls_metrics".
   */
  @SuppressWarnings("unchecked")
  private DirectMetrics extractDirectCallsMetrics(List<HeuristicResult> results) {
    HeuristicResult hr = results.stream()
        .filter(r -> "direct_calls_metrics".equals(r.metricId()))
        .findFirst()
        .orElse(null);
    if (hr == null || hr.candidates().isEmpty())
      return null;

    Map<String, Object> ev = hr.candidates().get(0).evidence();
    Integer total = ev.get("totalMethodCalls") instanceof Number n ? n.intValue() : null;
    Integer unique = ev.get("uniqueClassCount") instanceof Number n ? n.intValue() : null;
    Double conc = ev.get("classConcentration") instanceof Number n ? n.doubleValue() : null;
    Map<String, Number> per = ev.get("perClass") instanceof Map<?, ?> m
        ? (Map<String, Number>) (Map<?, ?>) m
        : null;

    return new DirectMetrics(total, unique, conc, per);
  }

  /**
   * Fallback: se non abbiamo "direct_calls_metrics", ricostruiamo una vista
   * simile
   * usando la distribution di "assertion_focal_producers".
   *
   * Logica:
   * - distribution: Map<MethodSignatureString, Long>
   * - raggruppa per classe (estraendola dalla firma del metodo)
   * - total = somma occorrenze
   * - unique = numero classi
   * - concentration = max(byClass.values)/total (come nella direct metric)
   */
  @SuppressWarnings("unchecked")
  private DirectMetrics fallbackFromProducers(List<HeuristicResult> results) {
    HeuristicResult hrProd = results.stream()
        .filter(r -> "assertion_focal_producers".equals(r.metricId()))
        .findFirst().orElse(null);
    if (hrProd == null)
      return null;

    Object distObj = hrProd.meta().get("distribution");
    if (!(distObj instanceof Map<?, ?> dist))
      return null;

    Map<String, Long> byClass = new java.util.LinkedHashMap<>();
    long total = 0L;

    for (var e : dist.entrySet()) {
      String m = String.valueOf(e.getKey()); // "<FQN: ...>"
      String cls = classFromMethodSigString(m); // estrae FQN della classe
      if (cls == null)
        continue;
      long occ = ((Number) e.getValue()).longValue();
      byClass.merge(cls, occ, Long::sum);
      total += occ;
    }
    if (total <= 0)
      return null;

    int unique = byClass.size();
    double concentration = (unique <= 1)
        ? 1.0
        : byClass.values().stream().mapToLong(Long::longValue).max().orElse(0L) / (double) total;

    Map<String, Number> per = new java.util.LinkedHashMap<>();
    for (var e : byClass.entrySet())
      per.put(e.getKey(), e.getValue());

    return new DirectMetrics((int) total, unique, concentration, per);
  }

  // -------------------- Mock usage --------------------

  private static final class MockStats {
    final boolean hasMocks;
    final int mockCount;
    final int totalInteractions;
    final int stubbings;
    final int verifications;
    final int verifyInvocations;

    MockStats(boolean h, int mc, int ti, int st, int v, int vi) {
      this.hasMocks = h;
      this.mockCount = mc;
      this.totalInteractions = ti;
      this.stubbings = st;
      this.verifications = v;
      this.verifyInvocations = vi;
    }

    double verificationRatio() {
      return totalInteractions > 0 ? (double) verifications / (double) totalInteractions : 0.0;
    }
  }

  /**
   * Estrae il riepilogo d'uso dei mock dall'euristica "mock_usage".
   * Aggrega verifications/stubbings/verifyInvocations sommando i candidati.
   */
  private MockStats extractMockStats(List<HeuristicResult> results) {
    HeuristicResult hr = results.stream()
        .filter(r -> "mock_usage".equals(r.metricId()))
        .findFirst()
        .orElse(null);
    if (hr == null)
      return new MockStats(false, 0, 0, 0, 0, 0);

    int mockCount = ((Number) hr.meta().getOrDefault("mockCount", 0)).intValue();
    int totalInteractions = ((Number) hr.meta().getOrDefault("totalInteractions", 0)).intValue();

    int stubbings = 0, verifications = 0, verifyInvocations = 0;
    for (Candidate<?> c : hr.candidates()) {
      Object s = c.evidence().get("stubbings");
      Object v = c.evidence().get("verifications");
      Object vi = c.evidence().get("verifyInvocations");
      if (s instanceof Number n)
        stubbings += n.intValue();
      if (v instanceof Number n)
        verifications += n.intValue();
      if (vi instanceof Number n)
        verifyInvocations += n.intValue();
    }
    return new MockStats(mockCount > 0, mockCount, totalInteractions, stubbings, verifications, verifyInvocations);
  }

  // -------------------- Score & classification --------------------

  /**
   * Penalità per uso di mock:
   * - più mock => più penalità (basePenalty cresce con mockCount)
   * - se la quota di verifications è alta, riduciamo la penalità (verifyFactor <
   * 1)
   * - clamp massimo 0.30
   */
  private double computeMockPenalty(MockStats ms) {
    if (!ms.hasMocks || ms.mockCount <= 0)
      return 0.0;
    try {
      double verificationRatio = ms.verificationRatio();
      double basePenalty = 0.10 + 0.035 * Math.max(0, ms.mockCount - 1);
      double verifyFactor = (verificationRatio >= 0.45) ? 0.55
          : (verificationRatio >= 0.25) ? 0.75
              : 1.0;
      double computed = basePenalty * verifyFactor;
      return Math.min(0.30, Math.max(0.0, computed));
    } catch (Exception e) {
      return FALLBACK_MOCK_PENALTY;
    }
  }

  /**
   * Regole di classificazione:
   * - Mock “forti” + bassa interazione/ampiezza => UNIT
   * - Nessun mock + ampiezza/volume sufficienti => INTEGRATION
   * - Altrimenti, fallback su soglie di unique/norm
   */
  private TestKind classify(double norm, int unique, int total, MockStats ms) {
    // Unit forte: presenza mock + stubbing/verifiche
    if (ms.hasMocks && (ms.stubbings > 0 || ms.verifications > 0 || ms.verifyInvocations > 0)) {
      if (unique <= 3 && norm < 0.70)
        return TestKind.UNIT;
    }

    // Integrazione forte: nessun mock + ampiezza/volume
    if (!ms.hasMocks) {
      if (unique >= 3 || total >= 6)
        return TestKind.INTEGRATION;
      if (unique >= 2 && total >= 4 && norm >= 0.30)
        return TestKind.INTEGRATION;
    }

    // Fallback
    if (unique <= 1 && norm < 0.50)
      return TestKind.UNIT;
    if (unique <= 2 && norm < 0.60)
      return TestKind.UNIT;
    return TestKind.INTEGRATION;
  }

  /**
   * Confidenza della classificazione:
   * - più il punteggio norm è vicino al "centro" del tipo (0.35=UNIT,
   * 0.65=INTEGRATION),
   * più la confidenza cresce.
   * - range clampato in [0.5..0.95].
   */
  private double classificationConfidence(double norm, TestKind kind) {
    double center = (kind == TestKind.UNIT) ? 0.35 : 0.65;
    double dist = 1.0 - Math.min(1.0, Math.abs(norm - center) * 2.0);
    double base = 0.55 + 0.35 * dist; // [0.55..0.90]
    return round3(Math.max(0.5, Math.min(0.95, base)));
  }

  // -------------------- Utils --------------------
  private double clamp01(double d) {
    return d < 0 ? 0 : (d > 1 ? 1 : d);
  }

  private double round3(double d) {
    return Math.round(d * 1000.0) / 1000.0;
  }
}
