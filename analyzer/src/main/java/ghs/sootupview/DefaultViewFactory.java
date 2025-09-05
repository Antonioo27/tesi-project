package ghs.analyzer.sootupview;

import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.java.core.views.JavaView;
import java.util.List;

public final class DefaultViewFactory implements ViewFactory {
  @Override
  public JavaView create(List<AnalysisInputLocation> locs) {
    return new JavaView(locs);
  }
}
