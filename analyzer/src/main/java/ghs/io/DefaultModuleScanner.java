package ghs.io;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Scanner di moduli Maven.
 *
 * - Cammina ricorsivamente da baseDir.
 * - Riconosce un modulo se nella directory c'è un 'pom.xml'.
 * - Evita directory rumorose (target, node_modules, ecc.) e symlink.
 * - NON interrompe la discesa quando trova un pom.xml (per non perdere
 * sottomoduli).
 * - Ordina il risultato per riproducibilità.
 *
 * La verifica "il modulo è analizzabile?" (target/classes &
 * target/test-classes)
 * viene fatta in ModuleAnalyzer (responsabilità separata).
 */
public final class DefaultModuleScanner implements ModuleScanner {

  private static final Set<String> IGNORE = Set.of(
      "target", // evita scansionare output Maven
      "build", "dist", "out", // output vari
      ".git", ".hg", ".svn", ".mvn",
      ".idea", ".gradle",
      "node_modules");

  @Override
  public List<Path> findMavenModules(Path baseDir) throws IOException {
    List<Path> modules = new ArrayList<>();

    Files.walkFileTree(baseDir, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        // Chiamato PRIMA di entrare in una directory: qui puoi decidere se scendere o
        // saltare il sottoalbero.
        // Se è symlink o cartella “rumorosa” -> SKIP_SUBTREE; se contiene pom.xml la
        // registriamo ma continuiamo a scendere (aggregator).
        String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
        // Salta symlink e cartelle rumorose
        if (attrs.isSymbolicLink() || IGNORE.contains(name)) {
          return FileVisitResult.SKIP_SUBTREE;
        }
        // Riconosci modulo Maven: presenza di pom.xml
        if (Files.isRegularFile(dir.resolve("pom.xml"))) {
          modules.add(dir);
          // IMPORTANTE: NON fare SKIP_SUBTREE qui, per non perdere moduli figli di un
          // aggregator
        }
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFileFailed(Path file, IOException exc) {
        // Chiamato quando l’accesso a un file/dir fallisce (permessi, path sparito,
        // ecc.).
        // Logghiamo e proseguiamo la visita (CONTINUE) per non fermare la scansione
        // dell’intero albero.
        System.out.println(" (skip path problem) " + file + " -> " + exc.getClass().getSimpleName());
        return FileVisitResult.CONTINUE;
      }
    });

    modules.sort(Comparator.naturalOrder()); // ordine deterministico
    return modules;
  }
}