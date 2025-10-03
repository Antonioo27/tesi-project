package ghs.heuristics;

import java.util.Optional;
import sootup.core.signatures.MethodSignature;

public interface FocalMethodHeuristic {
  Optional<MethodSignature> selectFocalMethod(FocalMethodContext ctx);
}
