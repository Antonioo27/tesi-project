package ghs.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ghs.heuristics.Heuristic;
import ghs.heuristics.HeuristicContext;
import ghs.heuristics.HeuristicResult;


public final class HeuristicEngine {
    
    private final List<Heuristic> heuristics;

    public HeuristicEngine(List<Heuristic> heuristics) {
        this.heuristics = List.copyOf(heuristics);
    }

    public List<HeuristicResult> runAll(HeuristicContext ctx) {
        List<HeuristicResult> out = new ArrayList<>();
        for (Heuristic h: heuristics) {
            try {
                out.add(h.run(ctx));
            } catch (Exception e) {
                out.add(new HeuristicResult(
                    h.id(),
                    "error",
                    List.of(),
                    Map.of(
                        "error", e.getClass().getSimpleName(),
                        "message", e.getMessage())));
            }
        }
        return out;
    }
}
