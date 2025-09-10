package ghs.analyzer.heuristics;

import java.util.List;
import java.util.Optional;
import sootup.core.signatures.MethodSignature;

public interface FocalMethodHeuristic {
  Optional<MethodSignature> selectFocalMethod(
    String focalClassFqn,
    List<MethodSignature> inDistanceOrder
  );
}
