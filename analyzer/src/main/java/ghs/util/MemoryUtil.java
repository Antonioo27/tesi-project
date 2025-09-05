// file: src/main/java/ghs/analyzer/util/MemoryUtil.java
package ghs.analyzer.util;

public final class MemoryUtil {

  private MemoryUtil() {}

  public static long usedMB() {
    return (
      (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) /
      (1024 * 1024)
    );
  }

  public static long maxMB() {
    return Runtime.getRuntime().maxMemory() / (1024 * 1024);
  }
}
