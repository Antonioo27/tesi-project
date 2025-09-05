package ghs.analyzer.io;

import ghs.analyzer.model.ModuleInputs;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public final class DefaultInputResolver implements InputResolver {

  @Override
  public ModuleInputs resolveInputsForModule(Path module) throws IOException {
    Path prod = module.resolve("target").resolve("classes");
    Path test = module.resolve("target").resolve("test-classes");

    Set<Path> jarSet = new LinkedHashSet<>();
    Path cp = module.resolve("target").resolve("classpath.txt");
    if (Files.isRegularFile(cp)) {
      for (String line : Files.readAllLines(cp, StandardCharsets.UTF_8)) {
        for (String raw : line.split(
          java.util.regex.Pattern.quote(File.pathSeparator)
        )) {
          String entry = raw.trim().replace("\"", "");
          if (!entry.endsWith(".jar")) continue;
          Path p = Paths.get(entry);
          if (!p.isAbsolute()) p = module
            .resolve(p)
            .toAbsolutePath()
            .normalize();
          if (Files.isRegularFile(p)) jarSet.add(p);
        }
      }
      System.out.println(" dipendenze (jar): " + jarSet.size());
    } else {
      System.out.println(
        " (nota) nessun classpath.txt trovato , si procede lo stesso."
      );
      Path depDir = module.resolve("target").resolve("dependency");
      if (Files.isDirectory(depDir)) {
        try (Stream<Path> s = Files.list(depDir)) {
          s.filter(p -> p.toString().endsWith(".jar")).forEach(jarSet::add);
        }
        if (!jarSet.isEmpty()) System.out.println(
          " dipendenze (jar) trovate in target/dependency: " + jarSet.size()
        );
      }
    }
    return new ModuleInputs(prod, test, new ArrayList<>(jarSet));
  }
}
