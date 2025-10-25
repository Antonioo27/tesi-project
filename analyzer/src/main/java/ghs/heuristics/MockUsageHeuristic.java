package ghs.heuristics;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootMethod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * MockUsageHeuristic
 * metricId: mock_usage
 *
 * Rileva i mock utilizzati in un metodo di test aggregando per TIPO/CLASSE
 * e supportando :
 * - Mock "normali" (@Mock e mock(Foo.class))
 * - Spy (spy(realObj))
 * - Mock statici (MockedStatic<T> tramite mockStatic(T.class))
 * Conta per ciascun mock:
 * - stubbings:
 * - Stile A: when(mock.m(...))
 * - Stile B: doReturn(...).when(mock).m(...)
 * - verifications:
 * - conteggio delle chiamate a verify(mock, ...) per quel tip
 * - include verify su mock statici: ms.verify(() -> StaticUtil.m(...))
 * - directCalls:
 * - invocazioni dirette mock.foo(...) effettuate fuori da contesti when/verify
 * Normalizzazione (per una metrica coerente tra test/progetti)
 * 
 * rima di incrementare i contatori, il nome variabile viene normalizzato in
 * chiave di tipo:
 * - mockAliases: var → Tipo (es. "repo" → "OrderRepository")
 * - spyAliases: var → Tipo dichiarato (es. "spy" → "List"/"ArrayList")
 * - staticAliases:var MockedStatic → Classe statica (es. "ms" → "StaticUtil")
 * In questo modo le metriche sono aggregate per classe/tipo (non per nome
 * locale).
 * Evidenze & confidence
 * ---------------------
 * - Le evidenze riportano per ciascun tipo: directCalls, stubbings,
 * verifications, totalInteractions.
 * - La confidence privilegia la presenza di verifications, poi stubbings,
 * infine directCalls.
 * * Output
 * ------
 * Restituisce un HeuristicResult con:
 * - candidates: una entry per ciascun tipo “coinvolto” nel test (ordinati per
 * verifications, poi stubbings, poi directCalls)
 * - meta: { mockCount, totalInteractions }
 */
public final class MockUsageHeuristic implements Heuristic {

  @Override
  public String id() {
    return "mock-usage";
  }

  @Override
  public HeuristicResult run(HeuristicContext ctx) {
    JavaSootMethod testMethod = ctx.testMethod();
    MethodSignature sig = testMethod.getSignature();
    String testFqn = sig.getDeclClassType().getFullyQualifiedName();
    String testMethodName = sig.getName();

    Path sourceFile = resolveTestSource(ctx.modulePath(), testFqn);
    if (!Files.isRegularFile(sourceFile)) {
      return empty("source_not_found:" + sourceFile);
    }

    configureSymbolSolver(ctx.modulePath());

    CompilationUnit cu;
    try {
      cu = StaticJavaParser.parse(sourceFile);
    } catch (IOException e) {
      return empty("parse_error");
    } catch (Exception e) {
      return empty("parse_failure");
    }

    // Trova il metodo di test
    Optional<MethodDeclaration> mdOpt = cu.findAll(MethodDeclaration.class).stream()
        .filter(m -> m.getNameAsString().equals(testMethodName))
        .filter(m -> m.getParameters().size() == sig.getParameterTypes().size())
        .findFirst();

    if (mdOpt.isEmpty())
      return empty("method_not_found");
    MethodDeclaration md = mdOpt.get();
    if (md.getBody().isEmpty())
      return empty("empty_body");

    // 1) Collezione @Mock
    Set<String> mockVars = new LinkedHashSet<>();

    // 2) alias
    Map<String, String> mockAliases = collectMockAliases(cu, md);
    Map<String, String> spyAliases = collectSpyAliases(md);
    Map<String, String> staticAliases = collectStaticMockAliases(md);

    // 3) aggiungi ai "mock noti" anche i **tipi** emersi dagli alias (così contiamo
    // per tipo)
    mockVars.addAll(mockAliases.values());
    mockVars.addAll(spyAliases.values());
    mockVars.addAll(staticAliases.values());

    if (mockVars.isEmpty())
      return empty("no_mocks_detected");

    // 4) inizializza stats per tutti i tipi/chiavi note
    Map<String, Stats> stats = new LinkedHashMap<>();
    for (String k : mockVars)
      stats.put(k, new Stats());

    List<MethodCallExpr> allCalls = md.findAll(MethodCallExpr.class);

    // =============== STUBBINGS (UNIFICATO: SOLO WHEN(...)) ===============

    findStubbings(allCalls, mockAliases, spyAliases, staticAliases, stats);

    // =============== VERIFY INVOCATIONS (raw: verify(mock)) ===============
    // Si occupa solo di verify(mock) come invocazione diretta (non del metodo
    // verificato).
    // Prende arg0 di verify(...) e lo normalizza con asSimpleVarName.
    // Se è un mock noto → verifyInvocations++.
    // =============== VERIFY INVOCATIONS (raw: verify(mock)) ===============

    findVerifications(allCalls, mockAliases, spyAliases, staticAliases, stats);

    findDirectCalls(allCalls, mockAliases, spyAliases, staticAliases, stats);

    // Rimuovi mock mai usati (senza alcuna interazione forte: direct/stub/verify)
    stats.entrySet().removeIf(e -> e.getValue().totalInteractions() == 0);
    if (stats.isEmpty())
      return empty("all_mocks_unused");

    long totalInteractions = stats.values().stream()
        .mapToLong(Stats::totalInteractions)
        .sum();

    List<Candidate<?>> candidates = new ArrayList<>();
    for (var e : stats.entrySet()) {
      String var = e.getKey();
      Stats st = e.getValue();
      double conf = confidence(st);
      candidates.add(new Candidate<>(
          var,
          round3(conf),
          dominantLabel(st),
          evidence(var, st, totalInteractions)));
    }

    // Ordina per verifications desc, poi stubbings, poi directCalls
    candidates.sort((a, b) -> {
      Stats sa = stats.get(a.value().toString());
      Stats sb = stats.get(b.value().toString());
      int c = Long.compare(sb.verifications, sa.verifications);
      if (c != 0)
        return c;
      c = Long.compare(sb.stubbings, sa.stubbings);
      if (c != 0)
        return c;
      c = Long.compare(sb.directCalls, sa.directCalls);
      if (c != 0)
        return c;
      return a.value().toString().compareTo(b.value().toString());
    });

    Map<String, Object> summary = Map.of(
        "mockCount", stats.size(),
        "totalInteractions", totalInteractions);

    return new HeuristicResult(
        id(),
        "mock_usage",
        candidates,
        summary);
  }

  // ---- Collection helpers ----

  private boolean hasMockAnnotation(NodeWithAnnotations<?> node) {
    return node.getAnnotations().stream()
        .map(a -> a.getName().getIdentifier().toLowerCase(Locale.ROOT))
        .anyMatch(n -> n.equals("mock"));
  }

  // --- helper: estrai il "nome variabile" anche da this.mock o holder.mock ---
  private Optional<String> asSimpleVarName(Expression e) {
    if (e.isNameExpr()) {
      return Optional.of(e.asNameExpr().getNameAsString());
    }
    if (e.isFieldAccessExpr()) {
      // Prende la coda del field access: this.userRepo -> "userRepo", holder.userRepo
      // -> "userRepo"
      FieldAccessExpr fa = e.asFieldAccessExpr();
      return Optional.of(fa.getNameAsString());
    }
    return Optional.empty();
  }

  // varName -> typeSimpleName (es. "repo" -> "OrderRepository")
  private Map<String, String> collectMockAliases(CompilationUnit cu, MethodDeclaration md) {
    Map<String, String> out = new HashMap<>();

    // @Mock fields
    cu.findAll(FieldDeclaration.class).forEach(fd -> {
      if (!hasMockAnnotation(fd))
        return;
      String typeSimple = fd.getElementType().isClassOrInterfaceType()
          ? fd.getElementType().asClassOrInterfaceType().getName().getIdentifier()
          : fd.getElementType().asString();
      fd.getVariables().forEach(v -> out.put(v.getNameAsString(), typeSimple));
    });

    // locals: X x = mock(Foo.class);
    md.findAll(VariableDeclarationExpr.class).forEach(vde -> {
      for (VariableDeclarator vd : vde.getVariables()) {
        vd.getInitializer().ifPresent(init -> {
          if (!init.isMethodCallExpr())
            return;
          MethodCallExpr mc = init.asMethodCallExpr();
          if (!mc.getNameAsString().equals("mock"))
            return;
          if (mc.getArguments().size() != 1 || !mc.getArgument(0).isClassExpr())
            return;

          ClassExpr ce = mc.getArgument(0).asClassExpr();
          String typeSimple = ce.getType().isClassOrInterfaceType()
              ? ce.getType().asClassOrInterfaceType().getName().getIdentifier()
              : ce.getType().asString();
          out.put(vd.getNameAsString(), typeSimple);
        });
      }
    });

    return out;
  }

  // varName -> typeSimpleName (es. "spy" -> "List" o "ArrayList")
  private Map<String, String> collectSpyAliases(MethodDeclaration md) {
    Map<String, String> aliases = new HashMap<>();
    md.findAll(VariableDeclarationExpr.class).forEach(vde -> {
      for (VariableDeclarator vd : vde.getVariables()) {
        vd.getInitializer().ifPresent(init -> {
          if (!init.isMethodCallExpr())
            return;
          MethodCallExpr mc = init.asMethodCallExpr();
          if (!mc.getNameAsString().equals("spy"))
            return;

          String typeSimple = vd.getType().isClassOrInterfaceType()
              ? vd.getType().asClassOrInterfaceType().getName().getIdentifier()
              : vd.getType().asString();

          aliases.put(vd.getNameAsString(), typeSimple);
        });
      }
    });
    return aliases;
  }

  // =============== STUBBINGS (UNIFICATO: SOLO WHEN(...)) ===============
  private void findStubbings(
      List<MethodCallExpr> allCalls,
      Map<String, String> mockAliases,
      Map<String, String> spyAliases,
      Map<String, String> staticAliases,
      Map<String, Stats> stats) {

    for (MethodCallExpr call : allCalls) {
      if (!isWhenCall(call))
        continue;

      // VERIFICA PROFONDA: esistono MethodCallExpr dentro QUALSIASI argomento di
      // when(...)?
      boolean hasNestedMethodCall = call.getArguments().stream()
          .anyMatch(arg -> !arg.findAll(MethodCallExpr.class).isEmpty());

      if (hasNestedMethodCall) {
        // Stile A: when(mock.m(...)) oppure when(() -> StaticUtil.m(...)) (lambda)
        for (Expression arg : call.getArguments()) {
          for (MethodCallExpr inner : arg.findAll(MethodCallExpr.class)) {
            inner.getScope()
                .flatMap(this::asSimpleVarName) // "repo" / "this.repo" / "StaticUtil" /
                // "holder.repo"
                .ifPresent(var -> {
                  String key = normalizeKey(var, mockAliases, spyAliases, staticAliases);
                  if (stats.containsKey(key)) {
                    stats.get(key).stubbings++;
                  }
                });
          }
        }
      } else {
        // Stile B: doReturn(...).when(mock).m(...) -> arg0 è il mock
        if (!call.getArguments().isEmpty()) {
          asSimpleVarName(call.getArgument(0)).ifPresent(var -> {
            String key = normalizeKey(var, mockAliases, spyAliases, staticAliases);
            if (stats.containsKey(key)) {
              stats.get(key).stubbings++;
            }
          });
        }
      }
    }
  }

  private void findVerifications(
      List<MethodCallExpr> allCalls,
      Map<String, String> mockAliases,
      Map<String, String> spyAliases,
      Map<String, String> staticAliases,
      Map<String, Stats> stats) {

    for (MethodCallExpr call : allCalls) {
      if (isVerifyCall(call) && !call.getArguments().isEmpty()) {

        // Caso normale: verify(varMock, ...)
        Optional<String> arg0Var = asSimpleVarName(call.getArgument(0));
        if (arg0Var.isPresent()) {
          String key = normalizeKey(arg0Var.get(), mockAliases, spyAliases, staticAliases);
          if (stats.containsKey(key))
            stats.get(key).verifications++;
          continue;
        }

        // Caso MockedStatic: ms.verify(() -> StaticUtil.foo(...), times(..))
        call.getScope()
            .flatMap(this::asSimpleVarName) // "ms"
            .map(staticAliases::get) // "StaticUtil"
            .ifPresent(staticClass -> {
              if (stats.containsKey(staticClass))
                stats.get(staticClass).verifications++;
            });
      }
    }
  }

  private void findDirectCalls(
      List<MethodCallExpr> allCalls,
      Map<String, String> mockAliases,
      Map<String, String> spyAliases,
      Map<String, String> staticAliases,
      Map<String, Stats> stats) {

    for (MethodCallExpr call : allCalls) {
      // 1) salta tutto ciò che è nel contesto verify(...)
      if (inVerifyContext(call)) {
        // stai già contando le raw verify(mock) nel loop dedicato,
        // e non vuoi contare queste call come direct
        continue;
      }

      // 2) salta tutto ciò che è nel contesto when(...)
      if (inWhenContext(call)) {
        // gli stubbing sono già contati nel blocco unificato su when(...)
        continue;
      }

      // 3) altrimenti: direct call (mock.foo())
      call.getScope()
          .flatMap(this::asSimpleVarName)
          .ifPresent(var -> {
            String key = normalizeKey(var, mockAliases, spyAliases, staticAliases);
            if (stats.containsKey(key))
              stats.get(key).directCalls++;
          });

    }
  }

  // var MockedStatic -> staticClassSimpleName (es. "ms" -> "StaticUtil")
  // Inoltre ritorneremo anche la lista dei type da aggiungere ai mock noti
  private Map<String, String> collectStaticMockAliases(MethodDeclaration md) {
    Map<String, String> aliases = new HashMap<>();
    md.findAll(VariableDeclarationExpr.class).forEach(vde -> {
      for (VariableDeclarator vd : vde.getVariables()) {
        vd.getInitializer().ifPresent(init -> {
          if (!init.isMethodCallExpr())
            return;
          MethodCallExpr mc = init.asMethodCallExpr();
          if (!mc.getNameAsString().equals("mockStatic"))
            return;
          if (mc.getArguments().size() != 1 || !mc.getArgument(0).isClassExpr())
            return;

          ClassExpr ce = mc.getArgument(0).asClassExpr();
          String staticClassSimple = ce.getType().isClassOrInterfaceType()
              ? ce.getType().asClassOrInterfaceType().getName().getIdentifier()
              : ce.getType().asString();

          aliases.put(vd.getNameAsString(), staticClassSimple);
        });
      }
    });
    return aliases;
  }

  private String normalizeKey(String var,
      Map<String, String> mockAliases,
      Map<String, String> spyAliases,
      Map<String, String> staticAliases) {
    String k = var;
    // 1) variabili normali annotate/mock(Foo.class)
    k = mockAliases.getOrDefault(k, k);
    // 2) spy(...)
    k = spyAliases.getOrDefault(k, k);
    // 3) MockedStatic var -> nome classe statica
    k = staticAliases.getOrDefault(k, k);
    return k;
  }

  // ---- Identification helpers ----

  private boolean isWhenCall(MethodCallExpr mc) {
    return mc.getNameAsString().equals("when");
  }

  private boolean isVerifyCall(MethodCallExpr mc) {
    return mc.getNameAsString().equals("verify");
  }

  private boolean hasAncestorNamed(MethodCallExpr mc, String name) {
    String target = name.toLowerCase(Locale.ROOT);
    return mc.findAncestor(MethodCallExpr.class,
        anc -> anc != mc && anc.getNameAsString().toLowerCase(Locale.ROOT).equals(target)).isPresent();
  }

  // Siamo in un contesto when se: "when" è un antenato OPPURE è nella scope chain
  private boolean inWhenContext(MethodCallExpr mc) {
    return hasAncestorNamed(mc, "when") || hasScopeChainNamed(mc, "when");
  }

  // Cerca "verify" nell'albero degli antenati OPPURE lungo la catena degli scope.
  private boolean inVerifyContext(MethodCallExpr mc) {
    return hasAncestorNamed(mc, "verify") || hasScopeChainNamed(mc, "verify");
  }

  // Risale la catena degli scope (mc.getScope() -> MethodCallExpr -> ...)
  // e verifica se c'è una call di nome target.
  private boolean hasScopeChainNamed(MethodCallExpr mc, String name) {
    String target = name.toLowerCase(Locale.ROOT);
    Optional<Expression> scopeOpt = mc.getScope();
    while (scopeOpt.isPresent()) {
      Expression scope = scopeOpt.get();
      if (scope.isMethodCallExpr()) {
        MethodCallExpr smc = scope.asMethodCallExpr();
        if (smc.getNameAsString().toLowerCase(Locale.ROOT).equals(target)) {
          return true;
        }
        scopeOpt = smc.getScope(); // continua a risalire la catena
      } else {
        break; // NameExpr/FieldAccessExpr/ThisExpr: catena finita
      }
    }
    return false;
  }

  // ---- Confidence & Evidence ----
  // confidence = quanto siamo “sicuri/convinti” che un certo mock/tipo sia
  // davvero centrale nel test, in base alle evidenze raccolte.
  // Verify > Stubbing > Direct rispecchia il ciclo AAA (Arrange–Act–Assert):
  // verify(...) è Assert → il più indicativo: il test controlla davvero
  // l’interazione.
  // when(...) è Arrange → forte ma preparatorio: non garantisce che l’interazione
  // sia poi verificata.
  // calls dirette sono Act → uso osservato, ma non necessariamente “centrale” o
  // asserito.
  private double confidence(Stats st) {
    if (st.verifications > 0) {
      return Math.min(0.98, 0.90 + (st.verifications - 1) * 0.02);
    }
    if (st.stubbings > 0) {
      return Math.min(0.90, 0.80 + (st.stubbings - 1) * 0.02);
    }
    return Math.min(0.75, 0.60 + Math.max(0, st.directCalls - 1) * 0.02);
  }

  private String dominantLabel(Stats st) {
    if (st.verifications > 0)
      return "verified";
    if (st.stubbings > 0)
      return "stubbed";
    return "used";
  }

  private Map<String, Object> evidence(String var, Stats st, long totalInteractions) {
    long mockTotal = st.totalInteractions();
    double rel = mockTotal == 0 ? 0.0 : (double) mockTotal / (double) totalInteractions;
    return Map.of(
        "variable", var,
        "directCalls", st.directCalls,
        "stubbings", st.stubbings,
        "verifications", st.verifications,
        "totalInteractions", mockTotal,
        "relativeInteractionShare", round3(rel));
  }

  private HeuristicResult empty(String reason) {
    return new HeuristicResult(id(), "mock_usage", List.of(), Map.of("reason", reason));
  }

  // ---- Utils ----

  private double round3(double d) {
    return Math.round(d * 1000.0) / 1000.0;
  }

  private void configureSymbolSolver(Path modulePath) {
    if (StaticJavaParser.getConfiguration().getSymbolResolver().isPresent())
      return;
    CombinedTypeSolver solver = new CombinedTypeSolver();
    solver.add(new ReflectionTypeSolver(false));
    Path mainSrc = modulePath.resolve("src/main/java");
    Path testSrc = modulePath.resolve("src/test/java");
    if (Files.isDirectory(mainSrc))
      solver.add(new JavaParserTypeSolver(mainSrc));
    if (Files.isDirectory(testSrc))
      solver.add(new JavaParserTypeSolver(testSrc));
    StaticJavaParser.getConfiguration().setSymbolResolver(new JavaSymbolSolver(solver));
  }

  private Path resolveTestSource(Path modulePath, String fqn) {
    return modulePath.resolve("src/test/java").resolve(fqn.replace('.', '/') + ".java");
  }

  // ---- Internal structures ----
  private static final class Stats {
    long directCalls = 0;
    long stubbings = 0;
    long verifications = 0; // times a verified method was seen

    long totalInteractions() {
      return directCalls + stubbings + verifications;
    }
  }
}
