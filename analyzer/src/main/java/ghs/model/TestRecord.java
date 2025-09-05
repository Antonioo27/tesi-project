package ghs.analyzer.model;


public record TestRecord(
String repo,
String module,
String cfgId,
String testClass,
String testMethod,
String focalClass,
String focalMethod,
CgStats cgStats,
boolean usesMocks,
double unitIntegrationScore
) {}