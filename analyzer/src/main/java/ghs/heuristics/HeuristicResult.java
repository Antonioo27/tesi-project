package ghs.heuristics;

import java.util.List;
import java.util.Map;

public record HeuristicResult (
    String heuristicId,
    String metricId,
    List<Candidate<?>> candidates,
    Map<String, Object> meta
) {}
