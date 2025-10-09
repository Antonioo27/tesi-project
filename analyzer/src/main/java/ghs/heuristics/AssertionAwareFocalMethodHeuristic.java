package ghs.heuristics;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;

import sootup.callgraph.CallGraph;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootMethod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import javax.management.relation.Role;

/**
 * AssertionAwareFocalMethodHeuristic (AST + SymbolSolving).
 *
 * Obiettivo:
 * - Analizzare il corpo del metodo di test via JavaParser.
 * - Individuare le chiamate assert* / verify* / check* / expect* / fail.
 * - Per ogni assert:
 * * Identificare metodi chiamati direttamente dentro gli argomenti (DIRECT).
 * * Se l'argomento è una variabile, risalire all'assegnazione precedente e se
 * contiene una method call → VARIABLE_PRODUCER.
 * - Risolvere con JavaSymbolSolver ogni MethodCallExpr candidato per ottenere:
 * * FQN della classe chiamata
 * * Nome metodo
 * * Numero (e tipi) parametri
 * - Mappare il risultato su un MethodSignature già presente nel call graph
 * (stesso FQN, nome, arity).
 * (Evitiamo di costruire manualmente nuove MethodSignature, riusiamo quelle
 * note a SootUp).
 *
 * Confidence (security):
 * - DIRECT: base 0.90
 * - VARIABLE_PRODUCER: base 0.75
 * - +0.05 per ogni occorrenza extra dello stesso produttore (cap 1.0)
 * - Caso unico produttore totale: 1.0
 *
 * metricId: assertion_focal_producers
 *
 * NOTE:
 * - Richiede dipendenze: javaparser-core + javaparser-symbol-solver.
 * - Risoluzione best-effort: se un metodo non viene risolto / non mappato nel
 * call graph → ignorato.
 * - Matching firma: (declaringClassFqn, methodName, paramCount).
 */
public final class AssertionAwareFocalMethodHeuristic implements Heuristic {

  // Identificativo dell'euristica
  @Override
  public String id() {
    return "assertion-focal-producers";
  }

  @Override
  public HeuristicResult run(HeuristicContext ctx) {
    // 1) Identifica test e file sorgente
    JavaSootMethod testMethod = ctx.testMethod();
    MethodSignature testSig = testMethod.getSignature();
    String testFqn = testSig.getDeclClassType().getFullyQualifiedName();
    String testMethodName = testSig.getName();

    Path sourceFile = resolveTestSource(ctx.modulePath(), testFqn);
    if (!Files.isRegularFile(sourceFile))
      return empty("source_not_found:" + sourceFile);

    // 2) Configura JavaParser + SymbolSolver (una sola volta)
    configureSymbolSolver(ctx.modulePath());

    // 3) Parse AST
    CompilationUnit cu; // variabile che conterra' l'AST radice
    try {
      cu = StaticJavaParser.parse(sourceFile);
    } catch (IOException e) {
      return empty("parse_error:" + e.getClass().getSimpleName());
    } catch (Exception e) {
      return empty("parse_failure");
    }

    // Una volta parsata le classi di test, andiamo a trovare il metodo nell AST
    // dato il nome del metodo di test
    Optional<MethodDeclaration> maybeMd = cu.findAll(MethodDeclaration.class).stream()
        .filter(m -> m.getNameAsString().equals(testMethodName))
        .filter(m -> m.getParameters().size() == testSig.getParameterTypes().size())
        .findFirst();
    if (maybeMd.isEmpty())
      return empty("method_not_found_in_source");
    MethodDeclaration md = maybeMd.get();

    // Lista delle asserzioni trovate nel test
    List<AssertSite> asserts = collectAsserts(md);
    if (asserts.isEmpty())
      return empty("no_assertions_found");

    CallGraph cg = ctx.callGraph();
    // Lista di metodi che il CHA vede chiamati dal test
    List<MethodSignature> outgoingFromTest = cg.callsFrom(testSig).stream()
        .map(edge -> edge.getTargetMethodSignature())
        .collect(Collectors.toList());

    // Costruiamo un indice per il match rapido : Index: FQN → methodName →
    // List<MethodSignature>, raggruppiamo per nome della classe chiamata, poi
    // metodi appartenenti a quella classe e lista di metodi chiamati dal metododo
    // in considerazione
    Map<String, Map<String, List<MethodSignature>>> index = buildIndex(outgoingFromTest);

    // Lista producers sono il conteggio di quante volte una specifica
    // MethodSignature
    // (cioè un metodo prod ben identificato da FQN) è stata catturata
    // durante la scansione di tutti gli argomenti di tutti gli assert nel metodo di
    // test corrente.
    Map<MethodSignature, ProducerInfo> producers = new LinkedHashMap<>();

    for (AssertSite as : asserts) {
      for (Expression arg : as.arguments()) {

        if (arg.isMethodCallExpr()) {
          processMethodCall(arg.asMethodCallExpr(), Role.DIRECT, null, ctx, index, producers);
          continue;
        }

        if (arg.isNameExpr()) {
          String var = arg.asNameExpr().getNameAsString();
          findProducerOfVar(as.block(), as.localIndex(), var)
              .ifPresent(mc -> processMethodCall(mc, Role.VARIABLE_PRODUCER, var, ctx, index, producers));
        }

        // eventuali chiamate annidate con scope (es. obj.foo().bar())
        arg.findAll(MethodCallExpr.class, mc -> mc.getScope().isPresent()).stream()
            .findFirst()
            .ifPresent(mc -> processMethodCall(mc, Role.DIRECT, null, ctx, index, producers));
      }
    }

    if (producers.isEmpty()) {
      return new HeuristicResult(id(), "assertion_focal_producers", List.of(),
          Map.of("reason", "assertions_present_no_producer_calls", "assertCount", asserts.size()));
    }

    long maxOcc = producers.values().stream().mapToLong(p -> p.occurrences).max().orElse(1L);
    List<Candidate<?>> candidates;

    // blocco costruisce la lista di candidates da restituire nell’HeuristicResult,
    // partendo dalla mappa producers (MethodSignature → ProducerInfo).
    if (producers.size() == 1) {
      var e = producers.entrySet().iterator().next();
      ProducerInfo pi = e.getValue();
      candidates = List.of(new Candidate<>(e.getKey(), 1.0, "single-producer",
          evidence(pi, maxOcc, asserts.size(), true)));
    } else {
      candidates = producers.entrySet().stream()
          // ordina producer
          .sorted((a, b) -> {
            int cmp = Long.compare(b.getValue().occurrences, a.getValue().occurrences);
            return (cmp != 0) ? cmp : a.getKey().toString().compareTo(b.getKey().toString());
          })
          .map(e -> {
            ProducerInfo pi = e.getValue();
            // se ci sono piu' producer calcoliamo la confidenza in base al ruolo e al
            // numero di occorrenze
            double conf = baseConfidence(pi.role);
            if (pi.occurrences > 1)
              conf = Math.min(1.0, conf + (pi.occurrences - 1) * 0.05);
            return new Candidate<>(e.getKey(), round3(conf),
                pi.role == Role.DIRECT ? "direct-in-assert" : "variable-producer",
                evidence(pi, maxOcc, asserts.size(), false));
          })
          .collect(Collectors.toList());
    }

    Map<String, Long> distribution = producers.entrySet().stream()
        .collect(LinkedHashMap::new,
            (m, e) -> m.put(e.getKey().toString(), e.getValue().occurrences),
            Map::putAll);

    double avg = candidates.stream().mapToDouble(Candidate::confidence).average().orElse(0);
    double max = candidates.stream().mapToDouble(Candidate::confidence).max().orElse(0);

    return new HeuristicResult(id(), "assertion_focal_producers", candidates,
        Map.of("assertCount", asserts.size(),
            "producerCount", producers.size(),
            "distribution", distribution,
            "avgSecurityConfidence", round3(avg),
            "maxSecurityConfidence", round3(max)));
  }

  private void processMethodCall(MethodCallExpr mc, Role role, String varName,
      HeuristicContext ctx,
      Map<String, Map<String, List<MethodSignature>>> index,
      Map<MethodSignature, ProducerInfo> producers) {

    // Qua trova il metodo di produzione invocato dal test, e lo mette nella
    // variabile resolved
    Optional<ResolvedMethodDeclaration> resolved = resolveSafely(mc);
    if (resolved.isEmpty())
      return;
    ResolvedMethodDeclaration r = resolved.get();

    String classFqn = r.declaringType().getQualifiedName();
    if (!ctx.projectProdClasses().contains(classFqn))
      return;

    int paramCount = r.getNumberOfParams();
    String methodName = r.getName();

    MethodSignature ms = matchExistingSignature(index, classFqn, methodName, paramCount)
        .orElse(null);
    if (ms == null)
      return;

    producers.compute(ms, (k, old) -> {
      if (old == null) {
        Set<String> vars = new LinkedHashSet<>();
        if (varName != null)
          vars.add(varName);
        return new ProducerInfo(role, 1L, vars);
      }
      Set<String> vars = new LinkedHashSet<>(old.variableNames);
      if (varName != null)
        vars.add(varName);
      return new ProducerInfo(role, old.occurrences + 1, vars);
    });
  }

  private Optional<ResolvedMethodDeclaration> resolveSafely(MethodCallExpr mc) {
    try {
      return Optional.of(mc.resolve());
    } catch (Throwable t) {
      return Optional.empty();
    }
  }

  private Map<String, Map<String, List<MethodSignature>>> buildIndex(List<MethodSignature> sigs) {
    Map<String, Map<String, List<MethodSignature>>> idx = new HashMap<>();
    for (MethodSignature ms : sigs) {
      String fqn = ms.getDeclClassType().getFullyQualifiedName();
      idx.computeIfAbsent(fqn, k -> new HashMap<>())
          .computeIfAbsent(ms.getName(), k -> new ArrayList<>())
          .add(ms);
    }
    return idx;
  }

  private Optional<MethodSignature> matchExistingSignature(
      Map<String, Map<String, List<MethodSignature>>> index,
      String classFqn, String methodName, int paramCount) {

    var byName = index.get(classFqn);
    if (byName == null)
      return Optional.empty();
    var list = byName.get(methodName);
    if (list == null)
      return Optional.empty();
    return list.stream()
        .filter(ms -> ms.getParameterTypes().size() == paramCount)
        .findFirst();
  }

  private List<Statement> methodStatements(MethodDeclaration md) {
    if (md.getBody().isEmpty())
      return List.of();
    // Istruzioni nel blocco del metodo
    return new ArrayList<>(md.getBody().get().getStatements());
  }

  // Produce una lista ordinata di AssertSite, uno per ogni chiamata assert-like
  // trovata nel test
  // Ogni assertsite ha l'indice dello statement in cui si trova l'assert, lo
  // statement in cui si trova,
  // la methodCallExpression che rappresenta la chiamata asser/verify/... trovata
  // ed infine la lista degli argomenti passati alla chiamata
  private List<AssertSite> collectAsserts(MethodDeclaration md) {
    List<AssertSite> out = new ArrayList<>();
    if (md.getBody().isEmpty())
      return out;
    for (Statement topLevel : methodStatements(md)) {
      for (MethodCallExpr mc : topLevel.findAll(MethodCallExpr.class)) {
        if (!isAssertionLike(mc))
          continue;

        // Statement che contiene *direttamente* la call (es. l’ExpressionStmt
        // dell’assert)
        Statement stmtOfCall = mc.findAncestor(Statement.class).orElse(topLevel);

        // Blocco che contiene quello statement (può essere il body del try, di un if,
        // ecc.)
        BlockStmt block = stmtOfCall.findAncestor(BlockStmt.class).orElse(null);

        int idx = -1;
        if (block != null) {
          List<Statement> siblings = block.getStatements();
          // indice locale all’interno del blocco
          for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i) == stmtOfCall) {
              idx = i;
              break;
            }
          }
        } else {
          // fallback: nessun blocco (rarissimo per un test); usa 0
          idx = 0;
        }

        out.add(new AssertSite(stmtOfCall, block, idx, mc, new ArrayList<>(mc.getArguments())));
      }
    }
    return out;
  }

  private Optional<MethodCallExpr> findProducerOfVar(BlockStmt startBlock, int beforeIndex, String var) {
    BlockStmt block = startBlock;
    int idx = beforeIndex;

    while (block != null && idx >= 0) {
      // scan all’indietro dentro 'block' a partire da idx-1
      for (int i = idx - 1; i >= 0; i--) {
        Statement st = block.getStatements().get(i);

        if (st instanceof ExpressionStmt es) {
          Expression expr = es.getExpression();

          // x = foo(...)
          if (expr.isAssignExpr()) {
            var assign = expr.asAssignExpr();
            if (assign.getTarget().isNameExpr()
                && assign.getTarget().asNameExpr().getNameAsString().equals(var)
                && assign.getValue().isMethodCallExpr()) {
              return Optional.of(assign.getValue().asMethodCallExpr());
            }
          }

          // Type x = foo(...); oppure var x = foo(...);
          if (expr.isVariableDeclarationExpr()) {
            var vde = expr.asVariableDeclarationExpr();
            var mc = vde.getVariables().stream()
                .filter(v -> v.getNameAsString().equals(var))
                .filter(v -> v.getInitializer().isPresent() && v.getInitializer().get().isMethodCallExpr())
                .map(v -> v.getInitializer().get().asMethodCallExpr())
                .findFirst();
            if (mc.isPresent())
              return mc;
          }
        }
      }

      // non trovato nel blocco corrente: risali
      // Trova il blocco padre e l’indice dello statement contenitore nel padre,
      // così da continuare a cercare "prima del contenitore".
      Optional<BlockStmt> maybeParentBlock = block.findAncestor(BlockStmt.class);
      if (maybeParentBlock.isEmpty())
        break;

      BlockStmt parent = maybeParentBlock.get();

      // statement "contenitore" del block corrente (TryStmt, IfStmt, WhileStmt, ecc.)
      Statement containerStmt = block.findAncestor(Statement.class).orElse(null);
      if (containerStmt == null)
        break;

      // nuovo idx: posizione del contenitore nel blocco padre
      int parentIdx = -1;
      List<Statement> parentStmts = parent.getStatements();
      for (int i = 0; i < parentStmts.size(); i++) {
        if (parentStmts.get(i) == containerStmt) {
          parentIdx = i;
          break;
        }
      }
      if (parentIdx < 0)
        break;

      block = parent;
      idx = parentIdx;
    }

    // Nessuna assegnazione/dichiarazione con method call trovata prima dell'assert
    return Optional.empty();
  }

  private Map<String, Object> evidence(ProducerInfo pi, long maxOcc, int totalAssertions, boolean det) {
    double rel = pi.occurrences / (double) maxOcc;
    return Map.of(
        "occurrences", pi.occurrences,
        "relativeToMax", rel,
        "role", pi.role.toString(),
        "variableNames", pi.variableNames.isEmpty() ? List.of() : pi.variableNames,
        "totalAssertionsAnalyzed", totalAssertions,
        "deterministic", det);
  }

  private HeuristicResult empty(String reason) {
    return new HeuristicResult(id(), "assertion_focal_producers", List.of(), Map.of("reason", reason));
  }

  private boolean isAssertionLike(MethodCallExpr mc) {
    String n = mc.getNameAsString().toLowerCase(Locale.ROOT);
    if (n.startsWith("assert") || n.startsWith("check") || n.startsWith("expect") || n.equals("fail"))
      return true;
    return mc.getScope()
        .map(s -> s.toString().toLowerCase(Locale.ROOT))
        .filter(sc -> sc.contains("assert") || sc.contains("junit"))
        .isPresent();
  }

  private double baseConfidence(Role r) {
    return (r == Role.DIRECT) ? 0.90 : 0.75;
  }

  private double round3(double d) {
    return Math.round(d * 1000.0) / 1000.0;
  }

  // Metodo che configura una sola volta il JavaSymbolSolver usato da JavaParser
  // per fare name/type/method resolution dentro l’AST del test.
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

  private enum Role {
    DIRECT, VARIABLE_PRODUCER
  }

  private static final class ProducerInfo {
    final Role role;
    final long occurrences;
    final Set<String> variableNames;

    ProducerInfo(Role role, long occurrences, Set<String> vars) {
      this.role = role;
      this.occurrences = occurrences;
      this.variableNames = vars;
    }
  }

  private record AssertSite(
      Statement stmt, // lo statement che contiene l’assert (non il TryStmt esterno, ma
                      // l’ExpressionStmt dell’assert)
      BlockStmt block, // il blocco che contiene 'stmt'
      int localIndex, // indice di 'stmt' dentro 'block'
      MethodCallExpr call, // la call assert/verify...
      List<Expression> arguments) {
  }
}