package ghs.io;

import ghs.model.ModuleInputs;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Risolutore dei path di classi per un modulo Maven.
 *
 * Responsabilità:
 * - Dato il path del modulo (cartella con pom.xml), costruisce i path
 * canonici Maven di output:
 * - produzione: target/classes
 * - test: target/test-classes
 * - NON fallisce se le directory non esistono: logga un warning e
 * delega a ModuleAnalyzer la decisione di "skippare" il modulo.
 *
 * Note:
 * - Questo componente non legge pom.xml né interpreta configurazioni speciali:
 * segue il convention-over-configuration di Maven.
 * - Se un domani volessimo supportare Gradle/IDEA, qui è il punto giusto per
 * aggiungere fallback (es. build/classes/java/{main,test},
 * out/{production,test}).
 */
public final class DefaultInputResolver implements InputResolver {

  @Override
  public ModuleInputs resolveInputsForModule(Path module) throws IOException {
    // Path canonici Maven: target/classes e target/test-classes
    Path prod = module.resolve("target").resolve("classes");
    Path test = module.resolve("target").resolve("test-classes");

    // Non forziamo l'esistenza: stampiamo solo un avviso.
    // La vera decisione (skip modulo) è nel ModuleAnalyzer, che verifica di nuovo.
    if (!Files.isDirectory(prod)) {
      System.out.println(" (warn) target/classes non trovato");
    }
    if (!Files.isDirectory(test)) {
      System.out.println(" (warn) target/test-classes non trovato");
    }

    // ModuleInputs attualmente espone solo (prodClasses, testClasses).
    // Restituiamo i path anche se mancanti: la validazione è demandata a valle.
    return new ModuleInputs(prod, test);
  }
}
