package ghs.analyzer.sootupview;

import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.java.core.views.JavaView;
import java.util.List;

public interface ViewFactory {
  JavaView create(List<AnalysisInputLocation> locations);
}
