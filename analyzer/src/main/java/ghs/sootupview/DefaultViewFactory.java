package ghs.sootupview;

import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.java.core.views.JavaView;
import java.util.List;

/**
 * Factory minimale per creare una JavaView di SootUp.
 *
 * Responsabilità:
 * - Dato un elenco di "input locations" (classpath entries e JDK),
 * costruisce una JavaView che verrà usata per discovery dei test,
 * costruzione del call graph, ecc.
 *
 * Note:
 * - L'ordine delle locations tipicamente segue la priorità di classpath:
 * [target/classes, target/test-classes, JDK]
 * - Questo factory esiste per isolare la dipendenza da SootUp in un punto solo,
 * semplificando test e futura estendibilità (caching/configurazioni).
 */
public final class DefaultViewFactory implements ViewFactory {

  @Override
  public JavaView create(List<AnalysisInputLocation> locs) {
    // Crea una vista del "mondo" Java composto da queste locations.
    // Esempi di locs nel progetto:
    // - new JavaClassPathAnalysisInputLocation(<module>/target/classes)
    // - new JavaClassPathAnalysisInputLocation(<module>/target/test-classes)
    // - new JrtFileSystemAnalysisInputLocation() // JDK
    return new JavaView(locs);
  }
}
