package ghs.heuristics;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import sootup.callgraph.CallGraph;
import sootup.java.core.JavaSootMethod;

public record HeuristicContext (
    String repo,
    Path modulePath,
    JavaSootMethod testMethod,
    CallGraph callGraph,
    Set<String> projectProdClasses,
    Set<String> projectTestClasses,
    int maxDepth,
    int maxVisited
) {}
