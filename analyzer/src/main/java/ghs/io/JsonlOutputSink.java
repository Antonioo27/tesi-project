package ghs.analyzer.io;

import ghs.analyzer.model.*;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.json.JSONObject;

public final class JsonlOutputSink implements OutputSink {

  private final BufferedWriter writer;
  private final boolean splitByRepo;
  private final Path outPath;
  private final java.util.Map<String, BufferedWriter> byRepo =
    new java.util.HashMap<>();

  public JsonlOutputSink(AnalysisConfig cfg) throws Exception {
    this.splitByRepo = cfg.splitByRepo();
    this.outPath = Paths.get(cfg.outPath());
    if (!splitByRepo) {
      Path dir = outPath.toAbsolutePath().getParent();
      if (dir != null) Files.createDirectories(dir);
      this.writer = Files.newBufferedWriter(
        outPath,
        StandardCharsets.UTF_8,
        cfg.append()
          ? new OpenOption[] {
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
          }
          : new OpenOption[] {
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
          }
      );
    } else {
      Files.createDirectories(outPath);
      this.writer = null;
    }
  }

  @Override
  public void write(TestRecord r) throws Exception {
    JSONObject row = new JSONObject()
      .put("repo", r.repo())
      .put("module", r.module())
      .put("cfgId", r.cfgId())
      .put("testClass", r.testClass())
      .put("testMethod", r.testMethod())
      .put("focalClass", r.focalClass())
      .put("focalMethod", r.focalMethod())
      .put(
        "cgStats",
        new JSONObject()
          .put("projectCalls", r.cgStats().projectCalls())
          .put("callsToFocalClass", r.cgStats().callsToFocalClass())
          .put(
            "callsToOtherProjectClasses",
            r.cgStats().callsToOtherProjectClasses()
          )
          .put("callsToLibraries", r.cgStats().callsToLibraries())
          .put("uniqueProjectClasses", r.cgStats().uniqueProjectClasses())
          .put("maxDepthVisited", r.cgStats().maxDepthVisited())
      )
      .put("usesMocks", r.usesMocks())
      .put("unit_integration_score", r.unitIntegrationScore());

    if (!splitByRepo) {
      writer.write(row.toString());
      writer.write("\n");
    } else {
      BufferedWriter w = byRepo.computeIfAbsent(r.repo(), repo -> {
        try {
          Path p = outPath.resolve(repo + ".jsonl");
          return Files.newBufferedWriter(
            p,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
          );
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
    if (writer != null) writer.close();
    for (BufferedWriter w : byRepo.values()) try {
      w.close();
    } catch (Exception ignored) {}
  }
}
