package ghs.heuristics;

import java.util.List;
import java.util.Optional;
import sootup.core.signatures.MethodSignature;

/**
 * Euristica "Name & Distance":
 * - Usa l'ORDINE della lista (distanza/rilevanza calcolata a monte).
 * - Sceglie il primo metodo NON triviale (niente costruttori, Object helpers,
 * getter/setter/is).
 * - Se tutti triviali, ricade sul primo (fallback sicuro).
 *
 * Nota: questa classe non calcola distanze; si affida all'ordine di 'ordered'.
 */
public final class NameAndDistanceFocalMethodHeuristic implements FocalMethodHeuristic {

  @Override
  public Optional<MethodSignature> selectFocalMethod(
      String focalClassFqn, // FQN della focal class (qui non utilizzato)
      List<MethodSignature> ordered // Candidati già ordinati per vicinanza/rilevanza
  ) {
    // 1) prova a prendere il primo NON triviale
    return ordered.stream()
        .filter(ms -> !isTrivial(ms.getSubSignature().toString()))
        .findFirst()
        // 2) se non esiste, prendi comunque il primo disponibile
        .or(() -> ordered.stream().findFirst());
  }

  /**
   * Riconosce metodi "triviali" che non sono buoni focal (costruttori,
   * getter/setter, ecc.).
   */
  private static boolean isTrivial(String subSig) {
    String name = methodNameFromSubSig(subSig);

    // Costruttori / class init
    if (name.equals("<init>") || name.equals("<clinit>"))
      return true;

    // Metodi standard "di servizio"
    if (name.equals("toString") ||
        name.equals("equals") ||
        name.equals("hashCode") ||
        name.equals("close") ||
        name.equals("finalize"))
      return true;

    // Getter / Setter / Predicate stile JavaBeans (getX/isX/hasX/setX con X
    // maiuscola)
    if ((name.startsWith("get") && name.length() >= 4 && Character.isUpperCase(name.charAt(3))) ||
        (name.startsWith("set") && name.length() >= 4 && Character.isUpperCase(name.charAt(3))) ||
        (name.startsWith("is") && name.length() >= 3 && Character.isUpperCase(name.charAt(2)))) {
      return true;
    }
    return false;
  }

  /** Estrae il nome del metodo dalla sub-signature "retType name(params)". */
  private static String methodNameFromSubSig(String subSig) {
    int par = subSig.indexOf('('); // posizione '('
    int sp = subSig.lastIndexOf(' ', par >= 0 ? par : subSig.length()); // ultimo spazio prima di '('
    if (par > 0 && sp >= 0 && par > sp) {
      return subSig.substring(sp + 1, par); // fetta tra spazio e '('
    }
    return subSig; // fallback: se il formato non è quello atteso
  }
}
