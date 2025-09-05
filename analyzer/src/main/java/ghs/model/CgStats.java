package ghs.analyzer.model;


public record CgStats(
int projectCalls,
int callsToFocalClass,
int callsToOtherProjectClasses,
int callsToLibraries,
int uniqueProjectClasses,
int maxDepthVisited
) {}