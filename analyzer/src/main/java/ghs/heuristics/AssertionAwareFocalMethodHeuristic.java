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
 * call graph → (PATCH) ora usiamo un fallback testuale così da non perdere il
 * segnale.
 * - Matching firma: (declaringClassFqn, methodName, paramCount). (PATCH) Match
 * elastico (name+arity)
 * se il match rigido fallisce ed esiste un solo candidato nel pool diretto.
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
    CompilationUnit cu; // variabile che conterrà l'AST radice
    try {
      cu = StaticJavaParser.parse(sourceFile);
    } catch (IOException e) {
      return empty("parse_error:" + e.getClass().getSimpleName());
    } catch (Exception e) {
      return empty("parse_failure");
    }

    // Una volta parsate le classi di test, andiamo a trovare il metodo nell'AST
    // dato il nome del metodo di test
    Optional<MethodDeclaration> maybeMd = cu.findAll(MethodDeclaration.class).stream()
        .filter(m -> m.getNameAsString().equals(testMethodName))
        .filter(m -> m.getParameters().size() == testSig.getParameterTypes().size())
        .findFirst();
    if (maybeMd.isEmpty())
      return empty("method_not_found_in_source");
    MethodDeclaration md = maybeMd.get();

    // Mappa con i tipi locali (solo simple name): es. model -> GCModel
    Map<String, String> localTypes = collectLocalTypes(md);

    // Lista delle asserzioni trovate nel test
    List<AssertSite> asserts = collectAsserts(md);
    if (asserts.isEmpty())
      return empty("no_assertions_found");

    CallGraph cg = ctx.callGraph();
    // Lista di metodi che il CHA vede chiamati dal test (diretti, livello 1)
    List<MethodSignature> outgoingFromTest = cg.callsFrom(testSig).stream()
        .map(edge -> edge.getTargetMethodSignature())
        .collect(Collectors.toList());

    // Costruiamo un indice per il match rapido:
    // Index: FQN → methodName → List<MethodSignature>
    // (raggruppiamo per classe e poi per nome)
    Map<String, Map<String, List<MethodSignature>>> index = buildIndex(outgoingFromTest);

    // (PATCH) Mappa dei producers: accettiamo sia MethodSignature (CG) che firma
    // testuale fallback.
    // Questo evita di perdere i producer annidati in lambda (es. assertThrows).
    Map<Object, ProducerInfo> producers = new LinkedHashMap<>();

    for (AssertSite as : asserts) {
      for (Expression arg : as.arguments()) {

        // 1) argomento = chiamata diretta
        if (arg.isMethodCallExpr()) {
          processMethodCall(arg.asMethodCallExpr(), Role.DIRECT, null, ctx, index, producers, testFqn, localTypes);
          continue;
        }

        // 2) argomento = variabile → risaliamo al producer (assegnazione/dichiarazione
        // con method call)
        if (arg.isNameExpr()) {
          String var = arg.asNameExpr().getNameAsString();
          findProducerOfVar(as.block(), as.localIndex(), var)
              .ifPresent(
                  mc -> processMethodCall(mc, Role.VARIABLE_PRODUCER, var, ctx, index, producers, testFqn, localTypes));
        }

        // 3) (fallback) cerca eventuali chiamate annidate con scope presente dentro
        // l’espressione
        // (es. catene obj.foo().bar())
        arg.findAll(MethodCallExpr.class, mc -> mc.getScope().isPresent()).stream()
            .findFirst()
            .ifPresent(mc -> processMethodCall(mc, Role.DIRECT, null, ctx, index, producers, testFqn, localTypes));
      }
    }

    if (producers.isEmpty()) {
      return new HeuristicResult(id(), "assertion_focal_producers", List.of(),
          Map.of("reason", "assertions_present_no_producer_calls", "assertCount", asserts.size()));
    }

    long maxOcc = producers.values().stream().mapToLong(p -> p.occurrences).max().orElse(1L);
    List<Candidate<?>> candidates;

    // blocco costruisce la lista di candidates da restituire nell’HeuristicResult,
    // partendo dalla mappa producers (Object → ProducerInfo).
    if (producers.size() == 1) {
      var e = producers.entrySet().iterator().next();
      ProducerInfo pi = e.getValue();
      candidates = List.of(new Candidate<>(e.getKey(), 1.0, "single-producer",
          evidence(pi, maxOcc, asserts.size(), true)));
    } else {
      candidates = producers.entrySet().stream()
          // ordina producer per occorrenze desc, poi per stringa chiave
          .sorted((a, b) -> {
            int cmp = Long.compare(b.getValue().occurrences, a.getValue().occurrences);
            return (cmp != 0) ? cmp : a.getKey().toString().compareTo(b.getKey().toString());
          })
          .map(e -> {
            ProducerInfo pi = e.getValue();
            // se ci sono più producer calcoliamo la confidenza in base al ruolo e al
            // numero di occorrenze (+0.05 per ripetizione, cap a 1.0)
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

  /**
   * (PATCH) processMethodCall: ora supporta
   * - match "rigido" su CG
   * - match "elastico" (name+arity) se unico
   * - fallback testuale se l’indice CG non permette mapping (es. lambda in
   * assertThrows)
   */
  private void processMethodCall(MethodCallExpr mc,
      Role role,
      String varName,
      HeuristicContext ctx,
      Map<String, Map<String, List<MethodSignature>>> index,
      Map<Object, ProducerInfo> producers,
      String testFqn,
      Map<String, String> localTypes) {

    // NON riassegniamo 'mc': creiamo un nuovo riferimento 'target' da usare ovunque
    final MethodCallExpr target = pickProjectOwnedCall(mc, ctx, index, testFqn);

    // Prova la risoluzione simbolica
    Optional<ResolvedMethodDeclaration> resolved = resolveSafely(target);
    if (resolved.isEmpty()) {
      // --- Fallback “no-resolve” #1: scope è una variabile locale -----------------
      // es: "model.size()" -> scope "model" -> localTypes.get("model") == "GCModel"
      Optional<String> maybeScopeVar = target.getScope()
          .filter(Expression::isNameExpr)
          .map(s -> s.asNameExpr().getNameAsString());

      if (maybeScopeVar.isPresent()) {
        String scopeVar = maybeScopeVar.get();
        String typeSimple = localTypes.get(scopeVar); // es. "GCModel"
        if (typeSimple != null) {
          // Cerca nell'indice le classi col simple name uguale e lo stesso metodo+arity
          List<MethodSignature> pool = new ArrayList<>();
          for (var e : index.entrySet()) {
            String fqn = e.getKey();
            String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
            if (simple.equals(typeSimple)) {
              var byName = e.getValue().get(target.getNameAsString());
              if (byName != null)
                pool.addAll(byName);
            }
          }

          List<MethodSignature> arity = pool.stream()
              .filter(ms -> ms.getParameterTypes().size() == target.getArguments().size())
              .collect(Collectors.toList());

          if (arity.size() == 1) {
            MethodSignature ms = arity.get(0);
            String fqn = ms.getDeclClassType().getFullyQualifiedName();
            if (isProjectish(fqn, ctx, index, testFqn)) {
              producers.compute(ms, (k, old) -> mergeProducer(old, role, varName));
              return; // fallback riuscito
            }
          }
        }
      }

      // --- Fallback “no-resolve” #2: scope è "new Type(...)" ----------------------
      // es: "new Engine(dummyJobsDir).getProfileCxmlResource()"
      if (target.getScope().isPresent() && target.getScope().get() instanceof ObjectCreationExpr oce) {
        String typeSimple = oce.getType().getName().getIdentifier(); // "Engine"
        if (typeSimple != null && !typeSimple.isEmpty()) {
          // match nel pool CG: classi con simple name == Engine e metodo ==
          // target.getNameAsString()
          List<MethodSignature> pool = new ArrayList<>();
          for (var e : index.entrySet()) {
            String fqn = e.getKey();
            String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
            if (simple.equals(typeSimple)) {
              var byName = e.getValue().get(target.getNameAsString());
              if (byName != null)
                pool.addAll(byName);
            }
          }

          // filtra per stessa arity dell'invocazione
          List<MethodSignature> arity = pool.stream()
              .filter(ms -> ms.getParameterTypes().size() == target.getArguments().size())
              .collect(Collectors.toList());

          if (arity.size() == 1) {
            MethodSignature ms = arity.get(0);
            String fqn = ms.getDeclClassType().getFullyQualifiedName();
            if (isProjectish(fqn, ctx, index, testFqn)) {
              producers.compute(ms, (k, old) -> mergeProducer(old, role, varName));
              return; // fallback riuscito
            }
          }
        }
      }

      // Nessun fallback sicuro -> comportamento invariato
      return;
    }

    // --- Risolto con SymbolSolver: prosegui con match CG/elastico/fallback
    // testuale ---
    ResolvedMethodDeclaration r = resolved.get();
    String classFqn = r.declaringType().getQualifiedName();
    int paramCount = r.getNumberOfParams();
    String methodName = r.getName();

    // 1) match rigido su CG
    Optional<MethodSignature> matched = matchExistingSignature(index, classFqn, methodName, paramCount);

    // 2) match elastico (name+arity unico nel pool)
    if (matched.isEmpty()) {
      matched = findUniqueByNameArity(index, ctx, methodName, paramCount);
    }

    if (matched.isPresent()) {
      MethodSignature ms = matched.get();
      String fqn = ms.getDeclClassType().getFullyQualifiedName();
      if (isProjectish(fqn, ctx, index, testFqn)) {
        producers.compute(ms, (k, old) -> mergeProducer(old, role, varName));
        return;
      }
    }

    // 3) fallback testuale se la classe è “project-ish”
    if (isProjectish(classFqn, ctx, index, testFqn)) {
      String textual = toAngleSignature(r);
      producers.compute(textual, (k, old) -> mergeProducer(old, role, varName));
    }
  }

  // Unifica l’aggiornamento delle occorrenze e la raccolta dei nomi variabili
  // legati al producer
  private ProducerInfo mergeProducer(ProducerInfo old, Role role, String varName) {
    if (old == null) {
      Set<String> vars = new LinkedHashSet<>();
      if (varName != null)
        vars.add(varName);
      return new ProducerInfo(role, 1L, vars);
    }
    Set<String> vars = new LinkedHashSet<>(old.variableNames);
    if (varName != null)
      vars.add(varName);
    return new ProducerInfo(old.role, old.occurrences + 1, vars);
  }

  // Costruisce una firma testuale stile SootUp: "<com.pkg.Clazz: Ret
  // name(T1,T2,...)>"
  private String toAngleSignature(ResolvedMethodDeclaration r) {
    String cls = r.declaringType().getQualifiedName();
    String ret = r.getReturnType().describe();
    String name = r.getName();
    String params = java.util.stream.IntStream.range(0, r.getNumberOfParams())
        .mapToObj(i -> r.getParam(i).getType().describe())
        .collect(Collectors.joining(","));
    return "<" + cls + ": " + ret + " " + name + "(" + params + ")>";
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

  /**
   * (PATCH) Match "elastico": se il match per FQN fallisce, cerchiamo per (name,
   * arity)
   * tra tutte le classi che hanno quel metodo uscito dal test.
   * Se esiste un solo candidato ed è di progetto → usalo.
   */
  private Optional<MethodSignature> findUniqueByNameArity(
      Map<String, Map<String, List<MethodSignature>>> index,
      HeuristicContext ctx,
      String methodName,
      int paramCount) {

    List<MethodSignature> pool = new ArrayList<>();
    for (var byName : index.values()) {
      var list = byName.get(methodName);
      if (list != null)
        pool.addAll(list);
    }
    if (pool.isEmpty())
      return Optional.empty();

    List<MethodSignature> arity = pool.stream()
        .filter(ms -> ms.getParameterTypes().size() == paramCount)
        .collect(Collectors.toList());

    if (arity.size() == 1) {
      MethodSignature only = arity.get(0);
      String fqn = only.getDeclClassType().getFullyQualifiedName();
      if (ctx.projectProdClasses().contains(fqn)) {
        return Optional.of(only);
      }
    }
    return Optional.empty();
  }

  private List<Statement> methodStatements(MethodDeclaration md) {
    if (md.getBody().isEmpty())
      return List.of();
    // Istruzioni nel blocco del metodo
    return new ArrayList<>(md.getBody().get().getStatements());
  }

  // Produce una lista ordinata di AssertSite, uno per ogni chiamata assert-like
  // trovata nel test
  // Ogni AssertSite ha:
  // - lo statement che contiene la call (ExpressionStmt dell’assert)
  // - il blocco che contiene lo statement (può essere il body di un
  // try/if/while/...)
  // - l’indice "locale" dello statement dentro il blocco (serve per la risalita
  // ai producer)
  // - la MethodCallExpr dell’assert
  // - la lista degli argomenti passati alla chiamata
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

  // Considera "di progetto" se:
  // 1) è nelle projectProdClasses()
  // 2) O compare nell'indice delle chiamate dirette del test (CG livello 1)
  // 3) (opzionale) condivide il root package con la test class (utile in
  // monorepo)
  private boolean isProjectish(String fqn,
      HeuristicContext ctx,
      Map<String, Map<String, List<MethodSignature>>> index,
      String testFqn) {

    if (ctx.projectProdClasses().contains(fqn))
      return true;
    if (index.containsKey(fqn))
      return true; // visto nel CG → buon candidato

    // opzionale: stesso root package (es. "com.graphhopper")
    int firstDot = testFqn.indexOf('.');
    int secondDot = firstDot < 0 ? -1 : testFqn.indexOf('.', firstDot + 1);
    if (firstDot > 0 && secondDot > firstDot) {
      String root = testFqn.substring(0, secondDot); // "com.graphhopper"
      if (fqn.startsWith(root + "."))
        return true;
    }
    return false;
  }

  // Estrae i tipi dichiarati localmente nel metodo: "GCModel model = ...;" ->
  // localTypes.put("model","GCModel")
  private Map<String, String> collectLocalTypes(MethodDeclaration md) {
    Map<String, String> out = new HashMap<>();
    md.findAll(VariableDeclarationExpr.class).forEach(vde -> {
      String typeSimple = vde.getElementType().isClassOrInterfaceType()
          ? vde.getElementType().asClassOrInterfaceType().getName().getIdentifier()
          : vde.getElementType().asString();
      vde.getVariables().forEach(v -> out.put(v.getNameAsString(), typeSimple));
    });
    return out;
  }

  // Cerca dentro mc (se stesso + annidate) la prima call il cui declaring type è
  // "project-ish".
  private MethodCallExpr pickProjectOwnedCall(MethodCallExpr mc,
      HeuristicContext ctx,
      Map<String, Map<String, List<MethodSignature>>> index,
      String testFqn) {
    List<MethodCallExpr> all = new ArrayList<>();
    all.add(mc);
    all.addAll(mc.findAll(MethodCallExpr.class)); // include anche mc

    return all.stream()
        .sorted((a, b) -> {
          var pa = a.getRange().map(r -> r.begin).orElse(null);
          var pb = b.getRange().map(r -> r.begin).orElse(null);
          if (pa == null || pb == null)
            return 0;
          int cmp = Integer.compare(pa.line, pb.line);
          return (cmp != 0) ? cmp : Integer.compare(pa.column, pb.column);
        })
        .filter(c -> {
          var r = resolveSafely(c);
          if (r.isEmpty())
            return false;
          String fqn = r.get().declaringType().getQualifiedName();
          return isProjectish(fqn, ctx, index, testFqn);
        })
        .findFirst()
        .orElse(mc); // se non trovi call "di progetto", tieni l'originale
  }

  /**
   * Ricerca il "producer" (chiamata di metodo) che ha assegnato la variabile
   * 'var'
   * risalendo prima all’interno del blocco locale, poi (se serve) risalendo ai
   * blocchi padre.
   * Supporta scenari con try/catch/if/while nidificati.
   */
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
