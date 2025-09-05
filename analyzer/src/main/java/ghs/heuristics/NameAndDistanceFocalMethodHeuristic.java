package ghs.analyzer.heuristics;

import sootup.core.signatures.MethodSignature;
import java.util.List;
import java.util.Optional;

public final class NameAndDistanceFocalMethodHeuristic implements FocalMethodHeuristic {

  @Override
  public Optional<MethodSignature> selectFocalMethod(String focalClassFqn,
                                                     List<MethodSignature> ordered) {
    return ordered.stream()
        .filter(ms -> !isTrivial(ms.getSubSignature().toString()))
        .findFirst()
        .or(() -> ordered.stream().findFirst());
  }

  private static boolean isTrivial(String subSig) {
    String name = methodNameFromSubSig(subSig);
    if (name.equals("<init>") || name.equals("<clinit>")) return true;
    if (name.equals("toString") || name.equals("equals") || name.equals("hashCode")
        || name.equals("close") || name.equals("finalize")) return true;
    if ((name.startsWith("get") && name.length()>=4 && Character.isUpperCase(name.charAt(3))) ||
        (name.startsWith("set") && name.length()>=4 && Character.isUpperCase(name.charAt(3))) ||
        (name.startsWith("is")  && name.length()>=3 && Character.isUpperCase(name.charAt(2)))) return true;
    return false;
  }

  private static String methodNameFromSubSig(String subSig) {
    int par = subSig.indexOf('(');
    int sp = subSig.lastIndexOf(' ', par >= 0 ? par : subSig.length());
    if (par > 0 && sp >= 0 && par > sp) return subSig.substring(sp + 1, par);
    return subSig;
  }
}
