package ghs.io;

import ghs.model.*;
import ghs.model.TestRecord;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.json.JSONObject;

/**
 * Output sink che serializza i risultati in formato JSON Lines (JSONL).
 *
 * Modalità:
 * - File unico: --splitByRepo=false → scrive su cfg.outPath()
 * - Split per repo:--splitByRepo=true → crea una dir cfg.outPath()/ e scrive
 * out/<repo>.jsonl
 *
 * Scelte:
 * - JSONL è append-friendly e facilmente processabile a streaming.
 * - In modalità split, i writer sono creati "lazy" per ciascuna repo alla prima
 * write.
 * - La chiusura (close) garantisce flush e rilascio di tutte le risorse.
 */
public final class JsonlOutputSink implements OutputSink {

  /** Writer unico quando non si splitta per repo; null in modalità split. */
  private final BufferedWriter writer;
  /** Flag che indica la modalità di output. */
  private final boolean splitByRepo;
  /** Percorso del file (file unico) o directory (split). */
  private final Path outPath;
  // copia di cfg.append() da usare anche in write(...)
  private final boolean appendMode;
  /** Writer per repo in modalità split (creati on-demand). */
  private final java.util.Map<String, BufferedWriter> byRepo = new java.util.HashMap<>();

  public JsonlOutputSink(AnalysisConfig cfg) throws Exception {
    this.splitByRepo = cfg.splitByRepo();
    this.outPath = Paths.get(cfg.outPath());
    this.appendMode = cfg.append();

    if (!splitByRepo) {
      // Modalità file unico: assicurati che esista la directory padre di outPath
      Path dir = outPath.toAbsolutePath().getParent();
      if (dir != null)
        Files.createDirectories(dir);

      // Apertura writer con policy di append o truncate in base al flag --append
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
      // Modalità split: outPath è una directory contenitore per i file per-repo
      Files.createDirectories(outPath);
      this.writer = null; // si useranno i writer in byRepo
    }
  }

  @Override
  public void write(TestRecord r) throws Exception {
    // Costruzione della riga JSON: include anche un oggetto annidato 'cgStats'
    JSONObject row = new JSONObject()
        .put("repo", r.repo())
        .put("module", r.module())
        .put("cfgId", r.cfgId())
        .put("testClass", r.testClass())
        .put("testMethod", r.testMethod())
        .put("focalClass", r.focalClass())
        .put("focalMethod", r.focalMethod())
        .put("cgStats", new JSONObject()
            .put("projectCalls", r.cgStats().projectCalls())
            .put("callsToFocalClass", r.cgStats().callsToFocalClass())
            .put("callsToOtherProjectClasses", r.cgStats().callsToOtherProjectClasses())
            .put("callsToLibraries", r.cgStats().callsToLibraries())
            .put("uniqueProjectClasses", r.cgStats().uniqueProjectClasses())
            .put("maxDepthVisited", r.cgStats().maxDepthVisited()))
        .put("usesMocks", r.usesMocks())
        .put("unit_integration_score", r.unitIntegrationScore())
        .put("testKind", r.testKind()) // enum → serializzato come stringa (es. "UNIT")
        .put("directProjectClasses", new org.json.JSONArray(r.directProjectClasses()));

    if (!splitByRepo) {
      // Scrittura su file unico
      writer.write(row.toString());
      writer.write("\n"); // JSON Lines = un record per riga
    } else {
      // Scrittura per-repo: ottieni o crea il writer per questa repo
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
    // Chiudi il writer unico, se presente
    if (writer != null)
      writer.close();

    // Chiudi tutti i writer per repo (split)
    for (BufferedWriter w : byRepo.values()) {
      try {
        w.close();
      } catch (Exception ignored) {
        // best-effort: continuiamo a chiuderli tutti
      }
    }
  }
}
