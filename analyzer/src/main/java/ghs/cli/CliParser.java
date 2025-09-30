package ghs.cli;

import java.nio.file.*;
import java.util.*;

/**
 * Parser minimale per argomenti CLI in forma:
 * --chiave valore (es. --base /path/to/repos)
 * --flag (interpreta "true")
 *
 * Limiti noti:
 * - NON supporta '--k=v' (si può estendere in toMap).
 * - NON supporta flag negativi (es. --no-resume).
 * - Per alcune opzioni legge anche ENV; la precedenza CLI↔ENV non è uniforme
 * (volutamente): per certi parametri prevale CLI, per altri prevale ENV.
 */
public final class CliParser {

  private CliParser() {
  }

  /** Punto di ingresso del parsing CLI → CliOptions (immutabile). */
  public static CliOptions parse(String[] args) {
    // 1) Tokenizza gli argomenti in mappa chiave→valore.
    // Esempi:
    // --base /x/y → m["base"] = "/x/y"
    // --resume → m["resume"] = "true"
    Map<String, String> m = toMap(args);

    // 2) Parametri base (con default ragionevoli)
    Path base = Paths.get(m.getOrDefault("base", "cloned_repos"));
    Path out = Paths.get(m.getOrDefault("out", "analysis.jsonl"));

    int maxDepth = getInt(m, "maxDepth", 3);
    int maxVisited = getInt(m, "maxVisited", 25_000);
    boolean append = getBool(m, "append", false);
    boolean splitByRepo = getBool(m, "splitByRepo", false);
    int batchSize = getInt(m, "batchSize", 50);

    int preflightN = getInt(m, "preflightN", 5);
    int preflightMinHeadroomMb = getInt(m, "preflightMinHeadroomMb", 1500);
    boolean skipOnOom = getBool(m, "skipOnOom", true);

    // 3) Soglie per la classificazione dei test (UNIT/INTEGRATION)
    int integrationMinProjectClasses = getInt(m, "integrationMinProjectClasses", 2);
    int integrationMinProjectMethods = getInt(m, "integrationMinProjectMethods", 6);
    double highConcentrationThreshold = getDouble(m, "highConcentrationThreshold", 0.8);

    // 4) Flag con priorità CLI → ENV (se CLI presente, vince la CLI)
    // Nota: 'resume' di default è true; 'resumeReset' di default è false.
    boolean resume = m.containsKey("resume")
        ? getBool(m, "resume", true) // CLI presente → usa CLI
        : getBoolOpt("RESUME", true); // CLI assente → ENV o default

    boolean resumeReset = m.containsKey("resumeReset")
        ? getBool(m, "resumeReset", false) // CLI presente → usa CLI
        : getBoolOpt("RESUME_RESET", false); // CLI assente → ENV o default

    int batchesPerView = getInt(m, "batchesPerView", 0);

    boolean autoTune = m.containsKey("autoTune")
        ? getBool(m, "autoTune", true) // CLI presente → usa CLI
        : getBoolOpt("AUTO_TUNE", true); // CLI assente → ENV o default

    // 5) Soglie di autotuning con priorità ENV → CLI (ENV ha precedenza qui)
    // Esempio: BIG_THRESHOLD in ENV vince su --bigThreshold in CLI.
    int bigThr = getIntOpt(m, "BIG_THRESHOLD", "bigThreshold", 1000);
    int hugeThr = getIntOpt(m, "HUGE_THRESHOLD", "hugeThreshold", 5000);
    int autoBatchBig = getIntOpt(m, "AUTO_BATCH_BIG", "autoBatchBig", 200);
    int autoBatchHuge = getIntOpt(m, "AUTO_BATCH_HUGE", "autoBatchHuge", 300);
    int autoVisitedBig = getIntOpt(m, "AUTO_VISITED_BIG", "autoVisitedBig", 3000);
    int autoVisitedHuge = getIntOpt(m, "AUTO_VISITED_HUGE", "autoVisitedHuge", 2500);

    // 6) Filtro opzionale 'onlyFrom' (file con lista di repo da includere)
    Optional<Path> onlyFrom = Optional.ofNullable(m.get("onlyFrom"))
        .filter(s -> !s.isBlank())
        .map(Paths::get);

    // 7) Costruzione dell'oggetto immutabile con tutti i parametri
    return new CliOptions(
        base,
        out,
        maxDepth,
        maxVisited,
        append,
        splitByRepo,
        batchSize,
        resume,
        resumeReset,
        batchesPerView,
        autoTune,
        bigThr,
        hugeThr,
        autoBatchBig,
        autoBatchHuge,
        autoVisitedBig,
        autoVisitedHuge,
        onlyFrom,
        preflightN,
        preflightMinHeadroomMb,
        skipOnOom,
        integrationMinProjectClasses,
        integrationMinProjectMethods,
        highConcentrationThreshold);
  }

  // ====== Helpers ======

  /**
   * Converte la sequenza di argomenti in una mappa chiave→valore:
   * --k v → m.put("k","v")
   * --flag → m.put("flag","true")
   *
   * Limitazioni:
   * - NON gestisce "--k=v".
   * - Qualsiasi token che non inizia con "--" viene ignorato.
   */
  private static Map<String, String> toMap(String[] args) {
    Map<String, String> m = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i++) {
      String a = args[i];
      if (!a.startsWith("--"))
        continue;
      String k = a.substring(2);
      String v = (i + 1 < args.length && !args[i + 1].startsWith("--"))
          ? args[++i] // prende il token successivo come "valore"
          : "true"; // flag senza valore → "true"
      m.put(k, v);
    }
    return m;
  }

  /**
   * Parsing booleano tollerante: "", "1", "true", "yes", "y" → true; altrimenti
   * false.
   */
  private static boolean getBool(Map<String, String> m, String k, boolean def) {
    String v = m.get(k);
    if (v == null)
      return def;
    v = v.trim().toLowerCase(Locale.ROOT);
    return v.isEmpty() || v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("y");
  }

  /** Legge boolean da ENV (se non presente, torna def). */
  private static boolean getBoolOpt(String env, boolean def) {
    String v = System.getenv(env);
    if (v == null)
      return def;
    // riusa la logica di getBool componendo una mini-mappa fittizia
    return getBool(Map.of(env, v), env, def);
  }

  /** Parsing int con default; valori non numerici → default. */
  private static int getInt(Map<String, String> m, String k, int def) {
    try {
      return Integer.parseInt(m.getOrDefault(k, String.valueOf(def)).trim());
    } catch (Exception e) {
      return def;
    }
  }

  /**
   * Come getInt ma precedenza ENV → CLI:
   * - se ENV 'env' è presente e parseabile → lo usa
   * - altrimenti usa CLI 'k' o default
   */
  private static int getIntOpt(Map<String, String> m, String env, String k, int def) {
    String v = System.getenv(env);
    if (v != null) {
      try {
        return Integer.parseInt(v.trim());
      } catch (Exception ignored) {
      }
    }
    return getInt(m, k, def);
  }

  /** Parsing double con default; valori non numerici → default. */
  private static double getDouble(Map<String, String> m, String k, double def) {
    try {
      return Double.parseDouble(m.getOrDefault(k, String.valueOf(def)).trim());
    } catch (Exception e) {
      return def;
    }
  }
}
