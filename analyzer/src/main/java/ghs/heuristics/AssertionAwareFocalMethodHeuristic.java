// src/main/java/ghs/analyzer/heuristics/AssertionAwareFocalMethodHeuristic.java
package ghs.heuristics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootMethod;

/**
 * Euristica "assertion-aware" per scegliere il focal method della focal class.
 *
 * Idea: nel corpo del metodo di test, osserva le INVOKE verso la focal class e
 * sceglie la più "significativa", tipicamente quella immediatamente PRIMA
 * dell'ultima assertion. Se non ci sono assertion, usa l'ultima INVOKE
 * alla focal class. In caso di errori/assenza di segnali, ricade su una
 * euristica di fallback (es. NameAndDistance...).
 *
 * Differenze rispetto alla versione precedente:
 * - NIENTE stato interno/setContext: tutto il contesto arriva in
 * FocalMethodContext.
 * - Integrata la “regola forte” prima presente in ChaCallGraphAnalyzer:
 * se esiste UNA SOLA chiamata DIRETTA dal test a un metodo della focal class
 * (e non è triviale), la si sceglie subito (fast-path). Altrimenti si
 * applica la logica assertion-aware.
 *
 * NOTE:
 * - Il mapping finale dai nomi trovati nel bytecode ai MethodSignature avviene
 * PER NOME: in caso di overload, restituisce il PRIMO in 'candidatesOrdered'.
 */
public final class AssertionAwareFocalMethodHeuristic implements FocalMethodHeuristic {

  @Override
  public Optional<MethodSignature> selectFocalMethod(FocalMethodContext ctx) {
    // Estrai il contesto necessario
    final List<MethodSignature> candidatesOrdered = ctx.candidatesOrdered();
    final Set<MethodSignature> directCallsFromTest = ctx.directCallsFromTest();
    final String focalClassFqn = ctx.focalClassFqn();
    final JavaSootMethod testMethod = ctx.testMethod();
    final Path module = ctx.module();

    // 0) Se non ci sono candidati, non ha senso ispezionare il bytecode → fallback
    if (candidatesOrdered == null || candidatesOrdered.isEmpty()) {
      return Optional.empty();
    }

    // 1) FAST-PATH: se c'è UNA SOLA chiamata DIRETTA del test a 1 metodo della
    // focal class
    // (e non è triviale), prendila subito. Questo cattura il caso AAA tipico.
    List<MethodSignature> directToFocal = new ArrayList<>();
    for (MethodSignature ms : candidatesOrdered) {
      if (directCallsFromTest != null && directCallsFromTest.contains(ms)) {
        directToFocal.add(ms);
      }
    }
    if (directToFocal.size() == 1) {
      MethodSignature only = directToFocal.get(0);
      String onlyName = methodNameFromSubSig(only.getSubSignature().toString());
      if (!isTrivial(onlyName)) {
        return Optional.of(only); // shortcut: unico diretto non triviale
      }
      // Se è triviale (es. getter), NON lo prendiamo alla cieca e passiamo
      // all'analisi assertion-aware
    }

    // 2) Senza testMethod/module non possiamo leggere il .class → fallback
    if (testMethod == null || module == null) {
      System.out.println("Warning: missing testMethod/module in FocalMethodContext; using fallback.");
      return Optional.empty(); // nessun candidato → niente da scegliere
    }

    // 3) Prepara strutture di supporto: byName per gestire overload (prendi il
    // primo in 'ordered')
    Map<String, List<MethodSignature>> byName = new LinkedHashMap<>();
    for (MethodSignature ms : candidatesOrdered) {
      String name = methodNameFromSubSig(ms.getSubSignature().toString());
      byName.computeIfAbsent(name, k -> new ArrayList<>()).add(ms);
    }

    // 4) Prepara info ASM
    String focalOwnerInternal = focalClassFqn.replace('.', '/');

    String testClassFqn = testMethod.getSignature()
        .getDeclClassType()
        .getFullyQualifiedName();
    String testMethodName = methodNameFromSubSig(
        testMethod.getSignature().getSubSignature().toString());
    Path classFile = module
        .resolve("target")
        .resolve("test-classes")
        .resolve(testClassFqn.replace('.', '/') + ".class");

    if (!Files.isRegularFile(classFile)) {
      System.out.println("Warning: test class file not found: " + classFile);
      return Optional.empty();
    }

    // 5) Scansione bytecode con logica di selezione intelligente
    final List<Invk> allFocalCalls = new ArrayList<>(); // tutte le invocazioni su focal class
    final Invk[] bestBeforeAssertion = new Invk[1]; // migliore invocazione prima di assertion
    final Invk[] lastFocalAnywhere = new Invk[1]; // ultima invocazione su focal in assoluto (fallback)

    try (InputStream in = Files.newInputStream(classFile)) {
      ClassReader cr = new ClassReader(in);
      cr.accept(new ClassVisitor(Opcodes.ASM9) {
        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
          // Considero solo il metodo di test target (match per NOME; in caso di overload
          // omonimi prendo il primo)
          if (!Objects.equals(name, testMethodName))
            return null;

          return new MethodVisitor(Opcodes.ASM9) {
            int idx = 0; // contatore INVOKE (proxy di "posizione" nel test)

            @Override
            public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean itf) {
              // Track tutte le INVOKE su focalClass
              if (owner.equals(focalOwnerInternal)) {
                Invk call = new Invk(owner, mName, mDesc, idx);
                allFocalCalls.add(call);
                lastFocalAnywhere[0] = call; // aggiorna sempre "l'ultima vista"
              }

              // Se è una assert, analizza le chiamate focal "viste fin qui" e scegli la
              // migliore
              if (AssertionDetector.isAssertionOwnerInternal(owner)) {
                bestBeforeAssertion[0] = selectBestFocalCall(allFocalCalls, testMethodName);
              }
              idx++;
            }
          };
        }
      }, 0);
    } catch (IOException e) {
      // Qualsiasi problema di I/O
      return Optional.empty();
    }

    // 6) Scelta finale:
    // - preferisci la migliore prima dell'ULTIMA assertion (se esiste),
    // - altrimenti l'ultima invocazione su focal ovunque (se esiste),
    // - altrimenti fallback.
    Invk pick = (bestBeforeAssertion[0] != null)
        ? bestBeforeAssertion[0]
        : lastFocalAnywhere[0];
    if (pick == null) {
      return Optional.empty();
    }

    // 7) Mappo il nome scelto sul set di candidati (gestione overload: prendi il
    // primo in 'ordered')
    List<MethodSignature> withSameName = byName.getOrDefault(pick.name, List.of());
    if (!withSameName.isEmpty()) {
      return Optional.of(withSameName.get(0));
    }

    // Nome non presente tra i candidati (raro: divergenze di classpath/bytecode)
    return Optional.empty();
  }

  // ================= helpers =================

  /**
   * Seleziona la migliore chiamata a focal class tra quelle disponibili PRIMA
   * dell'assertion.
   * Applica logica intelligente per distinguere tra setup/getter e veri metodi
   * focali.
   *
   * Criteri (scoreFocalCall):
   * - +50 se il nome del metodo contiene il "core" del nome del test (es.
   * shouldCompute → compute).
   * - −30 se è "triviale" (ctor, equals/toString, getter/setter/is/has, ecc.).
   * - +20 se "sembra azione" (prefix verbali noti: compute/process/validate...).
   * - +10 se è esattamente l'ULTIMA invocazione alla focal prima di
   * quell'assertion (bonus discreto).
   * - +index (bonus continuo: invocazioni più tarde pesano un po' di più).
   */
  private Invk selectBestFocalCall(List<Invk> allCalls, String testMethodName) {
    if (allCalls.isEmpty())
      return null;

    String testNameCore = extractTestNameCore(testMethodName);
    int lastIdx = allCalls.get(allCalls.size() - 1).index; // ultima invocazione prima dell'assert

    Invk bestCall = null;
    int bestScore = Integer.MIN_VALUE;

    for (Invk call : allCalls) {
      int score = scoreFocalCall(call, testNameCore, lastIdx);
      if (score > bestScore) {
        bestScore = score;
        bestCall = call;
      }
    }

    // Se il migliore candidato è comunque triviale, prova l'ultimo non-triviale
    if (bestCall != null && isTrivial(bestCall.name)) {
      for (int i = allCalls.size() - 1; i >= 0; i--) {
        Invk call = allCalls.get(i);
        if (!isTrivial(call.name))
          return call;
      }
    }
    return bestCall;
  }

  /** Punteggio euristico per una chiamata alla focal class. */
  private int scoreFocalCall(Invk call, String testNameCore, int lastIdx) {
    int score = 0;
    String methodName = call.name;

    // BONUS: match col "core" del nome del test
    if (testNameCore != null && !testNameCore.isEmpty()
        && methodName.toLowerCase().contains(testNameCore.toLowerCase())) {
      score += 50;
    }

    // MALUS: metodi triviali
    if (isTrivial(methodName)) {
      score -= 30;
    } else {
      // BONUS: metodo che "sembra azione"
      if (isActionMethod(methodName))
        score += 20;

      // BONUS DISCRETO: è proprio l'ultima invocazione su focal prima dell'assert
      if (call.index == lastIdx)
        score += 10;
    }

    // BONUS CONTINUO: posizione tarda nel test
    score += call.index;

    return score;
  }

  /**
   * Estrae il "core" del nome del test rimuovendo prefissi comuni
   * (test/should/when)
   * e underscore iniziali.
   */
  private String extractTestNameCore(String testMethodName) {
    if (testMethodName == null)
      return "";
    String name = testMethodName;
    if (name.startsWith("test"))
      name = name.substring(4);
    else if (name.startsWith("should"))
      name = name.substring(6);
    else if (name.startsWith("when"))
      name = name.substring(4);
    while (name.startsWith("_"))
      name = name.substring(1);
    return name;
  }

  /** Heuristica "trivial": costruttori, object helpers, getter/setter/has/is. */
  private boolean isTrivial(String methodName) {
    if (methodName.equals("<init>") || methodName.equals("<clinit>"))
      return true;
    if (methodName.equals("toString") || methodName.equals("equals") ||
        methodName.equals("hashCode") || methodName.equals("close") ||
        methodName.equals("finalize"))
      return true;

    // Getter/predicate: getX / isX / hasX (X maiuscola)
    if ((methodName.startsWith("get") && methodName.length() >= 4 && Character.isUpperCase(methodName.charAt(3))) ||
        (methodName.startsWith("is") && methodName.length() >= 3 && Character.isUpperCase(methodName.charAt(2))) ||
        (methodName.startsWith("has") && methodName.length() >= 4 && Character.isUpperCase(methodName.charAt(3)))) {
      return true;
    }
    // Setter: setX
    if (methodName.startsWith("set") && methodName.length() >= 4 && Character.isUpperCase(methodName.charAt(3))) {
      return true;
    }
    return false;
  }

  /** Riconosce metodi "azione" tramite prefissi verbali comuni. */
  private boolean isActionMethod(String methodName) {
    String[] verbs = {
        "calculate", "compute", "process", "execute", "run", "perform", "create", "build", "generate", "make",
        "construct",
        "save", "store", "persist", "write", "update", "modify", "delete", "remove", "clear", "clean", "destroy",
        "validate", "verify", "check", "test", "evaluate", "transform", "convert", "parse", "format", "encode",
        "decode",
        "send", "receive", "transmit", "handle", "manage", "control", "start", "stop", "begin", "end", "finish",
        "complete",
        "add", "subtract", "multiply", "divide", "increment", "decrement",
    };
    String lower = methodName.toLowerCase();
    for (String v : verbs) {
      if (lower.startsWith(v))
        return true;
    }
    return false;
  }

  /** Estrae il nome del metodo da una sub-signature "retType name(params)". */
  private static String methodNameFromSubSig(String subSig) {
    int par = subSig.indexOf('(');
    int sp = subSig.lastIndexOf(' ', par >= 0 ? par : subSig.length());
    if (par > 0 && sp >= 0 && par > sp)
      return subSig.substring(sp + 1, par);
    return subSig; // fallback
  }

  /** Mini-record delle INVOKE alla focal class viste nel test. */
  private static final class Invk {
    final String owner, name, desc;
    final int index; // posizione (contatore) tra le INVOKE del metodo di test

    Invk(String owner, String name, String desc, int index) {
      this.owner = owner;
      this.name = name;
      this.desc = desc;
      this.index = index;
    }
  }
}
