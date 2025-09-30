package ghs.graph;

import ghs.heuristics.FocalClassHeuristic;
import ghs.heuristics.FocalMethodHeuristic;
import ghs.heuristics.TestClassifier;
import ghs.model.CgStats;
import ghs.model.TestKind;
import ghs.model.TestRecord;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import sootup.callgraph.CallGraph;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootMethod;

/**
 * Analizza un singolo metodo di test usando un call graph CHA.
 *
 * Passi:
 * 1) BFS dal metodo di test (limiti: maxDepth, maxVisited, potatura libs sempre
 * attiva).
 * 2) Classificazione UNIT/INTEGRATION (TestClassifier).
 * 3) Se UNIT → stima focal class e focal method (Unique Direct Call → fallback
 * euristico).
 * 4) Statistiche del grafo + score continuo (UnitIntegrationScorer).
 * 5) Costruzione TestRecord (con testKind e directProjectClasses).
 */
public final class ChaCallGraphAnalyzer implements CallGraphAnalyzer {

    private final BfsTraverser bfs;
    private final MockUsageDetector mocks;
    private final UnitIntegrationScorer scorer;
    private final TestClassifier classifier;

    public ChaCallGraphAnalyzer(
            BfsTraverser bfs,
            MockUsageDetector mocks,
            UnitIntegrationScorer scorer,
            TestClassifier classifier) {
        this.bfs = bfs;
        this.mocks = mocks;
        this.scorer = scorer;
        this.classifier = classifier;
    }

    @Override
    public TestRecord analyzeOne(
            String repoName,
            Path module,
            String cfgId,
            CallGraph cg,
            JavaSootMethod tm,
            Set<String> projectProdClasses,
            Set<String> projectTestClasses,
            Set<String> projectAllClasses,
            int maxDepth,
            int maxVisited,
            java.util.function.Function<String, String> simpleName,
            FocalClassHeuristic classHeu,
            FocalMethodHeuristic methodHeu) {
        // === dati base del test ===
        final MethodSignature testSig = tm.getSignature();
        final String testClass = testSig.getDeclClassType().getFullyQualifiedName();
        final String testMethod = testSig.getSubSignature().toString();

        // === 1) BFS dal metodo di test (pruning libs sempre ON) ===
        Map<MethodSignature, Integer> distance = bfs.bfs(
                cg,
                testSig,
                maxDepth,
                projectAllClasses,
                maxVisited);
        final Map<MethodSignature, Integer> dist = distance; // per lambda

        // Metodi di classi DI PROGETTO raggiunti
        List<MethodSignature> projectTargets = dist.keySet().stream()
                .filter(ms -> projectProdClasses.contains(
                        ms.getDeclClassType().getFullyQualifiedName()))
                .collect(Collectors.toList());

        // Spread su classi di progetto raggiunte
        Set<String> uniqueProjectClasses = projectTargets.stream()
                .map(ms -> ms.getDeclClassType().getFullyQualifiedName())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Chiamate a librerie (né prod né test del progetto)
        long callsToLibraries = dist.keySet().stream()
                .filter(ms -> !projectProdClasses.contains(ms.getDeclClassType().getFullyQualifiedName()) &&
                        !projectTestClasses.contains(ms.getDeclClassType().getFullyQualifiedName()))
                .count();

        // Mock usage
        boolean usesMocks = mocks.usesMocks(cg, testSig);

        // === 2) Classificazione UNIT vs INTEGRATION ===
        TestClassifier.ClassificationResult cr = classifier.classify(
                cg, tm, projectProdClasses, projectTestClasses);

        // === 3) Focal solo se UNIT ===
        String focalClassFqn = "";
        Optional<MethodSignature> focalMethodSig = Optional.empty();
        long callsToFocal = 0L;
        long callsToOtherProjectClasses = 0L;

        if (cr.kind() == TestKind.UNIT) {
            // 3.a) FOCAL CLASS
            String candidateByName = classHeu.guessFocalClassFromTestName(testClass);
            String pkg = testClass.contains(".")
                    ? testClass.substring(0, testClass.lastIndexOf('.'))
                    : "";
            String base = simpleName.apply(candidateByName);
            final String baseName = base;

            String exactCandidate = pkg.isEmpty() ? baseName : (pkg + "." + baseName);

            Optional<String> focalExact = projectProdClasses.contains(exactCandidate)
                    ? Optional.of(exactCandidate)
                    : Optional.empty();

            Optional<MethodSignature> byName = projectTargets.stream()
                    .filter(ms -> ms.getDeclClassType().getFullyQualifiedName().endsWith("." + baseName))
                    .min(Comparator.comparingInt(dist::get));

            Optional<String> byMinDistanceClass = projectTargets.stream()
                    .collect(Collectors.groupingBy(
                            ms -> ms.getDeclClassType().getFullyQualifiedName(),
                            Collectors.mapping(dist::get, Collectors.minBy(Integer::compareTo))))
                    .entrySet().stream()
                    .sorted(Comparator.comparingInt(e -> e.getValue().orElse(Integer.MAX_VALUE)))
                    .map(Map.Entry::getKey)
                    .findFirst();

            focalClassFqn = focalExact.orElse(
                    byName.map(ms -> ms.getDeclClassType().getFullyQualifiedName())
                            .orElse(byMinDistanceClass.orElse(candidateByName)));

            final String focalClassFqnFinal = focalClassFqn;

            // Metodi della focal class (ordinati per distanza)
            List<MethodSignature> focalClassMethods = projectTargets.stream()
                    .filter(ms -> ms.getDeclClassType().getFullyQualifiedName().equals(focalClassFqnFinal))
                    .sorted(Comparator.comparingInt(dist::get))
                    .collect(Collectors.toList());

            // 3.b) FOCAL METHOD
            final Set<MethodSignature> directCallsFromTest = cg.callsFrom(testSig).stream()
                    .map(call -> call.getTargetMethodSignature())
                    .collect(Collectors.toSet());

            List<MethodSignature> directCallsToFocal = focalClassMethods.stream()
                    .filter(directCallsFromTest::contains)
                    .collect(Collectors.toList());

            if (directCallsToFocal.size() == 1) {
                focalMethodSig = Optional.of(directCallsToFocal.get(0));
            } else {
                focalMethodSig = methodHeu.selectFocalMethod(focalClassFqnFinal, focalClassMethods);
            }

            // 3.c) breakdown chiamate
            callsToFocal = focalClassMethods.size();
            callsToOtherProjectClasses = projectTargets.size() - callsToFocal;
        } else {
            // INTEGRATION → niente concetto di focal
            focalClassFqn = "";
            callsToFocal = 0L;
            callsToOtherProjectClasses = projectTargets.size();
        }

        // === 4) Statistiche + Score ===
        int maxDepthVisited = dist.values().stream()
                .mapToInt(Integer::intValue)
                .max().orElse(0);

        CgStats stats = new CgStats(
                projectTargets.size(),
                (int) callsToFocal,
                (int) callsToOtherProjectClasses,
                (int) callsToLibraries,
                uniqueProjectClasses.size(),
                maxDepthVisited);

        double score = scorer.score(
                new UnitIntegrationScorer.Features(
                        cr.directRefsCount(), // classi progetto dirette dal test
                        uniqueProjectClasses.size(), // spread BFS
                        maxDepthVisited, // profondità
                        usesMocks, // mock on/off
                        stats.projectCalls(), // invocazioni verso classi progetto
                        stats.callsToFocalClass() // quota focal (0 se INTEGRATION)
                ));

        // === 5) Output ===
        return new TestRecord(
                repoName,
                module.toString(),
                cfgId,
                testClass,
                testMethod,
                cr.kind() == TestKind.UNIT ? focalClassFqn : "",
                cr.kind() == TestKind.UNIT
                        ? focalMethodSig.map(MethodSignature::toString).orElse("")
                        : "",
                stats,
                usesMocks,
                score,
                cr.kind(),
                cr.directProjectClasses());
    }
}
