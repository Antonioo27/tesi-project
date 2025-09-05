package ghs.analyzer.graph;

import ghs.analyzer.heuristics.*;
import ghs.analyzer.model.*;
import java.util.*;
import java.util.stream.Collectors;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootMethod;

public final class ChaCallGraphAnalyzer implements CallGraphAnalyzer {

  private final BfsTraverser bfs;
  private final MockUsageDetector mocks;
  private final UnitIntegrationScorer scorer;

  public ChaCallGraphAnalyzer(
    BfsTraverser bfs,
    MockUsageDetector mocks,
    UnitIntegrationScorer scorer
  ) {
    this.bfs = bfs;
    this.mocks = mocks;
    this.scorer = scorer;
  }

  @Override
  public TestRecord analyzeOne(
    String repoName,
    java.nio.file.Path module,
    String cfgId,
    sootup.callgraph.CallGraph cg,
    JavaSootMethod tm,
    Set<String> projectProdClasses,
    Set<String> projectTestClasses,
    Set<String> projectAllClasses,
    int maxDepth,
    boolean pruneLibs,
    int maxVisited,
    java.util.function.Function<String, String> simpleName,
    FocalClassHeuristic classHeu,
    FocalMethodHeuristic methodHeu
  ) {
    MethodSignature tSig = tm.getSignature();
    String testClass = tSig.getDeclClassType().getFullyQualifiedName();
    String testMethod = tSig.getSubSignature().toString();

    String candidateFocalClass = classHeu.guessFocalClassFromTestName(
      testClass
    );

    Map<MethodSignature, Integer> distance = bfs.bfs(
      cg,
      tSig,
      maxDepth,
      projectAllClasses,
      pruneLibs,
      maxVisited
    );

    List<MethodSignature> projectTargets = distance
      .keySet()
      .stream()
      .filter(ms ->
        projectProdClasses.contains(
          ms.getDeclClassType().getFullyQualifiedName()
        )
      )
      .collect(Collectors.toList());

    Optional<MethodSignature> byName = projectTargets
      .stream()
      .filter(ms ->
        ms
          .getDeclClassType()
          .getFullyQualifiedName()
          .endsWith('.' + simpleName.apply(candidateFocalClass))
      )
      .min(Comparator.comparingInt(distance::get));

    Optional<String> byMinDistanceClass = projectTargets
      .stream()
      .collect(
        Collectors.groupingBy(
          ms -> ms.getDeclClassType().getFullyQualifiedName(),
          Collectors.mapping(
            distance::get,
            Collectors.minBy(Integer::compareTo)
          )
        )
      )
      .entrySet()
      .stream()
      .sorted(Comparator.comparingInt(e -> e.getValue().orElse(999)))
      .map(Map.Entry::getKey)
      .findFirst();

    String focalClassFqn = byName
      .map(ms -> ms.getDeclClassType().getFullyQualifiedName())
      .orElse(byMinDistanceClass.orElse(candidateFocalClass));

    List<MethodSignature> focalClassMethods = projectTargets
      .stream()
      .filter(ms ->
        ms.getDeclClassType().getFullyQualifiedName().equals(focalClassFqn)
      )
      .sorted(Comparator.comparingInt(distance::get))
      .collect(Collectors.toList());

    Optional<MethodSignature> focalMethodSig = methodHeu.selectFocalMethod(
      focalClassFqn,
      focalClassMethods
    );

    java.util.Set<String> uniqueProjectClasses = projectTargets
      .stream()
      .map(ms -> ms.getDeclClassType().getFullyQualifiedName())
      .collect(Collectors.toSet());
    long callsToFocal = focalClassMethods.size();
    long callsToOtherProjectClasses = projectTargets.size() - callsToFocal;
    long callsToLibraries = distance
      .keySet()
      .stream()
      .filter(
        ms ->
          !projectProdClasses.contains(
            ms.getDeclClassType().getFullyQualifiedName()
          ) &&
          !projectTestClasses.contains(
            ms.getDeclClassType().getFullyQualifiedName()
          )
      )
      .count();
    boolean usesMocks = mocks.usesMocks(cg, tSig);
    double score = scorer.score(
      projectTargets.size(),
      (int) callsToOtherProjectClasses,
      usesMocks
    );

    return new TestRecord(
      repoName,
      module.toString(),
      cfgId,
      testClass,
      testMethod,
      focalClassFqn,
      focalMethodSig.map(MethodSignature::toString).orElse(""),
      new CgStats(
        projectTargets.size(),
        (int) callsToFocal,
        (int) callsToOtherProjectClasses,
        (int) callsToLibraries,
        uniqueProjectClasses.size(),
        distance.values().stream().mapToInt(i -> i).max().orElse(0)
      ),
      usesMocks,
      score
    );
  }
}
