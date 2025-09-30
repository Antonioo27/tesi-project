package ghs.discovery;

import java.util.List;
import java.util.stream.Collectors;
import sootup.java.core.AnnotationUsage;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;

/**
 * Scoperta dei metodi di test JUnit/TestNG.
 *
 * Strategia:
 * - Scorre tutte le classi presenti nella JavaView (prod/test/JDK a seconda dei
 * locs).
 * - Appiattisce in tutti i metodi e filtra quelli con annotazioni note di test.
 *
 * Limiti noti:
 * - Rileva annotazioni SOLO a livello di metodo (TestNG a livello classe non
 * incluso).
 * - Non include altri tipi JUnit 5 (es. RepeatedTest, TestTemplate,
 * TestFactory) a meno di estensione.
 * - Non gestisce JUnit 3 (TestCase + metodi test* senza annotazione).
 */
public final class JUnitTestDiscovery implements TestDiscovery {

  @Override
  public List<JavaSootMethod> discover(JavaView view) {
    return view
        .getClasses() // stream delle classi visibili nella view
        .flatMap(c -> c.getMethods().stream()) // tutti i metodi di tutte le classi
        .filter(JUnitTestDiscovery::isJUnitOrTestNGTest) // tiene solo i metodi annotati come test
        .collect(Collectors.toList()); // raccoglie in lista
  }

  /**
   * Riconosce i metodi di test in base all'annotazione sul metodo.
   * Copre: JUnit 4, JUnit 5 (@Test, @ParameterizedTest), TestNG (@Test).
   */
  private static boolean isJUnitOrTestNGTest(JavaSootMethod m) {
    for (AnnotationUsage au : m.getAnnotations()) {
      String ann = au.getAnnotation().getFullyQualifiedName();
      if ("org.junit.Test".equals(ann) || // JUnit 4
          "org.junit.jupiter.api.Test".equals(ann) || // JUnit 5
          "org.junit.jupiter.params.ParameterizedTest".equals(ann) || // JUnit 5 parametrizzato
          ann.startsWith("org.testng.annotations.Test") // TestNG @Test (anche inner)
      ) {
        return true;
      }
    }
    return false;
  }
}
