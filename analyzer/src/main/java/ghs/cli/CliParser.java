package ghs.analyzer.cli;

import java.nio.file.*;
import java.util.*;

public final class CliParser {

  private CliParser() {}

  public static CliOptions parse(String[] args) {
    Map<String, String> m = toMap(args);

    Path base = Paths.get(m.getOrDefault("base", "cloned_repos"));
    Path out = Paths.get(m.getOrDefault("out", "analysis.jsonl"));

    int maxDepth = getInt(m, "maxDepth", 3);
    boolean pruneLibs = getBool(m, "pruneLibs", true);
    int maxVisited = getInt(m, "maxVisited", 25_000);
    boolean append = getBool(m, "append", false);
    boolean splitByRepo = getBool(m, "splitByRepo", false);
    boolean useJars = getBool(m, "useJars", false);
    int batchSize = getInt(m, "batchSize", 50);

    boolean resume = m.containsKey("resume")
      ? getBool(m, "resume", true)
      : getBoolOpt("RESUME", true);
    boolean resumeReset = m.containsKey("resumeReset")
      ? getBool(m, "resumeReset", false)
      : getBoolOpt("RESUME_RESET", false);

    int maxJars = getInt(m, "maxJars", -1);
    int ignoreJarsIfTestsOver = getInt(m, "ignoreJarsIfTestsOver", -1);
    int batchesPerView = getInt(m, "batchesPerView", 0);

    boolean autoTune = m.containsKey("autoTune")
      ? getBool(m, "autoTune", true)
      : getBoolOpt("AUTO_TUNE", true);
    int bigThr = getIntOpt(m, "BIG_THRESHOLD", "bigThreshold", 1000);
    int hugeThr = getIntOpt(m, "HUGE_THRESHOLD", "hugeThreshold", 5000);
    int autoBatchBig = getIntOpt(m, "AUTO_BATCH_BIG", "autoBatchBig", 200);
    int autoBatchHuge = getIntOpt(m, "AUTO_BATCH_HUGE", "autoBatchHuge", 300);
    int autoVisitedBig = getIntOpt(
      m,
      "AUTO_VISITED_BIG",
      "autoVisitedBig",
      3000
    );
    int autoVisitedHuge = getIntOpt(
      m,
      "AUTO_VISITED_HUGE",
      "autoVisitedHuge",
      2500
    );
    boolean autoFastHeuristic = m.containsKey("autoFastHeuristic")
      ? getBool(m, "autoFastHeuristic", true)
      : getBoolOpt("AUTO_FAST_HEURISTIC", true);

    Optional<Path> onlyFrom = Optional.ofNullable(m.get("onlyFrom"))
      .filter(s -> !s.isBlank())
      .map(Paths::get);

    return new CliOptions(
      base,
      out,
      maxDepth,
      pruneLibs,
      maxVisited,
      append,
      splitByRepo,
      useJars,
      batchSize,
      resume,
      resumeReset,
      maxJars,
      ignoreJarsIfTestsOver,
      batchesPerView,
      autoTune,
      bigThr,
      hugeThr,
      autoBatchBig,
      autoBatchHuge,
      autoVisitedBig,
      autoVisitedHuge,
      autoFastHeuristic,
      onlyFrom
    );
  }

  private static Map<String, String> toMap(String[] args) {
    Map<String, String> m = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i++) {
      String a = args[i];
      if (!a.startsWith("--")) continue;
      String k = a.substring(2);
      String v = (i + 1 < args.length && !args[i + 1].startsWith("--"))
        ? args[++i]
        : "true";
      m.put(k, v);
    }
    return m;
  }

  private static boolean getBool(Map<String, String> m, String k, boolean def) {
    String v = m.get(k);
    if (v == null) return def;
    v = v.trim().toLowerCase(Locale.ROOT);
    return (
      v.isEmpty() ||
      v.equals("1") ||
      v.equals("true") ||
      v.equals("yes") ||
      v.equals("y")
    );
  }

  private static boolean getBoolOpt(String env, boolean def) {
    String v = System.getenv(env);
    if (v == null) return def;
    return getBool(Map.of(env, v), env, def);
  }

  private static int getInt(Map<String, String> m, String k, int def) {
    try {
      return Integer.parseInt(m.getOrDefault(k, String.valueOf(def)).trim());
    } catch (Exception e) {
      return def;
    }
  }

  private static int getIntOpt(
    Map<String, String> m,
    String env,
    String k,
    int def
  ) {
    String v = System.getenv(env);
    if (v != null) try {
      return Integer.parseInt(v.trim());
    } catch (Exception ignored) {}
    return getInt(m, k, def);
  }
}
