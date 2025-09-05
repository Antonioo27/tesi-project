package ghs.analyzer.discovery;

import java.util.List;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;

public interface TestDiscovery {
  List<JavaSootMethod> discover(JavaView view);
}
