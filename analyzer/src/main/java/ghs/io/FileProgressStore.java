package ghs.analyzer.io;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class FileProgressStore implements ProgressStore {

  private Path file(Path module, String cfgId) {
    return module
      .resolve("target")
      .resolve("analyzer-progress-" + cfgId + ".txt");
  }

  @Override
  public Set<String> load(Path module, String cfgId) {
    Path f = file(module, cfgId);
    try {
      if (!Files.isRegularFile(f)) return new HashSet<>();
      return new LinkedHashSet<>(Files.readAllLines(f, StandardCharsets.UTF_8));
    } catch (Exception e) {
      System.out.println(
        " (warn) impossibile leggere progress: " + e.getMessage()
      );
      return new HashSet<>();
    }
  }

  @Override
  public void append(Path module, String cfgId, String key) {
    Path f = file(module, cfgId);
    try {
      Files.createDirectories(f.getParent());
      Files.writeString(
        f,
        key + System.lineSeparator(),
        StandardCharsets.UTF_8,
        Files.exists(f) ? StandardOpenOption.APPEND : StandardOpenOption.CREATE
      );
    } catch (Exception e) {
      System.out.println(" (warn) progress append fallito: " + e.getMessage());
    }
  }

  @Override
  public void reset(Path module, String cfgId) {
    try {
      Files.deleteIfExists(file(module, cfgId));
    } catch (Exception ignored) {}
  }
}
