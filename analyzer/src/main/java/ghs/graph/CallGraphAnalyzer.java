package ghs.analyzer.graph;

import ghs.analyzer.model.*;
import java.util.*;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootMethod;

public interface CallGraphAnalyzer {
  TestRecord analyzeOne(
    String repoName,
    java.nio.file.Path module,
    String cfgId,
    sootup.callgraph.CallGraph cg,
    JavaSootMethod tm,
    Set<String> projectProdClasses,
    Set<String> projectTestClasses,
    Set<String> projectAllClasses,
    int maxDepth,
    boolean pruneLibs,
    int maxVisited,
    java.util.function.Function<String, String> simpleName,
    ghs.analyzer.heuristics.FocalClassHeuristic classHeu,
    ghs.analyzer.heuristics.FocalMethodHeuristic methodHeu
  );
}
