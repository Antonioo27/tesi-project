// file: src/main/java/ghs/analyzer/util/PathUtil.java
package ghs.analyzer.util;

import java.io.File;
import java.nio.file.Path;

public final class PathUtil {

  private PathUtil() {}

  /** Nome repo come prima directory sotto baseDir. */
  public static String repoName(Path baseDir, Path module) {
    Path rel = baseDir.relativize(module);
    return rel.getNameCount() > 0
      ? rel.getName(0).toString()
      : module.getFileName().toString();
  }

  /** Converte un file .class in FQN partendo dalla root delle classi. */
  public static String toFqn(Path root, Path file) {
    Path rel = root.relativize(file);
    String s = rel.toString().replace(File.separatorChar, '.');
    if (s.endsWith(".class")) s = s.substring(0, s.length() - 6);
    return s;
  }
}
