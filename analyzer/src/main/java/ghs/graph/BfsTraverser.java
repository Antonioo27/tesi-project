package ghs.analyzer.graph;

import java.util.*;
import sootup.callgraph.CallGraph;
import sootup.core.signatures.MethodSignature;

public final class BfsTraverser {

  public Map<MethodSignature, Integer> bfs(
    CallGraph cg,
    MethodSignature start,
    int maxDepth,
    Set<String> projectAllClasses,
    boolean pruneLibs,
    int maxVisited
  ) {
    Map<MethodSignature, Integer> dist = new LinkedHashMap<>();
    ArrayDeque<MethodSignature> q = new ArrayDeque<>();
    dist.put(start, 0);
    q.add(start);
    while (!q.isEmpty()) {
      if (dist.size() >= maxVisited) break;
      MethodSignature u = q.poll();
      int d = dist.get(u);
      if (d >= maxDepth) continue;
      boolean uIsProject = projectAllClasses.contains(
        u.getDeclClassType().getFullyQualifiedName()
      );
      if (pruneLibs && !uIsProject && d >= 1) continue;
      cg
        .callsFrom(u)
        .forEach(call -> {
          MethodSignature v = call.getTargetMethodSignature();
          if (!dist.containsKey(v)) {
            dist.put(v, d + 1);
            if (dist.size() < maxVisited) q.add(v);
          }
        });
    }
    return dist;
  }
}
