// src/main/java/ghs/analyzer/heuristics/AssertionAwareFocalMethodHeuristic.java
package ghs.analyzer.heuristics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import org.objectweb.asm.*;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootMethod;

public final class AssertionAwareFocalMethodHeuristic
  implements FocalMethodHeuristic {

  private final FocalMethodHeuristic fallback;
  private JavaSootMethod testMethod; // contesto
  private Path module; // contesto: root del modulo (per risalire a target/test-classes)

  public AssertionAwareFocalMethodHeuristic(FocalMethodHeuristic fallback) {
    this.fallback = fallback;
  }

  /** Deve essere chiamato prima di selectFocalMethod. */
  public void setContext(JavaSootMethod testMethod, Path module) {
    this.testMethod = testMethod;
    this.module = module;
  }

  @Override
  public Optional<MethodSignature> selectFocalMethod(
    String focalClassFqn,
    List<MethodSignature> candidatesOrdered
  ) {
    // Se manca il contesto, fallback
    if (testMethod == null || module == null) {
      return fallback.selectFocalMethod(focalClassFqn, candidatesOrdered);
    }

    // Costruisco mappa rapida: methodName -> lista di candidate con quel nome (gestisco overload scegliendo il primo in 'ordered')
    Map<String, List<MethodSignature>> byName = candidatesOrdered
      .stream()
      .collect(
        java.util.stream.Collectors.groupingBy(
          ms -> methodNameFromSubSig(ms.getSubSignature().toString()),
          java.util.LinkedHashMap::new,
          java.util.stream.Collectors.toList()
        )
      );

    String focalOwnerInternal = focalClassFqn.replace('.', '/');

    // Leggo il .class del test
    String testClassFqn = testMethod
      .getSignature()
      .getDeclClassType()
      .getFullyQualifiedName();
    String testMethodName = methodNameFromSubSig(
      testMethod.getSignature().getSubSignature().toString()
    );
    Path classFile = module
      .resolve("target")
      .resolve("test-classes")
      .resolve(testClassFqn.replace('.', '/') + ".class");

    if (!Files.isRegularFile(classFile)) {
      // Se per qualche motivo non trovo il .class, fallback
      return fallback.selectFocalMethod(focalClassFqn, candidatesOrdered);
    }

    // Scansione bytecode con logica di selezione intelligente
    final List<Invk> allFocalCalls = new ArrayList<>(); // tutte le invocazioni su focal class
    final Invk[] bestBeforeAssertion = new Invk[1]; // migliore invocazione prima di assertion
    final Invk[] lastFocalAnywhere = new Invk[1]; // ultima invocazione su focal in assoluto (fallback)

    try (InputStream in = Files.newInputStream(classFile)) {
      ClassReader cr = new ClassReader(in);
      cr.accept(
        new ClassVisitor(Opcodes.ASM9) {
          @Override
          public MethodVisitor visitMethod(
            int access,
            String name,
            String desc,
            String signature,
            String[] exceptions
          ) {
            // Seleziono *il* metodo di test: uso il nome; se ci sono overload con stesso nome, prendo il primo (best-effort)
            if (!Objects.equals(name, testMethodName)) return null;

            return new MethodVisitor(Opcodes.ASM9) {
              int idx = 0; // indice istruzioni INVOKE

              @Override
              public void visitMethodInsn(
                int opcode,
                String owner,
                String mName,
                String mDesc,
                boolean itf
              ) {
                // Track tutte le INVOKE su focalClass
                if (owner.equals(focalOwnerInternal)) {
                  Invk call = new Invk(owner, mName, mDesc, idx);
                  allFocalCalls.add(call);
                  lastFocalAnywhere[0] = call;
                }

                // Se è una assert, analizza tutte le chiamate precedenti per trovare la migliore
                if (AssertionDetector.isAssertionOwnerInternal(owner)) {
                  bestBeforeAssertion[0] = selectBestFocalCall(
                    allFocalCalls,
                    testMethodName
                  );
                }
                idx++;
              }
            };
          }
        },
        0
      );
    } catch (IOException e) {
      return fallback.selectFocalMethod(focalClassFqn, candidatesOrdered);
    }

    Invk pick = (bestBeforeAssertion[0] != null)
      ? bestBeforeAssertion[0]
      : lastFocalAnywhere[0];
    if (pick == null) {
      // Nessuna invocazione alla focal nel body → fallback
      return fallback.selectFocalMethod(focalClassFqn, candidatesOrdered);
    }

    // Mappo per nome (nel caso di overload restituisco il primo in 'ordered')
    List<MethodSignature> withSameName = byName.getOrDefault(
      pick.name,
      List.of()
    );
    if (!withSameName.isEmpty()) {
      return Optional.of(withSameName.get(0));
    }

    // Se per qualche motivo il nome non matcha (poco probabile), fallback
    return fallback.selectFocalMethod(focalClassFqn, candidatesOrdered);
  }

  // ===== helpers =====

  /**
   * Seleziona la migliore chiamata a focal class tra quelle disponibili prima dell'assertion.
   * Applica logica intelligente per distinguere tra setup/getter e veri metodi focali.
   */
  private Invk selectBestFocalCall(List<Invk> allCalls, String testMethodName) {
    if (allCalls.isEmpty()) return null;

    // Estrai nome del test senza prefisso "test" per matching
    String testNameCore = extractTestNameCore(testMethodName);

    // Scoring: più alto = migliore candidato
    Invk bestCall = null;
    int bestScore = Integer.MIN_VALUE;

    for (Invk call : allCalls) {
      int score = scoreFocalCall(call, testNameCore);
      if (score > bestScore) {
        bestScore = score;
        bestCall = call;
      }
    }

    // Se il migliore candidato è comunque triviale, prova l'ultimo non-triviale
    if (bestCall != null && isTrivial(bestCall.name)) {
      for (int i = allCalls.size() - 1; i >= 0; i--) {
        Invk call = allCalls.get(i);
        if (!isTrivial(call.name)) {
          return call;
        }
      }
    }

    return bestCall;
  }

  /**
   * Assegna un punteggio a una chiamata focal per determinare quanto sia probabile
   * che sia il vero metodo focal (vs setup/getter).
   */
  private int scoreFocalCall(Invk call, String testNameCore) {
    int score = 0;
    String methodName = call.name;

    // BONUS: Nome del metodo matcha con il nome del test
    if (testNameCore != null && !testNameCore.isEmpty()) {
      if (methodName.toLowerCase().contains(testNameCore.toLowerCase())) {
        score += 50; // forte bonus per matching nome
      }
    }

    // MALUS: Metodi triviali (getter, setter, constructors, etc.)
    if (isTrivial(methodName)) {
      score -= 30;
    }

    // BONUS: Metodi che sembrano azioni (verbi comuni)
    if (isActionMethod(methodName)) {
      score += 20;
    }

    // BONUS: Posizione tarda nel test (probabilmente l'azione principale)
    score += call.index; // posizione più tarda = score più alto

    return score;
  }

  /**
   * Estrae il "core" del nome del test rimuovendo prefissi comuni come "test", "should", etc.
   */
  private String extractTestNameCore(String testMethodName) {
    if (testMethodName == null) return "";

    String name = testMethodName;

    // Rimuovi prefissi comuni
    if (name.startsWith("test")) {
      name = name.substring(4);
    } else if (name.startsWith("should")) {
      name = name.substring(6);
    } else if (name.startsWith("when")) {
      name = name.substring(4);
    }

    // Rimuovi underscore iniziali
    while (name.startsWith("_")) {
      name = name.substring(1);
    }

    return name;
  }

  /**
   * Determina se un metodo è "triviale" (getter, setter, constructor, etc.)
   */
  private boolean isTrivial(String methodName) {
    if (
      methodName.equals("<init>") || methodName.equals("<clinit>")
    ) return true;
    if (
      methodName.equals("toString") ||
      methodName.equals("equals") ||
      methodName.equals("hashCode") ||
      methodName.equals("close") ||
      methodName.equals("finalize")
    ) return true;

    // Getter pattern: getName, isActive, hasPermission
    if (
      (methodName.startsWith("get") &&
        methodName.length() >= 4 &&
        Character.isUpperCase(methodName.charAt(3))) ||
      (methodName.startsWith("is") &&
        methodName.length() >= 3 &&
        Character.isUpperCase(methodName.charAt(2))) ||
      (methodName.startsWith("has") &&
        methodName.length() >= 4 &&
        Character.isUpperCase(methodName.charAt(3)))
    ) {
      return true;
    }

    // Setter pattern: setName, setActive
    if (
      methodName.startsWith("set") &&
      methodName.length() >= 4 &&
      Character.isUpperCase(methodName.charAt(3))
    ) {
      return true;
    }

    return false;
  }

  /**
   * Determina se un metodo sembra essere un'azione (verbo)
   */
  private boolean isActionMethod(String methodName) {
    // Lista di verbi comuni che indicano azioni
    String[] actionVerbs = {
      "calculate",
      "compute",
      "process",
      "execute",
      "run",
      "perform",
      "create",
      "build",
      "generate",
      "make",
      "construct",
      "save",
      "store",
      "persist",
      "write",
      "update",
      "modify",
      "delete",
      "remove",
      "clear",
      "clean",
      "destroy",
      "validate",
      "verify",
      "check",
      "test",
      "evaluate",
      "transform",
      "convert",
      "parse",
      "format",
      "encode",
      "decode",
      "send",
      "receive",
      "transmit",
      "handle",
      "manage",
      "control",
      "start",
      "stop",
      "begin",
      "end",
      "finish",
      "complete",
      "add",
      "subtract",
      "multiply",
      "divide",
      "increment",
      "decrement",
    };

    String lower = methodName.toLowerCase();
    for (String verb : actionVerbs) {
      if (lower.startsWith(verb)) {
        return true;
      }
    }

    return false;
  }

  private static String methodNameFromSubSig(String subSig) {
    int par = subSig.indexOf('(');
    int sp = subSig.lastIndexOf(' ', par >= 0 ? par : subSig.length());
    if (par > 0 && sp >= 0 && par > sp) return subSig.substring(sp + 1, par);
    return subSig;
  }

  private static final class Invk {

    final String owner, name, desc;
    final int index;

    Invk(String owner, String name, String desc, int index) {
      this.owner = owner;
      this.name = name;
      this.desc = desc;
      this.index = index;
    }
  }
}
