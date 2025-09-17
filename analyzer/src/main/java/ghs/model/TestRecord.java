package ghs.analyzer.model;

import java.util.List;

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
  double unitIntegrationScore,
  TestKind testKind,                 // UNIT o INTEGRATION
  List<String> directProjectClasses  // solo dirette dal metodo di test
) {}
