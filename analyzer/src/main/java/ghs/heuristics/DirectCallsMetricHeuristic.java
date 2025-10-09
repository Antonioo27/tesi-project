package ghs.heuristics;

import sootup.core.signatures.MethodSignature;

import java.util.*;
import java.util.stream.Collectors;

public final class DirectCallsMetricHeuristic implements Heuristic {
    @Override
    public String id() {
        return "direct-calls";
    }

    @Override
    public HeuristicResult run(HeuristicContext ctx) {
        var testSig = ctx.testMethod().getSignature();

        // Recupera tutte le chiamate in uscita dirette dal metodo di test (livello 1
        // del call graph)
        List<MethodSignature> directCalls = ctx.callGraph().callsFrom(testSig).stream()
                .map(c -> c.getTargetMethodSignature())
                .filter(ms -> ctx.projectProdClasses()
                        .contains(ms.getDeclClassType().getFullyQualifiedName()))
                .toList();

        // perClass è una mappa: chiave = FQN della classe di produzione, valore =
        // numero di chiamate dirette a metodi di quella classe.
        Map<String, Long> perClass = directCalls.stream()
                .collect(Collectors.groupingBy(
                        ms -> ms.getDeclClassType().getFullyQualifiedName(),
                        LinkedHashMap::new,
                        Collectors.counting()));

        // cardinalità del numero di chiamate dirette
        int total = directCalls.size();
        // cardinalità classi di produzione diverse toccate.
        int unique = perClass.size();

        /**
         * Caso base:
         * Se non ci sono chiamate (total == 0) → concentration = 0.0.
         * 
         * Se c’è almeno una chiamata ma verso una sola classe (unique <= 1) →
         * concentration = 1.0 (tutto concentrato su una classe).
         * 
         * Caso generale:
         * 
         * Prende il massimo conteggio tra i valori di perClass
         * (max() sulle occorrenze per classe, con orElse(0L) come fallback).
         * 
         * Divide per total (cast a double) → quota massima:
         * maxCallsToOneClass / total.
         * 
         * Esempi:
         * perClass = {A=7, B=3}, total=10 → 0.7
         * 
         * misura quanto il test è focalizzato su una singola classe
         */
        double concentration = (unique <= 1 || total == 0)
                ? (total > 0 ? 1.0 : 0.0)
                : perClass.values().stream().mapToLong(Long::longValue).max().orElse(0L) / (double) total;

        Candidate<Void> metricCandidate = new Candidate<>(
                null,
                1.0,
                "direct-calls-distribution",
                Map.of(
                        "totalMethodCalls", total,
                        "uniqueClassCount", unique,
                        "classConcentration", concentration,
                        "perClass", perClass));

        return new HeuristicResult(
                id(),
                "direct_calls_metrics",
                List.of(metricCandidate),
                Map.of());

    }
}
