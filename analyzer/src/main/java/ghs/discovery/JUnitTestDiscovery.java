package ghs.analyzer.discovery;

import java.util.List;
import java.util.stream.Collectors;
import sootup.java.core.AnnotationUsage;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;

public final class JUnitTestDiscovery implements TestDiscovery {

  @Override
  public List<JavaSootMethod> discover(JavaView view) {
    return view
      .getClasses()
      .flatMap(c -> c.getMethods().stream())
      .filter(JUnitTestDiscovery::isJUnitOrTestNGTest)
      .collect(Collectors.toList());
  }

  private static boolean isJUnitOrTestNGTest(JavaSootMethod m) {
    for (AnnotationUsage au : m.getAnnotations()) {
      String ann = au.getAnnotation().getFullyQualifiedName();
      if (
        "org.junit.Test".equals(ann) ||
        "org.junit.jupiter.api.Test".equals(ann) ||
        "org.junit.jupiter.params.ParameterizedTest".equals(ann) ||
        ann.startsWith("org.testng.annotations.Test")
      ) return true;
    }
    return false;
  }
}
