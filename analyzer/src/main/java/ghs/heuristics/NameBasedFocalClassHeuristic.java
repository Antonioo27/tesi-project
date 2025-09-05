package ghs.analyzer.heuristics;

public final class NameBasedFocalClassHeuristic implements FocalClassHeuristic {

  @Override
  public String guessFocalClassFromTestName(String testClassFqn) {
    int lastDot = testClassFqn.lastIndexOf('.');
    String pkg = lastDot >= 0 ? testClassFqn.substring(0, lastDot) : "";
    String simple = lastDot >= 0
      ? testClassFqn.substring(lastDot + 1)
      : testClassFqn;
    String base = simple
      .replaceFirst("^(Test)+", "")
      .replaceFirst("(Test|Tests|IT|IntTest)$", "");
    return pkg.isEmpty() ? base : pkg + "." + base;
  }
}
