package ghs.app;

import ghs.cli.*;
import ghs.combine.ExampleTestResultCombiner;
import ghs.combine.TestResultCombiner;
import ghs.discovery.*;
import ghs.graph.*;
import ghs.heuristics.*;
import ghs.io.*;
import ghs.model.*;
import ghs.pipeline.*;
import ghs.sootupview.*;
import java.nio.file.*;
import java.util.List;

/**
 * Composition root dell'applicazione "Analyzer".
 *
 * Responsabilità:
 * 1) Parsing degli argomenti CLI -> CliOptions
 * 2) Mappatura in AnalysisConfig (immutabile) per il runtime
 * 3) Costruzione delle dipendenze concrete (scanner, I/O, heuristics, SootUp)
 * 4) Assemblaggio della strategia full (costruzione CHA + euristiche)
 * 5) Avvio della pipeline che scansiona i moduli Maven e analizza i test
 *
 * Output: JSONL con un record per test analizzato (focal class/method, stats,
 * score, kind).
 */
public final class Main {

        public static void main(String[] args) throws Exception {
                // ======================
                // 1) Lettura e validazione input CLI
                // ======================
                // Esempi di flag supportati: --base, --out, --maxDepth, --maxVisited, --resume,
                // ...
                // Vedi CliParser/CliOptions per l'elenco completo e i default.
                CliOptions opts = CliParser.parse(args);

                // ======================
                // 2) Configurazione runtime immutabile
                // ======================
                // Centralizza tutti i parametri usati nelle fasi successive (anche dopo
                // autotuning).
                AnalysisConfig cfg = AnalysisConfig.from(opts);

                // ======================
                // 3) Infrastruttura I/O e risorse
                // ======================
                // - ModuleScanner: individua moduli Maven (cartelle con pom.xml)
                // - InputResolver: risolve i path di target/classes e target/test-classes
                // - ProgressStore: file "analyzer-progress-<cfgId>.txt" per resume per-modulo
                // - OutputSink: scrittura JSONL (unico file o "split per repo")
                ModuleScanner scanner = new DefaultModuleScanner();
                InputResolver inputResolver = new DefaultInputResolver();
                ProgressStore progress = new FileProgressStore();
                OutputSink out = new JsonlOutputSink(cfg);

                // ======================
                // 4) SootUp & discovery dei test
                // ======================
                // ViewFactory crea una JavaView con (prod + test + JDK).
                // TestDiscovery trova i metodi annotati @Test (JUnit 4/5 e TestNG).
                ViewFactory viewFactory = new DefaultViewFactory();
                TestDiscovery discovery = new JUnitTestDiscovery();

                // ======================
                // 5) Euristiche e helper di analisi
                // ======================

                List<Heuristic> heuristics = List.of(
                                new NameBasedFocalClassHeuristic(),
                                new AssertionAwareFocalMethodHeuristic(),
                                new MockUsageHeuristic(),
                                new DirectCallsMetricHeuristic());

                HeuristicEngine heuristicEngine = new HeuristicEngine(heuristics);

                // Call-graph helpers:
                BfsTraverser bfs = new BfsTraverser(); // BFS con potatura librerie (riduce rumore/memoria)

                TestResultCombiner combiner = new ExampleTestResultCombiner();

                // ======================
                // 6) Strategia di analisi e orchestrazione
                // ======================
                // Strategia "full": costruisce il CHA e, per ogni test,
                // - stima focal class/method
                // - attraversa il grafo (BFS)
                // - misura feature (spread classi, profondità, mock)
                // - calcola score + classificazione
                AnalyzerStrategy full = new FullCallGraphStrategy(
                                viewFactory,
                                bfs,
                                heuristicEngine,
                                combiner);

                // ModuleAnalyzer: warm-up SootUp, discovery test, autotuning batch, resume/OOM
                // handling.
                ModuleAnalyzer moduleAnalyzer = new ModuleAnalyzer(
                                inputResolver,
                                viewFactory,
                                discovery,
                                progress,
                                out,
                                full);

                // Pipeline: scansiona moduli Maven sotto baseDir e delega l'analisi dei test
                // per modulo.
                AnalyzerPipeline pipeline = new AnalyzerPipeline(
                                scanner,
                                moduleAnalyzer,
                                cfg);

                // ======================
                // 7) Avvio
                // ======================
                // Percorre ricorsivamente cfg.baseDir(), applica i filtri (es. onlyFrom) e
                // analizza.
                pipeline.run(Paths.get(cfg.baseDir()));

                // Flush/chiusura risorse di output.
                out.close();

                System.out.println("\nAnalisi completata.");
        }
}
