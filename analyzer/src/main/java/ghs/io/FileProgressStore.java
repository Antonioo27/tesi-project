package ghs.io;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Persistenza leggera del progresso di analisi per abilitare il "resume".
 *
 * Modello dati:
 * - Un file di testo per modulo+configurazione:
 * target/analyzer-progress-<cfgId>.txt
 * - Ogni riga contiene la chiave di un test già processato:
 * <FQN_TEST_CLASS>#<subsignature>
 *
 * Perché per-configurazione (cfgId)?
 * - Cambiando parametri (maxDepth, maxVisited, batchSize) l'esito può variare;
 * separare i file evita falsi "già fatto".
 *
 * Scelte implementative:
 * - load(): LinkedHashSet → preserva ordine e rimuove duplicati.
 * - append(): crea la directory se mancante, scrive in append in modo semplice.
 * - Error handling "best-effort": log a console e continua (non blocca la
 * pipeline).
 *
 * Nota: non è progettato per accessi concorrenti multi-processo (ok per il
 * nostro use-case).
 */
public final class FileProgressStore implements ProgressStore {

  /** Costruisce il path del file di progresso per il modulo e la cfg corrente. */
  private Path file(Path module, String cfgId) {
    return module
        .resolve("target")
        .resolve("analyzer-progress-" + cfgId + ".txt");
  }

  /**
   * Carica le chiavi già registrate (se il file esiste).
   * 
   * @return insieme ordinato (inserimento) delle chiavi già viste.
   */
  @Override
  public Set<String> load(Path module, String cfgId) {
    Path f = file(module, cfgId);
    try {
      if (!Files.isRegularFile(f))
        return new HashSet<>();
      // LinkedHashSet: mantiene l'ordine originale + evita duplicati in lettura.
      return new LinkedHashSet<>(Files.readAllLines(f, StandardCharsets.UTF_8));
    } catch (Exception e) {
      System.out.println(" (warn) impossibile leggere progress: " + e.getMessage());
      return new HashSet<>();
    }
  }

  /**
   * Appende una chiave (test completato) al file di progresso.
   * Idempotenza pratica: se per qualche motivo la riga venisse scritta due volte,
   * load() de-duplica grazie al LinkedHashSet.
   */
  @Override
  public void append(Path module, String cfgId, String key) {
    Path f = file(module, cfgId);
    try {
      Files.createDirectories(f.getParent()); // assicura 'target/'
      Files.writeString(
          f,
          key + System.lineSeparator(),
          StandardCharsets.UTF_8,
          // Se il file esiste → APPEND, altrimenti → CREATE
          Files.exists(f) ? StandardOpenOption.APPEND : StandardOpenOption.CREATE);
    } catch (Exception e) {
      System.out.println(" (warn) progress append fallito: " + e.getMessage());
    }
  }

  /**
   * Resetta il progresso per la configurazione corrente (usato con
   * --resumeReset).
   * Se il file non esiste, non è un errore (deleteIfExists).
   */
  @Override
  public void reset(Path module, String cfgId) {
    try {
      Files.deleteIfExists(file(module, cfgId));
    } catch (Exception ignored) {
    }
  }
}
