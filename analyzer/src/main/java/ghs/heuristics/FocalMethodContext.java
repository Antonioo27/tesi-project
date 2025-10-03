package ghs.heuristics;

import sootup.callgraph.CallGraph;
import sootup.java.core.JavaSootMethod;
import sootup.core.signatures.MethodSignature;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public record FocalMethodContext(
        CallGraph cg,
        JavaSootMethod testMethod,
        Path module,
        String focalClassFqn,
        List<MethodSignature> candidatesOrdered, // metodi della focal (ordinati)
        Set<MethodSignature> directCallsFromTest // calls dirette dal test
) {
    // helper comodi (opzionali)
    public boolean hasSingleDirect() {
        return directCallsFromTest != null && directCallsFromTest.size() == 1;
    }

    public boolean hasCandidates() {
        return candidatesOrdered != null && !candidatesOrdered.isEmpty();
    }
}
