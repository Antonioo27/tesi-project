package ghs.graph;

import java.util.*;
import sootup.callgraph.CallGraph;
import sootup.core.signatures.MethodSignature;

/**
 * BFS sul call graph con potatura delle librerie SEMPRE attiva:
 * - dal test (depth=0) posso raggiungere metodi di libreria a depth=1,
 * - ma non espando mai oltre i nodi di libreria (depth>=1).
 */
public final class BfsTraverser {

  public Map<MethodSignature, Integer> bfs(
      CallGraph cg,
      MethodSignature start,
      int maxDepth,
      Set<String> projectAllClasses,
      int maxVisited) {
    Map<MethodSignature, Integer> dist = new LinkedHashMap<>();
    ArrayDeque<MethodSignature> q = new ArrayDeque<>();
    dist.put(start, 0);
    q.add(start);

    while (!q.isEmpty()) {
      if (dist.size() >= maxVisited)
        break;
      MethodSignature u = q.poll();
      int d = dist.get(u);
      if (d >= maxDepth)
        continue;

      boolean uIsProject = projectAllClasses.contains(
          u.getDeclClassType().getFullyQualifiedName());

      // Potatura librerie sempre attiva: non espandere nodi di libreria a depth>=1
      if (!uIsProject && d >= 1)
        continue;

      cg.callsFrom(u).forEach(call -> {
        MethodSignature v = call.getTargetMethodSignature();
        if (!dist.containsKey(v)) {
          dist.put(v, d + 1);
          if (dist.size() < maxVisited)
            q.add(v);
        }
      });
    }
    return dist;
  }
}
