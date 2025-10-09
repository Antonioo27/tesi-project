package ghs.heuristics;

import java.util.Map;
import java.util.List;

/**
 * Heuristica name-based con rimozione dei suffissi in ordine "lungo -> corto".
 */
public final class NameBasedFocalClassHeuristic implements Heuristic {

  @Override
  public String id() {
    return "focal-class-name";
  }

  @Override
  public HeuristicResult run(HeuristicContext ctx) {
    String testFqn = ctx.testMethod().getSignature()
        .getDeclClassType().getFullyQualifiedName();

    int lastDot = testFqn.lastIndexOf('.');
    String pkg = lastDot >= 0 ? testFqn.substring(0, lastDot) : "";
    String simple = lastDot >= 0 ? testFqn.substring(lastDot + 1) : testFqn;

    // Rimozione prefissi/suffissi comuni: prima quelli lunghi, poi i corti
    String base = simple
        .replaceFirst("^Test+", "")
        .replaceFirst("IntTest$", "")
        .replaceFirst("UnitTest$", "")
        .replaceFirst("Tests$", "")
        .replaceFirst("Test$", "")
        .replaceFirst("IT$", "");

    if (base.isEmpty())
      base = simple;

    String candidateFqn = pkg.isEmpty() ? base : pkg + "." + base;
    boolean exists = ctx.projectProdClasses().contains(candidateFqn);
    double conf = exists ? 0.8 : 0.4;

    Candidate<String> candidate = new Candidate<>(
        candidateFqn,
        conf,
        "name-derivation",
        Map.of("existsInProject", exists));

    return new HeuristicResult(
        id(),
        "focal-class-candidates",
        List.of(candidate),
        Map.of());
  }
}
