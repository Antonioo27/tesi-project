package ghs.graph;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import ghs.pipeline.HeuristicEngine;
import ghs.heuristics.HeuristicContext;
import ghs.heuristics.HeuristicResult;
import sootup.callgraph.CallGraph;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootMethod;

/**
 * ChaCallGraphAnalyzer (VERSIONE “COLLECTOR”)
 *
 * RESPONSABILITÀ (SOLO RACCOLTA DATI GREZZI):
 * - Esegue BFS limitata dal metodo di test sul call graph (delegando a
 * BfsTraverser)
 * - Calcola statistiche strutturali di base (CgStats + lista classi progetto
 * toccate direttamente)
 * - Rileva uso di mock (MockUsageDetector)
 * - Costruisce un HeuristicContext e lancia TUTTE le heuristic registrate
 * (HeuristicEngine)
 * - Restituisce un RawTestAnalysis con: metadati test, stats, mock flag,
 * heuristicResults
 *
 * NON FA (delegato a futuri componenti “Combiner/Strategy”):
 * - Classificazione UNIT/INTEGRATION
 * - Calcolo score continuo unit_integration
 * - Selezione focal class / focal method
 * - Qualsiasi fusione / decisione finale
 *
 * Questo consente di sostituire o aggiungere strategie di combinazione senza
 * toccare la fase di raccolta.
 */
public final class ChaCallGraphAnalyzer {

        private final BfsTraverser bfs;
        private final HeuristicEngine heuristicEngine;

        public ChaCallGraphAnalyzer(
                        BfsTraverser bfs,
                        HeuristicEngine heuristicEngine) {
                this.bfs = Objects.requireNonNull(bfs);
                this.heuristicEngine = Objects.requireNonNull(heuristicEngine);
        }

        /**
         * Esegue la sola raccolta delle evidenze sul singolo metodo di test.
         *
         * @param repoName           nome repo
         * @param modulePath         path modulo
         * @param cfgId              id configurazione (tracking)
         * @param cg                 call graph (già costruito altrove)
         * @param testMethod         metodo di test
         * @param projectProdClasses FQN classi di produzione
         * @param projectTestClasses FQN classi di test
         * @param projectAllClasses  union prod+test (per pruning BFS)
         * @param maxDepth           limite profondità BFS
         * @param maxVisited         limite nodi visitati BFS
         * @return RawTestAnalysis (nessuna decisione finale)
         */
        public RawTestAnalysis collect(
                        String repoName,
                        Path modulePath,
                        String cfgId,
                        CallGraph cg,
                        JavaSootMethod testMethod,
                        Set<String> projectProdClasses,
                        Set<String> projectTestClasses,
                        Set<String> projectAllClasses,
                        int maxDepth,
                        int maxVisited) {

                MethodSignature testSig = testMethod.getSignature();
                String testClassFqn = testSig.getDeclClassType().getFullyQualifiedName();
                String testMethodName = testSig.getSubSignature().toString();

                // 1) BFS (solo raccolta distanza)
                Map<MethodSignature, Integer> dist = bfs.bfs(
                                cg,
                                testSig,
                                maxDepth,
                                projectAllClasses,
                                maxVisited);

                // Metodi di classi di produzione raggiunti
                List<MethodSignature> projectTargets = dist.keySet().stream()
                                .filter(ms -> projectProdClasses
                                                .contains(ms.getDeclClassType().getFullyQualifiedName()))
                                .collect(Collectors.toList());

                // Spread classi produzione
                Set<String> uniqueProjectClasses = projectTargets.stream()
                                .map(ms -> ms.getDeclClassType().getFullyQualifiedName())
                                .collect(Collectors.toCollection(LinkedHashSet::new));

                // Chiamate a librerie (né prod né test)
                List<String> libraryClasses = dist.keySet().stream()
                                .map(ms -> ms.getDeclClassType().getFullyQualifiedName())
                                .filter(fqn -> !projectProdClasses.contains(fqn)
                                                && !projectTestClasses.contains(fqn))
                                .distinct()
                                .collect(Collectors.toList());


                //ok
                // Stampa le classi di libreria
                // System.out.println("Classi di libreria chiamate: " + libraryClasses);

                // Statistiche strutturali
                int maxDepthVisited = dist.values().stream().mapToInt(Integer::intValue).max().orElse(0);

                // 2) Esecuzione heuristics (forniscono candidati / metriche grezze)
                HeuristicContext hCtx = new HeuristicContext(
                                repoName,
                                modulePath,
                                testMethod,
                                cg,
                                projectProdClasses,
                                projectTestClasses,
                                maxDepthVisited,
                                maxVisited);

                List<HeuristicResult> heuristicResults = heuristicEngine.runAll(hCtx);

                // 3) Costruzione raw analysis (campi “decisione” lasciati vuoti / default)
                return new RawTestAnalysis(
                                repoName,
                                modulePath.toString(),
                                cfgId,
                                testClassFqn,
                                testMethodName,
                                heuristicResults);
        }
}