package ghs.io;

import ghs.model.*;
import ghs.heuristics.HeuristicResult;
import ghs.heuristics.Candidate;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * Output JSON Lines semplificato:
 * - Campi principali test (repo, module, testClass, testMethod)
 * - Classificazione (kind, score, confidence)
 * - Focal (class, method)
 * - Tutte le euristiche con metricId, meta, candidates
 * (value/confidence/evidence)
 *
 * RIMOSSI come “superflui”:
 * - cfgId
 * - Dettagli granulari cgStats
 * - Qualsiasi duplicazione di "direct calls": i dati sono già esposti tramite
 * l’euristica "direct_calls_metrics" in "heuristics".
 */
public final class JsonlOutputSink implements OutputSink {

  private final BufferedWriter writer;
  private final boolean splitByRepo;
  private final Path outPath;
  private final boolean appendMode;
  private final java.util.Map<String, BufferedWriter> byRepo = new java.util.HashMap<>();

  public JsonlOutputSink(AnalysisConfig cfg) throws Exception {
    this.splitByRepo = cfg.splitByRepo();
    this.outPath = Paths.get(cfg.outPath());
    this.appendMode = cfg.append();

    if (!splitByRepo) {
      Path dir = outPath.toAbsolutePath().getParent();
      if (dir != null)
        Files.createDirectories(dir);
      this.writer = Files.newBufferedWriter(
          outPath,
          StandardCharsets.UTF_8,
          appendMode
              ? new OpenOption[] {
                  StandardOpenOption.CREATE,
                  StandardOpenOption.WRITE,
                  StandardOpenOption.APPEND
              }
              : new OpenOption[] {
                  StandardOpenOption.CREATE,
                  StandardOpenOption.TRUNCATE_EXISTING,
                  StandardOpenOption.WRITE
              });
    } else {
      Files.createDirectories(outPath);
      this.writer = null;
    }
  }

  @Override
  public void write(TestRecord r) throws Exception {

    // Oggetto classificazione compatto
    JSONObject classification = new JSONObject()
        .put("kind", r.testKind().name())
        .put("score", r.unitIntegrationScore())
        .put("confidence", r.classificationConfidence());

    // Oggetto focal
    JSONObject focal = new JSONObject()
        .put("class", r.focalClass() == null ? JSONObject.NULL : r.focalClass())
        .put("method", r.focalMethod() == null ? JSONObject.NULL : r.focalMethod());

    // Heuristics array (include anche "direct_calls_metrics" se presente)
    JSONArray heuristicsArr = new JSONArray();
    if (r.heuristicResults() != null) {
      for (HeuristicResult hr : r.heuristicResults()) {
        JSONArray candArr = new JSONArray();
        for (Candidate<?> c : hr.candidates()) {
          candArr.put(new JSONObject()
              .put("value", c.value() == null ? JSONObject.NULL : c.value().toString())
              .put("confidence", c.confidence())
              .put("rationale", c.rationale())
              .put("evidence", new JSONObject(c.evidence() == null ? Map.of() : c.evidence())));
        }
        heuristicsArr.put(new JSONObject()
            .put("heuristicId", hr.heuristicId())
            .put("metricId", hr.metricId())
            .put("meta", new JSONObject(hr.meta() == null ? Map.of() : hr.meta()))
            .put("candidates", candArr));
      }
    }

    JSONObject row = new JSONObject()
        .put("repo", r.repo())
        .put("module", r.module().toString())
        .put("testClass", r.testClass())
        .put("testMethod", r.testMethod())
        .put("classification", classification)
        .put("focal", focal)
        .put("heuristics", heuristicsArr);

    if (!splitByRepo) {
      writer.write(row.toString());
      writer.write("\n");
    } else {
      BufferedWriter w = byRepo.computeIfAbsent(r.repo(), repo -> {
        try {
          Path p = outPath.resolve(repo + ".jsonl");
          if (appendMode) {
            return Files.newBufferedWriter(
                p, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
          } else {
            return Files.newBufferedWriter(
                p, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
          }
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      w.write(row.toString());
      w.write("\n");
    }
  }

  @Override
  public void close() throws Exception {
    if (writer != null)
      writer.close();
    for (BufferedWriter w : byRepo.values()) {
      try {
        w.close();
      } catch (Exception ignored) {
      }
    }
  }
}
