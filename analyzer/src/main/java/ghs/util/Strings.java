// file: src/main/java/ghs/analyzer/util/Strings.java
package ghs.analyzer.util;

public final class Strings {

  private Strings() {}

  public static String simpleName(String fqn) {
    int i = fqn.lastIndexOf('.');
    return i >= 0 ? fqn.substring(i + 1) : fqn;
  }
}
