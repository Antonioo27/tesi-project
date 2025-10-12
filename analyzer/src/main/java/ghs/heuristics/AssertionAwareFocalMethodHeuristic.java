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
 * elastico (name+arity) se il match rigido fallisce ed esiste un solo candidato
 * nel pool diretto.
 */
public final class AssertionAwareFocalMethodHeuristic implements Heuristic {

  /** Toggle globale per i log di debug. */
  private static final boolean DEBUG = true;

  private static void debug(String fmt, Object... args) {
    if (!DEBUG)
      return;
    System.out.printf(fmt + "%n", args);
  }

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

    debug("[RUN] testFqn=%s method=%s params=%d module=%s",
        testFqn, testMethodName, testSig.getParameterTypes().size(), ctx.modulePath());

    Path sourceFile = resolveTestSource(ctx.modulePath(), testFqn);
    if (!Files.isRegularFile(sourceFile)) {
      debug("[RUN] Source NOT FOUND: %s", sourceFile);
      return empty("source_not_found:" + sourceFile);
    }

    // 2) Configura JavaParser + SymbolSolver (una sola volta)
    configureSymbolSolver(ctx.modulePath());

    // 3) Parse AST
    CompilationUnit cu; // AST radice
    try {
      cu = StaticJavaParser.parse(sourceFile);
    } catch (IOException e) {
      debug("[RUN] parse_error: %s", e.getClass().getSimpleName());
      return empty("parse_error:" + e.getClass().getSimpleName());
    } catch (Exception e) {
      debug("[RUN] parse_failure");
      return empty("parse_failure");
    }

    // Trova il metodo di test nell'AST
    Optional<MethodDeclaration> maybeMd = cu.findAll(MethodDeclaration.class).stream()
        .filter(m -> m.getNameAsString().equals(testMethodName))
        .filter(m -> m.getParameters().size() == testSig.getParameterTypes().size())
        .findFirst();
    if (maybeMd.isEmpty()) {
      debug("[RUN] method_not_found_in_source");
      return empty("method_not_found_in_source");
    }
    MethodDeclaration md = maybeMd.get();

    // Mappa dei tipi locali (simple name): es. model -> GCModel
    Map<String, String> localTypes = collectLocalTypes(md);
    debug("[RUN] localTypes=%s", localTypes);

    // Raccogli tutte le chiamate assert-like
    List<AssertSite> asserts = collectAsserts(md);
    if (asserts.isEmpty()) {
      debug("[RUN] no_assertions_found");
      return empty("no_assertions_found");
    }
    debug("[RUN] asserts found: %d", asserts.size());

    // Call-graph: metodi chiamati direttamente dal test (livello 1)
    CallGraph cg = ctx.callGraph();
    List<MethodSignature> outgoingFromTest = cg.callsFrom(testSig).stream()
        .map(edge -> edge.getTargetMethodSignature())
        .collect(Collectors.toList());

    // Indice rapido: FQN -> methodName -> List<MethodSignature>
    Map<String, Map<String, List<MethodSignature>>> index = buildIndex(outgoingFromTest);
    debug("[RUN] CG outgoing targets: %d classes", index.size());

    // NOTA IMPORTANTE:
    // projectishSet DEVE contenere solo classi “di progetto” (certe)
    // - projectProdClasses() (fonte autorevole)
    // - + classi presenti come file in src/main/java
    // NON aggiungiamo più index.keySet() (CG) qui, altrimenti passano anche
    // librerie (es. java.util.List)
    Set<String> projectishSet = new HashSet<>(ctx.projectProdClasses());
    projectishSet.addAll(scanMainSourcesForFqns(ctx.modulePath())); // sorgenti main rilevati dal filesystem

    // NUOVO: aggiungi le classi viste dal test nel CG, ma filtra le librerie
    projectishSet.addAll(
        index.keySet().stream()
            .filter(fqn -> !isClearlyLibrary(fqn))
            .collect(Collectors.toSet()));
    debug("[RUN] projectishSet size=%d", projectishSet.size());

    // Mappa producers: MethodSignature o firma testuale fallback
    Map<Object, ProducerInfo> producers = new LinkedHashMap<>();

    // crea un set per questa analisi del singolo test
    Set<String> promotedVars = new HashSet<>();

    for (AssertSite as : asserts) {
      debug("[LOOP ASSERT] name=%s args=%d idx=%d range=%s",
          as.call().getNameAsString(), as.arguments().size(), as.localIndex(),
          as.stmt().getRange().orElse(null));

      for (Expression arg : as.arguments()) {

        // 1) argomento = chiamata diretta
        if (arg.isMethodCallExpr()) {
          debug("  [ARG] direct call: %s", arg);
          processMethodCall(arg.asMethodCallExpr(), Role.DIRECT, null,
              ctx, index, producers, testFqn, localTypes, projectishSet, ctx.modulePath(),
              as.block(), as.localIndex(), 0, promotedVars);
          continue;
        }

        // 2) argomento = variabile -> risaliamo all'assegnazione che la produce
        if (arg.isNameExpr()) {
          String var = arg.asNameExpr().getNameAsString();
          debug("  [ARG] variable: %s", var);

          // primo tentativo: risalita nel blocco e nei parent
          Optional<MethodCallExpr> prod = findProducerOfVar(as.block(), as.localIndex(), var);

          // FALLBACK: se non trovato, cerca in tutto il metodo la
          // dichiarazione/assegnazione più vicina (e precedente) all'assert.
          if (prod.isEmpty()) {
            prod = findProducerOfVarLoose(md, as.stmt(), var);
          }

          prod.ifPresent(mc -> {
            debug("    [PRODUCER] found for var=%s -> %s", var, mc);
            processMethodCall(mc, Role.VARIABLE_PRODUCER, var,
                ctx, index, producers, testFqn, localTypes, projectishSet, ctx.modulePath(),
                as.block(), as.localIndex(), 0, promotedVars);
          });
        }

        // 3) fallback: catene annidate con scope presente (es. obj.foo().bar())
        arg.findAll(MethodCallExpr.class, m -> m.getScope().isPresent()).stream().findFirst()
            .ifPresent(m -> {
              debug("  [ARG] nested with scope: %s", m);
              processMethodCall(m, Role.DIRECT, null,
                  ctx, index, producers, testFqn, localTypes, projectishSet, ctx.modulePath(),
                  as.block(), as.localIndex(), 0, promotedVars);
            });
      }
    }

    if (producers.isEmpty()) {
      debug("[RUN] producers EMPTY. assertions=%d", asserts.size());
      return new HeuristicResult(id(), "assertion_focal_producers", List.of(),
          Map.of("reason", "assertions_present_no_producer_calls", "assertCount", asserts.size()));
    }

    long maxOcc = producers.values().stream().mapToLong(p -> p.occurrences).max().orElse(1L);
    List<Candidate<?>> candidates;

    if (producers.size() == 1) {
      var e = producers.entrySet().iterator().next();
      ProducerInfo pi = e.getValue();
      debug("[RUN] single producer -> %s", e.getKey());
      candidates = List.of(new Candidate<>(e.getKey(), 1.0, "single-producer",
          evidence(pi, maxOcc, asserts.size(), true)));
    } else {
      candidates = producers.entrySet().stream()
          .sorted((a, b) -> {
            ProducerInfo pa = a.getValue();
            ProducerInfo pb = b.getValue();

            // (1) Prima i project-ish
            int pjCmp = Boolean.compare(pb.projectish, pa.projectish);
            if (pjCmp != 0)
              return pjCmp;

            // (2) Se nessuno dei due è project-ish, preferisci VARIABLE_PRODUCER
            if (!pa.projectish && !pb.projectish) {
              int roleCmp = Integer.compare(
                  (pb.role == Role.VARIABLE_PRODUCER) ? 1 : 0,
                  (pa.role == Role.VARIABLE_PRODUCER) ? 1 : 0);
              if (roleCmp != 0)
                return roleCmp;
            }

            // (3) Più occorrenze vince
            int occCmp = Long.compare(pb.occurrences, pa.occurrences);
            if (occCmp != 0)
              return occCmp;

            // (4) Tiebreaker deterministico
            return a.getKey().toString().compareTo(b.getKey().toString());
          })
          .map(e -> {
            ProducerInfo pi = e.getValue();
            double conf = baseConfidence(pi.role);
            if (!pi.projectish) {
              // leggera penalità per metodi libreria (es. size(), get(), isEmpty())
              conf = Math.max(0.55, conf - 0.15);
            }
            if (pi.occurrences > 1)
              conf = Math.min(1.0, conf + (pi.occurrences - 1) * 0.05);

            debug("[RUN] candidate %s conf=%.3f role=%s occ=%d proj=%s",
                e.getKey(), conf, pi.role, pi.occurrences, pi.projectish);

            return new Candidate<>(
                e.getKey(),
                round3(conf),
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

    debug("[RUN] producers=%d candidates=%d avgConf=%.3f maxConf=%.3f",
        producers.size(), candidates.size(), avg, max);

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
  private void processMethodCall(
      MethodCallExpr mc,
      Role role,
      String varName,
      HeuristicContext ctx,
      Map<String, Map<String, List<MethodSignature>>> index,
      Map<Object, ProducerInfo> producers,
      String testFqn,
      Map<String, String> localTypes,
      Set<String> projectishSet,
      Path modulePath,
      BlockStmt assertBlock,
      int assertLocalIdx,
      int depth,
      Set<String> promotedVars) {

    // stop ricorsione
    if (depth > 6) {
      debug("      [PROCESS] depth>6 stop. mc=%s", mc);
      return;
    }

    final MethodCallExpr target = pickProjectOwnedCall(mc, ctx, index, testFqn, projectishSet, modulePath);
    debug("      [PROCESS] role=%s var=%s depth=%d target=%s scope=%s",
        role, varName, depth, target, target.getScope().orElse(null));

    Optional<ResolvedMethodDeclaration> resolved = resolveSafely(target);

    // --- 1) Se risolve in libreria: promuovi lo scope verso il producer della var
    if (resolved.isPresent() && isClearlyLibrary(resolved.get().declaringType().getQualifiedName())) {
      debug("        [RESOLVE] library call in %s", resolved.get().declaringType().getQualifiedName());
      if (target.getScope().isPresent() && target.getScope().get().isNameExpr()) {
        String scopeVar = target.getScope().get().asNameExpr().getNameAsString();
        debug("        [PROMOTE] scope var=%s", scopeVar);
        if (promotedVars.add(scopeVar)) { // evita ripromozioni sulla stessa var
          Optional<MethodCallExpr> prod = findProducerOfVar(assertBlock, assertLocalIdx, scopeVar);
          if (prod.isPresent() && !sameRange(prod.get(), target)) {
            processMethodCall(prod.get(), Role.VARIABLE_PRODUCER, scopeVar,
                ctx, index, producers, testFqn, localTypes, projectishSet, modulePath,
                assertBlock, assertLocalIdx, depth + 1, promotedVars);
          } else {
            debug("        [PROMOTE] no producer found for var=%s", scopeVar);
          }
        }
      }
      return;
    }

    // --- 2) Se NON risolve: prova fallback scope-var e new Type(...)
    if (resolved.isEmpty()) {
      debug("        [RESOLVE] unresolved. Try fallbacks.");

      // 2a) scope è un NameExpr -> può essere VARIABILE oppure TIPO (call statica)
      if (target.getScope().isPresent() && target.getScope().get().isNameExpr()) {
        String scopeId = target.getScope().get().asNameExpr().getNameAsString();

        if (localTypes.containsKey(scopeId)) {
          // ==== CASO VARIABILE ====
          String typeSimple = localTypes.get(scopeId);
          debug("        [FALLBACK] variable scope=%s typeSimple=%s", scopeId, typeSimple);
          if (typeSimple != null) {
            List<MethodSignature> pool = new ArrayList<>();
            for (var e : index.entrySet()) {
              String fqn = e.getKey();
              String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
              var byName = e.getValue().get(target.getNameAsString());
              if (simple.equals(typeSimple) && byName != null)
                pool.addAll(byName);
            }
            List<MethodSignature> arity = pool.stream()
                .filter(ms -> ms.getParameterTypes().size() == target.getArguments().size())
                .collect(Collectors.toList());
            debug("        [FALLBACK] CG candidates by type+name+arity = %d", arity.size());
            if (arity.size() == 1) {
              MethodSignature ms = arity.get(0);
              String fqn = ms.getDeclClassType().getFullyQualifiedName();
              if (isProjectish(fqn, projectishSet, index, testFqn, modulePath)) {
                debug("        [ADD] CG match (var) -> %s", ms);
                producers.compute(ms, (k, o) -> mergeProducer(o, role, varName, fqn, true));
                return;
              }
            }
          }

          // Promuovi a producer della variabile (se esiste)
          debug("        [PROMOTE] search producer for var=%s", scopeId);
          if (promotedVars.add(scopeId)) {
            Optional<MethodCallExpr> prod = findProducerOfVar(assertBlock, assertLocalIdx, scopeId);
            if (prod.isPresent() && !sameRange(prod.get(), target)) {
              processMethodCall(prod.get(), Role.VARIABLE_PRODUCER, scopeId,
                  ctx, index, producers, testFqn, localTypes, projectishSet, modulePath,
                  assertBlock, assertLocalIdx, depth + 1, promotedVars);
            } else {
              debug("        [PROMOTE] none found for var=%s", scopeId);
            }
          }
          return;
        } else {
          // ==== CASO STATICO (TypeName.method(...)) ====
          debug("        [FALLBACK] static call scopeId=%s", scopeId);
          Optional<String> fqnOpt = findProjectFqnBySimple(scopeId, projectishSet, index, modulePath, testFqn);
          if (fqnOpt.isPresent()) {
            String fqn = fqnOpt.get();
            List<MethodSignature> pool = Optional.ofNullable(index.get(fqn))
                .map(map -> map.get(target.getNameAsString()))
                .orElse(List.of());
            List<MethodSignature> arity = pool.stream()
                .filter(ms -> ms.getParameterTypes().size() == target.getArguments().size())
                .collect(Collectors.toList());

            debug("        [FALLBACK] static CG candidates (name+arity) = %d", arity.size());

            if (arity.size() == 1) {
              MethodSignature ms = arity.get(0);
              debug("        [ADD] CG match (static) -> %s", ms);
              producers.compute(ms, (k, o) -> mergeProducer(o, role, varName, fqn, true));
              return;
            }

            String textual = "<" + fqn + ": ? " + target.getNameAsString() + "(?)>";
            debug("        [ADD] TEXT (static, ambiguous) -> %s", textual);
            producers.compute(textual, (k, o) -> mergeProducer(o, role, varName, fqn, true));
            return;
          } else {
            debug("        [FALLBACK] static scopeId=%s not mapped to project FQN", scopeId);
          }
          // se non troviamo un FQN di progetto, NON usciamo qui: lasciamo proseguire ad
          // altri fallback
        }
      }

      // 2b) scope = new Type(...)
      if (target.getScope().isPresent() && target.getScope().get() instanceof ObjectCreationExpr oce) {
        String typeSimple = oce.getType().getName().getIdentifier();
        debug("        [FALLBACK] new Type(...) typeSimple=%s", typeSimple);
        if (typeSimple != null && !typeSimple.isEmpty()) {
          List<MethodSignature> pool = new ArrayList<>();
          for (var e : index.entrySet()) {
            String fqn = e.getKey();
            String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
            var byName = e.getValue().get(target.getNameAsString());
            if (simple.equals(typeSimple) && byName != null)
              pool.addAll(byName);
          }
          List<MethodSignature> arity = pool.stream()
              .filter(ms -> ms.getParameterTypes().size() == target.getArguments().size())
              .collect(Collectors.toList());
          debug("        [FALLBACK] CG candidates by (new Type) = %d", arity.size());
          if (arity.size() == 1) {
            MethodSignature ms = arity.get(0);
            String fqn = ms.getDeclClassType().getFullyQualifiedName();
            if (isProjectish(fqn, projectishSet, index, testFqn, modulePath)) {
              debug("        [ADD] CG match (new Type) -> %s", ms);
              producers.compute(ms, (k, o) -> mergeProducer(o, role, varName, fqn, true));
              return;
            }
          }
        }
      }

      // nessun fallback sicuro
      debug("        [RESOLVE] no safe fallback matched");
      return;
    }

    // --- 3) Risolto e NON libreria: match CG / elastico / fallback testuale
    ResolvedMethodDeclaration r = resolved.get();
    String classFqn = r.declaringType().getQualifiedName();
    int paramCount = r.getNumberOfParams();
    String methodName = r.getName();
    debug("        [RESOLVE] OK %s.%s/%d", classFqn, methodName, paramCount);

    Optional<MethodSignature> matched = matchExistingSignature(index, classFqn, methodName, paramCount);
    if (matched.isEmpty()) {
      debug("        [MATCH] rigid miss → try elastic");
      matched = findUniqueByNameArity(index, ctx, methodName, paramCount);
    }

    if (matched.isPresent()) {
      MethodSignature ms = matched.get();
      String fqn = ms.getDeclClassType().getFullyQualifiedName();
      if (isProjectish(fqn, projectishSet, index, testFqn, modulePath)) {
        debug("        [ADD] CG match -> %s", ms);
        producers.compute(ms, (k, o) -> mergeProducer(o, role, varName, fqn, true));
        return;
      } else {
        debug("        [MATCH] present but not project-ish: %s", fqn);
      }
    }

    if (isProjectish(classFqn, projectishSet, index, testFqn, modulePath)) {
      String textual = toAngleSignature(r);
      debug("        [ADD] TEXT -> %s", textual);
      producers.compute(textual, (k, o) -> mergeProducer(o, role, varName, classFqn, true));
    } else {
      debug("        [SKIP] resolved class not project-ish: %s", classFqn);
    }
  }

  // helper per evitare ricorsione sullo stesso nodo
  private boolean sameRange(MethodCallExpr a, MethodCallExpr b) {
    return a.getRange().isPresent() && b.getRange().isPresent()
        && a.getRange().get().equals(b.getRange().get());
  }

  // Unifica l’aggiornamento delle occorrenze e la raccolta dei nomi variabili
  // legati al producer
  private ProducerInfo mergeProducer(ProducerInfo old, Role role, String varName, String fqn, boolean projectish) {
    if (old == null) {
      Set<String> vars = new LinkedHashSet<>();
      if (varName != null)
        vars.add(varName);
      return new ProducerInfo(role, 1L, vars, fqn, projectish);
    }
    // mantieni il migliore “projectish” se uno dei due lo è
    boolean pj = old.projectish || projectish;
    // mantieni fqn “di progetto” se disponibile, altrimenti quello esistente
    String keepFqn = pj && !old.projectish ? fqn : old.declaringFqn;
    Set<String> vars = new LinkedHashSet<>(old.variableNames);
    if (varName != null)
      vars.add(varName);
    return new ProducerInfo(old.role, old.occurrences + 1, vars, keepFqn, pj);
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
          // indice locale all’interno del blocco (confronto robusto per range + fallback
          // equals)
          for (int i = 0; i < siblings.size(); i++) {
            if (sameStmt(siblings.get(i), stmtOfCall)) {
              idx = i;
              break;
            }
          }
          if (idx < 0) {
            for (int i = 0; i < siblings.size(); i++) {
              if (siblings.get(i).equals(stmtOfCall)) {
                idx = i;
                break;
              }
            }
          }
          if (idx < 0) {
            // fallback d’emergenza: scansiona "tutto prima" (indice alla fine)
            idx = siblings.size();
          }
        } else {
          // fallback: nessun blocco (rarissimo per un test); usa 0
          idx = 0;
        }

        debug("[COLLECT] assert=%s idx=%d blockStatements=%d range=%s",
            mc.getNameAsString(), idx,
            (block != null ? block.getStatements().size() : -1),
            stmtOfCall.getRange().orElse(null));

        out.add(new AssertSite(stmtOfCall, block, idx, mc, new ArrayList<>(mc.getArguments())));
      }
    }
    return out;
  }

  private boolean isProjectish(String fqn,
      Set<String> projectishSet,
      Map<String, Map<String, List<MethodSignature>>> index,
      String testFqn,
      Path modulePath) {
    // 1) lista autorevole già costruita (prodClasses + src/main + CG filtrato)
    if (projectishSet.contains(fqn))
      return true;

    // 2) librerie note → mai project-ish
    if (isClearlyLibrary(fqn))
      return false;

    // 3) se ho il sorgente, è certamente progetto
    if (sourceExists(modulePath, fqn))
      return true;

    // 4) se è nel CG e condivide il root package → OK
    if (index.containsKey(fqn) && sharesRootPackage(fqn, testFqn))
      return true;

    // 5) NUOVO: anche se NON è nel CG, se condivide il root package col test → OK
    if (sharesRootPackage(fqn, testFqn))
      return true;

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

  // Sceglie, tra mc e le sue annidate, la prima call "di progetto" (ordinando per
  // posizione nel sorgente: la più a sinistra).
  private MethodCallExpr pickProjectOwnedCall(
      MethodCallExpr mc,
      HeuristicContext ctx,
      Map<String, Map<String, List<MethodSignature>>> index,
      String testFqn,
      Set<String> projectishSet,
      Path modulePath) {

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
          // prendi solo call “di progetto”, escludendo librerie (java.*, junit.*, ...)
          return isProjectish(fqn, projectishSet, index, testFqn, modulePath);
        })
        .findFirst()
        .orElse(mc); // se non trovi “progetto”, tieni l’originale (es. solo libreria)
  }

  // Ricava i FQN dei file in src/main/java senza fare parsing (solo dal path)
  private Set<String> scanMainSourcesForFqns(Path modulePath) {
    Set<String> out = new HashSet<>();
    Path mainSrc = modulePath.resolve("src/main/java");
    if (!Files.isDirectory(mainSrc))
      return out;
    try {
      Files.walk(mainSrc)
          .filter(p -> p.toString().endsWith(".java"))
          .forEach(p -> {
            Path rel = mainSrc.relativize(p);
            String fqn = rel.toString()
                .replace('\\', '/')
                .replace('/', '.')
                .replaceAll("\\.java$", "");
            out.add(fqn);
          });
    } catch (IOException ignored) {
    }
    return out;
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
              debug("      [FIND-PROD] tight assign var=%s -> %s", var, assign.getValue());
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
            if (mc.isPresent()) {
              debug("      [FIND-PROD] tight decl var=%s -> %s", var, mc.get());
              return mc;
            }
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

  private Optional<MethodCallExpr> findProducerOfVarLoose(MethodDeclaration md, Statement assertStmt, String var) {
    // Posizione dell'assert per assicurare "precedenza"
    var assertBegin = assertStmt.getRange().map(r -> r.begin).orElse(null);

    MethodCallExpr best = null;
    int bestLine = -1;

    // Scansiona tutte le ExpressionStmt del metodo
    for (ExpressionStmt es : md.findAll(ExpressionStmt.class)) {
      var esBegin = es.getRange().map(r -> r.begin).orElse(null);
      if (assertBegin != null && esBegin != null) {
        // prendi solo statement PRIMA dell'assert
        if (esBegin.isAfter(assertBegin))
          continue;
      }

      Expression expr = es.getExpression();

      // Caso 1: Dichiarazione "Type x = foo(...);"
      if (expr.isVariableDeclarationExpr()) {
        var vde = expr.asVariableDeclarationExpr();
        var maybe = vde.getVariables().stream()
            .filter(v -> v.getNameAsString().equals(var))
            .filter(v -> v.getInitializer().isPresent() && v.getInitializer().get().isMethodCallExpr())
            .map(v -> v.getInitializer().get().asMethodCallExpr())
            .findFirst();
        if (maybe.isPresent()) {
          int line = (esBegin != null) ? esBegin.line : Integer.MAX_VALUE;
          if (line > bestLine) {
            best = maybe.get();
            bestLine = line;
          }
          continue;
        }
      }

      // Caso 2: Assegnazione "x = foo(...);"
      if (expr.isAssignExpr()) {
        var assign = expr.asAssignExpr();
        if (assign.getTarget().isNameExpr()
            && assign.getTarget().asNameExpr().getNameAsString().equals(var)
            && assign.getValue().isMethodCallExpr()) {
          int line = (esBegin != null) ? esBegin.line : Integer.MAX_VALUE;
          if (line > bestLine) {
            best = assign.getValue().asMethodCallExpr();
            bestLine = line;
          }
        }
      }
    }

    if (best != null) {
      debug("      [FIND-PROD] loose var=%s -> %s (line=%d)", var, best, bestLine);
    }
    return Optional.ofNullable(best);
  }

  private Optional<String> findProjectFqnBySimple(
      String simple,
      Set<String> projectishSet,
      Map<String, Map<String, List<MethodSignature>>> index,
      Path modulePath,
      String testFqn) {
    List<String> candidates = new ArrayList<>();

    // 1) Dai sorgenti "di progetto" (set costruito da projectProdClasses +
    // scanMainSources)
    for (String fqn : projectishSet) {
      if (fqn.endsWith("." + simple)) {
        candidates.add(fqn);
      }
    }

    // 2) Se vuoto, prova dal CG ma filtra librerie e richiedi "project-ish"
    if (candidates.isEmpty()) {
      for (String fqn : index.keySet()) {
        if (fqn.endsWith("." + simple) && !isClearlyLibrary(fqn)
            && (sourceExists(modulePath, fqn) || sharesRootPackage(fqn, testFqn))) {
          candidates.add(fqn);
        }
      }
    }

    debug("        [STATIC-SIMPLE] simple=%s -> candidates=%s", simple, candidates);

    // Accetta solo se UNIVOCO
    if (candidates.size() == 1) {
      return Optional.of(candidates.get(0));
    }
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
    final String declaringFqn; // es. "org.apache.hertzbeat.alert.expr.AlertExpressionEvalVisitor"
    final boolean projectish; // true se di progetto (o “project-ish”)

    ProducerInfo(Role role, long occurrences, Set<String> vars, String fqn, boolean projectish) {
      this.role = role;
      this.occurrences = occurrences;
      this.variableNames = vars;
      this.declaringFqn = fqn;
      this.projectish = projectish;
    }

    ProducerInfo withOccurrenceInc(String maybeVar) {
      Set<String> vars = new LinkedHashSet<>(this.variableNames);
      if (maybeVar != null)
        vars.add(maybeVar);
      return new ProducerInfo(this.role, this.occurrences + 1, vars, this.declaringFqn, this.projectish);
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

  private boolean isClearlyLibrary(String fqn) {
    return fqn.startsWith("java.")
        || fqn.startsWith("javax.")
        || fqn.startsWith("jdk.")
        || fqn.startsWith("org.junit.")
        || fqn.startsWith("org.mockito.")
        || fqn.startsWith("kotlin.")
        || fqn.startsWith("scala.");
  }

  private boolean sourceExists(Path modulePath, String fqn) {
    Path p = modulePath.resolve("src/main/java").resolve(fqn.replace('.', '/') + ".java");
    return Files.isRegularFile(p);
  }

  private boolean sharesRootPackage(String fqn, String testFqn) {
    int d1 = testFqn.indexOf('.');
    int d2 = d1 < 0 ? -1 : testFqn.indexOf('.', d1 + 1);
    if (d1 <= 0 || d2 <= d1)
      return false;
    String root = testFqn.substring(0, d2); // es. "com.graphhopper"
    return fqn.startsWith(root + ".");
  }

  // Confronto robusto per capire se due Statement sono "lo stesso" nel sorgente.
  private boolean sameStmt(Statement a, Statement b) {
    if (a == b)
      return true;
    if (a == null || b == null)
      return false;
    return a.getRange().isPresent() && b.getRange().isPresent()
        && a.getRange().get().equals(b.getRange().get());
  }
}
