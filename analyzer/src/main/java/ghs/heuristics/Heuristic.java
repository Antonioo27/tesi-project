package ghs.heuristics;

public interface Heuristic {
    String id();
    HeuristicResult run(HeuristicContext ctx) throws Exception;
}
