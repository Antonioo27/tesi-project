package ghs.analyzer.io;

import java.nio.file.Path;
import java.util.Set;

public interface ProgressStore {
  Set<String> load(Path module, String cfgId);
  void append(Path module, String cfgId, String key);
  void reset(Path module, String cfgId);
}
