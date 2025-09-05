package ghs.analyzer.heuristics;

import sootup.core.signatures.MethodSignature;
import java.util.List;
import java.util.Optional;

public interface FocalMethodHeuristic {
  Optional<MethodSignature> selectFocalMethod(String focalClassFqn,
                                              List<MethodSignature> inDistanceOrder);
}
