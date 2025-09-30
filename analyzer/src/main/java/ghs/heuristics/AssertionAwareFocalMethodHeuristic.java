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
 * NOTE:
 * - setContext(testMethod, module) DEVE essere chiamato prima di
 * selectFocalMethod.
 * - Il mapping finale dai nomi trovati nel bytecode ai MethodSignature avviene
 * PER NOME: in caso di overload, restituisce il PRIMO in 'candidatesOrdered'.
 */
public final class AssertionAwareFocalMethodHeuristic implements FocalMethodHeuristic {

  private final FocalMethodHeuristic fallback; // euristica di ripiego
  private JavaSootMethod testMethod; // contesto: quale metodo di test ispezionare
  private Path module; // contesto: root del modulo (per trovare target/test-classes)

  public AssertionAwareFocalMethodHeuristic(FocalMethodHeuristic fallback) {
    this.fallback = fallback;
  }

  /**
   * Imposta il contesto del test corrente (deve essere chiamato ad ogni test).
   */
  public void setContext(JavaSootMethod testMethod, Path module) {
    this.testMethod = testMethod;
    this.module = module;
  }

  @Override
  public Optional<MethodSignature> selectFocalMethod(
      String focalClassFqn,
      List<MethodSignature> candidatesOrdered) {
    // Se manca il contesto, non possiamo leggere il bytecode -> fallback
    if (testMethod == null || module == null) {
      System.out.println("Warning: context not set for AssertionAwareFocalMethodHeuristic");
      return fallback.selectFocalMethod(focalClassFqn, candidatesOrdered);
    }

    // Gruppo i candidati per NOME, preservando l'ordine (LinkedHashMap):
    // se dal bytecode seleziono un "foo", qui prenderò il primo "foo" in 'ordered'.
    Map<String, List<MethodSignature>> byName = new LinkedHashMap<>();
    for (MethodSignature ms : candidatesOrdered) {
      String name = methodNameFromSubSig(ms.getSubSignature().toString());
      byName.computeIfAbsent(name, k -> new ArrayList<>()).add(ms);
    }

    // Nome interno (ASM) della focal class, con '/' al posto di '.'
    String focalOwnerInternal = focalClassFqn.replace('.', '/');

    // Preparo info sul metodo di test e path al relativo .class
    String testClassFqn = testMethod.getSignature().getDeclClassType().getFullyQualifiedName();
    String testMethodName = methodNameFromSubSig(testMethod.getSignature().getSubSignature().toString());
    Path classFile = module.resolve("target").resolve("test-classes")
        .resolve(testClassFqn.replace('.', '/') + ".class");

    if (!Files.isRegularFile(classFile)) {
      // Non trovo il .class del test -> fallback
      System.out.println("Warning: test class file not found: " + classFile);
      return fallback.selectFocalMethod(focalClassFqn, candidatesOrdered);
    }

    // Raccoglitori per le INVOKE osservate
    final List<Invk> allFocalCalls = new ArrayList<>(); // tutte le chiamate alla focal class
    final Invk[] bestBeforeAssertion = new Invk[1]; // la migliore prima dell'ultima assertion
    final Invk[] lastFocalAnywhere = new Invk[1]; // l'ultima chiamata alla focal vista (fallback)

    // === Lettura bytecode col visitor ASM ===
    try (InputStream in = Files.newInputStream(classFile)) {
      ClassReader cr = new ClassReader(in);
      cr.accept(new ClassVisitor(Opcodes.ASM9) {
        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
          // Considero solo *il* metodo di test (match per NOME; se ci fossero overload
          // omonimi, prendo il primo)
          if (!Objects.equals(name, testMethodName))
            return null;

          return new MethodVisitor(Opcodes.ASM9) {
            int idx = 0; // contatore delle INVOKE incontrate (proxy di "posizione" nel test)

            @Override
            public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean itf) {
              // 1) INVOKE verso la focal class -> traccia
              if (owner.equals(focalOwnerInternal)) {
                Invk call = new Invk(owner, mName, mDesc, idx);
                allFocalCalls.add(call);
                lastFocalAnywhere[0] = call;
              }

              // 2) INVOKE verso una libreria di assertion -> valuta la "migliore" tra quelle
              // viste fin qui
              if (AssertionDetector.isAssertionOwnerInternal(owner)) {
                bestBeforeAssertion[0] = selectBestFocalCall(allFocalCalls, testMethodName);
              }
              idx++;
            }
          };
        }
      }, 0);
    } catch (IOException e) {
      // Qualsiasi problema di I/O -> fallback
      return fallback.selectFocalMethod(focalClassFqn, candidatesOrdered);
    }

    // Scelta finale: preferisci la migliore prima dell'ULTIMA assertion; altrimenti
    // l'ultima ovunque.
    Invk pick = (bestBeforeAssertion[0] != null) ? bestBeforeAssertion[0] : lastFocalAnywhere[0];
    if (pick == null) {
      // Nessuna chiamata alla focal class nel metodo di test -> fallback
      return fallback.selectFocalMethod(focalClassFqn, candidatesOrdered);
    }

    // Rimappa per nome verso i candidati di SootUp (gestione overload: prendi il
    // primo in 'ordered')
    List<MethodSignature> withSameName = byName.getOrDefault(pick.name, List.of());
    if (!withSameName.isEmpty()) {
      return Optional.of(withSameName.get(0));
    }

    // Nome non trovato tra i candidati (raro: divergenze di classpath/bytecode) ->
    // fallback
    return fallback.selectFocalMethod(focalClassFqn, candidatesOrdered);
  }

  // ================= helpers =================

  /**
   * Sceglie la migliore INVOKE tra quelle alla focal class viste prima
   * dell'assertion.
   */
  private Invk selectBestFocalCall(List<Invk> allCalls, String testMethodName) {
    if (allCalls.isEmpty())
      return null;

    String testNameCore = extractTestNameCore(testMethodName);
    int lastIdx = allCalls.get(allCalls.size() - 1).index; // ultima invocazione prima dell'assert

    Invk bestCall = null;
    int bestScore = Integer.MIN_VALUE;

    for (Invk call : allCalls) {
      int score = scoreFocalCall(call, testNameCore, lastIdx); // <-- passa lastIdx
      if (score > bestScore) {
        bestScore = score;
        bestCall = call;
      }
    }

    // Fallback anti-trivial già presente
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
  // Overload con lastIdx
  private int scoreFocalCall(Invk call, String testNameCore, int lastIdx) {
    int score = 0;
    String methodName = call.name;

    // BONUS: match col nome del test
    if (testNameCore != null && !testNameCore.isEmpty()
        && methodName.toLowerCase().contains(testNameCore.toLowerCase())) {
      score += 50;
    }

    // MALUS: trivial
    if (isTrivial(methodName)) {
      score -= 30;
    } else {
      // BONUS: metodo "azione"
      if (isActionMethod(methodName))
        score += 20;

      // BONUS DISCRETO: è proprio l'ultima invocazione su focal prima dell'assert
      if (call.index == lastIdx)
        score += 10; // ← nuovo bonus
    }

    // BONUS continuo: posizione più tarda
    score += call.index;

    return score;
  }

  /**
   * Estrae il "core" del nome del test rimuovendo prefissi comuni
   * (test/should/when) e underscore iniziali.
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
    for (String v : verbs)
      if (lower.startsWith(v))
        return true;
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
