package ghs.analyzer.io;

import java.nio.file.Path;
import java.util.List;

public interface ModuleScanner {
  List<Path> findMavenModules(Path base) throws java.io.IOException;
}
