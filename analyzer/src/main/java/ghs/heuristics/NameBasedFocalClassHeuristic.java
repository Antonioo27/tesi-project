package ghs.heuristics;

/**
 * Euristica semplice: deduce la "focal class" dal nome della classe di test.
 * Esempio: com.acme.FooTest -> com.acme.Foo
 *
 * Regole:
 * - rimuove eventuale prefisso "Test" ripetuto (es. TestTestFooTest -> FooTest
 * -> poi rimosso suffix)
 * - rimuove il suffisso tra (Test|Tests|IT|IntTest)
 * - preserva il package originale
 *
 * Nota: l'ordine delle alternative del suffisso, così com'è, può lasciare "Int"
 * in "FooIntTest" (vedi sezione fix sotto).
 */
public final class NameBasedFocalClassHeuristic implements FocalClassHeuristic {

  @Override
  public String guessFocalClassFromTestName(String testClassFqn) {
    int lastDot = testClassFqn.lastIndexOf('.');
    String pkg = (lastDot >= 0) ? testClassFqn.substring(0, lastDot) : "";
    String simple = (lastDot >= 0) ? testClassFqn.substring(lastDot + 1) : testClassFqn;

    // Ordine corretto: pattern più lunghi prima, per evitare match parziali
    String base = simple
        .replaceFirst("^(Test)+", "")
        .replaceFirst("(IntTest|Tests|Test|IT)$", "");

    // Fallback: evita di restituire un FQN con nome vuoto
    if (base.isEmpty()) {
      base = simple.replaceFirst("(IntTest|Tests|Test|IT)$", "");
      if (base.isEmpty())
        base = simple; // ultima spiaggia
    }

    return pkg.isEmpty() ? base : pkg + "." + base;
  }
}
