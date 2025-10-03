package ghs.heuristics;

import ghs.model.TestKind;
import java.util.*;
import java.util.stream.Collectors;
import sootup.callgraph.CallGraph;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootMethod;

/**
 * Classificatore avanzato di test che distingue tra UNIT e INTEGRATION test.
 * 
 * RESPONSABILITÀ:
 * - Analizza le chiamate dirette dal metodo di test alle classi del progetto
 * - Applica euristiche sofisticate per classificare il tipo di test
 * - Considera sia il numero di classi coinvolte che la concentrazione delle
 * chiamate
 * 
 * ALGORITMO:
 * 1. Estrae tutte le chiamate dirette alle classi di produzione del progetto
 * 2. Calcola metriche: numero classi uniche, concentrazione chiamate, etc.
 * 3. Applica regole di classificazione basate su soglie configurabili
 * 
 * CRITERI CLASSIFICATION:
 * - INTEGRATION: Molte classi coinvolte O bassa concentrazione su singola
 * classe
 * - UNIT: Poche classi (tipicamente 1) con alta concentrazione di chiamate
 */
public final class EnhancedHybridTestClassifier implements TestClassifier {
  // === PARAMETRI DI CONFIGURAZIONE ===

  /**
   * Numero minimo di classi progetto per considerare un test INTEGRATION
   * (default: 2)
   */
  private final int integrationMinProjectClasses;

  /**
   * Numero minimo di chiamate a metodi progetto per considerare un test
   * INTEGRATION (default: 6)
   */
  private final int integrationMinProjectMethods;

  /**
   * Soglia di concentrazione alta (0.0-1.0). Sopra questa soglia = UNIT test
   * (default: 0.8)
   */
  private final double highConcentrationThreshold;

  /**
   * Costruttore completo con tutti i parametri configurabili.
   * 
   * @param integrationMinProjectClasses Soglia classi per INTEGRATION (≥2
   *                                     raccomandato)
   * @param integrationMinProjectMethods Soglia metodi per INTEGRATION (≥6
   *                                     raccomandato)
   * @param highConcentrationThreshold   Soglia concentrazione per UNIT (0.8 = 80%
   *                                     chiamate su 1 classe)
   */
  public EnhancedHybridTestClassifier(
      int integrationMinProjectClasses,
      int integrationMinProjectMethods,
      double highConcentrationThreshold) {
    // Validazione parametri con valori minimi sensati
    this.integrationMinProjectClasses = Math.max(1, integrationMinProjectClasses);
    this.integrationMinProjectMethods = Math.max(1, integrationMinProjectMethods);
    this.highConcentrationThreshold = Math.max(0.0, Math.min(1.0, highConcentrationThreshold));
  }

  /**
   * Costruttore semplificato che usa valori di default per metodi e
   * concentrazione.
   * Euristica: integrationMinProjectMethods = integrationMinProjectClasses * 3
   */
  public EnhancedHybridTestClassifier(int integrationMinProjectClasses) {
    this(integrationMinProjectClasses, integrationMinProjectClasses * 3, 0.8);
  }

  /**
   * METODO PRINCIPALE: Classifica un metodo di test come UNIT o INTEGRATION.
   * 
   * ALGORITMO:
   * 1. Analizza le chiamate dirette dal test alle classi di produzione del
   * progetto
   * 2. Estrae evidenze: numero classi, numero metodi, concentrazione chiamate
   * 3. Applica logica di classificazione basata su fattori multipli
   * 4. Restituisce il risultato con dettagli per debug/analisi
   * 
   * @param cg                 Call graph completo (CHA - Class Hierarchy
   *                           Analysis)
   * @param testMethod         Il metodo di test da classificare (annotato @Test)
   * @param projectProdClasses Set di tutte le classi di produzione del progetto
   *                           (FQN)
   * @param projectTestClasses Set di tutte le classi di test del progetto (FQN) -
   *                           non usato qui
   * @return ClassificationResult con tipo (UNIT/INTEGRATION) e dettagli
   */
  @Override
  public ClassificationResult classify(
      CallGraph cg,
      JavaSootMethod testMethod,
      Set<String> projectProdClasses,
      Set<String> projectTestClasses) {
    MethodSignature tSig = testMethod.getSignature();

    // 1. Raccoglie evidenze dalle chiamate dirette alle classi del progetto
    DirectCallEvidence directEvidence = analyzeDirectCalls(cg, tSig, projectProdClasses);

    // 2. Applica la logica di classificazione ibrida basata su fattori multipli
    TestKind kind = classifyWithMultipleFactors(directEvidence);

    // 3. Costruisce il risultato con le classi coinvolte per ulteriori analisi
    return new ClassificationResult(
        kind,
        new ArrayList<>(directEvidence.uniqueClasses),
        directEvidence.uniqueClassCount);
  }

  /**
   * LOGICA DI CLASSIFICAZIONE: Analizza fattori multipli per determinare il tipo
   * di test.
   * 
   * STRATEGIA: Cerca prima segnali forti, poi gestisce casi borderline.
   * La classificazione è conservativa: in caso di dubbio, preferisce UNIT.
   * 
   * @param evidence Evidenze raccolte dall'analisi delle chiamate dirette
   * @return TestKind.UNIT o TestKind.INTEGRATION
   */
  private TestKind classifyWithMultipleFactors(DirectCallEvidence evidence) {

    // === SEGNALI FORTI DI INTEGRATION ===

    // Regola 1: Molte classi coinvolte = chiara integrazione
    // Es: test chiama Classe1, Classe2, Classe3 → sicuramente INTEGRATION
    if (evidence.uniqueClassCount >= integrationMinProjectClasses) {
      return TestKind.INTEGRATION;
    }

    // Regola 2: Molte chiamate distribuite su più classi = complessità cross-class
    // Es: 10 chiamate con concentrazione 0.6 → chiamate sparse, non focalizzate
    if (evidence.totalMethodCalls >= integrationMinProjectMethods &&
        evidence.getClassConcentration() < highConcentrationThreshold) {
      return TestKind.INTEGRATION;
    }

    // === SEGNALI FORTI DI UNIT ===

    // Regola 3: Nessuna classe progetto = test puro framework/librerie
    // Es: test che chiama solo Mockito, JUnit assertions, Collections → UNIT
    if (evidence.uniqueClassCount == 0) {
      return TestKind.UNIT;
    }

    // Regola 4: Singola classe con alta concentrazione = test focalizzato
    // Es: 1 classe, 8/10 chiamate su quella classe (concentrazione 0.8) → UNIT
    if (evidence.uniqueClassCount == 1 &&
        evidence.getClassConcentration() >= highConcentrationThreshold) {
      return TestKind.UNIT;
    }

    // === CASI BORDERLINE ===

    // Regola 5: Singola classe, concentrazione moderata = probabilmente UNIT
    // Es: 1 classe, concentrazione 0.6 → ancora UNIT (test con qualche utility)
    if (evidence.uniqueClassCount == 1) {
      return TestKind.UNIT;
    }

    // Regola 6: Default per casi poco chiari = UNIT (approccio conservativo)
    // Razionale: è meglio classificare erroneamente un integration come unit
    // che viceversa, per mantenere alta precisione sui veri integration test
    return TestKind.UNIT;
  }

  /**
   * ANALISI CHIAMATE DIRETTE: Estrae e organizza le chiamate dal test alle classi
   * del progetto.
   * 
   * LOGICA:
   * 1. Ottiene tutte le chiamate dirette dal metodo di test (livello 1 nel call
   * graph)
   * 2. Filtra solo quelle verso classi di produzione del progetto (non
   * librerie/test)
   * 3. Raggruppa per classe per calcolare metriche di distribuzione
   * 4. Costruisce struttura dati con tutte le informazioni per la classificazione
   * 
   * ESEMPIO:
   * Test chiama: UserService.save(), UserService.validate(), EmailService.send()
   * → 3 chiamate totali, 2 classi uniche, concentrazione 0.67 (2/3 su
   * UserService)
   * 
   * @param cg                 Call graph da cui estrarre le chiamate
   * @param testSig            Signature del metodo di test analizzato
   * @param projectProdClasses Set FQN delle classi di produzione del progetto
   * @return DirectCallEvidence con tutte le metriche calcolate
   */
  private DirectCallEvidence analyzeDirectCalls(
      CallGraph cg,
      MethodSignature testSig,
      Set<String> projectProdClasses) {

    // 1. Estrae tutte le chiamate dirette verso metodi delle classi del progetto
    // Usa il call graph per ottenere chiamate immediate (non transitive)
    List<MethodSignature> directProjectMethods = cg
        .callsFrom(testSig) // Chiamate dirette dal test
        .stream()
        .map(call -> call.getTargetMethodSignature()) // Metodi chiamati
        .filter(ms -> projectProdClasses.contains( // Solo classi del progetto
            ms.getDeclClassType().getFullyQualifiedName()))
        .collect(Collectors.toList());

    // 2. Raggruppa le chiamate per classe per calcolare distribuzione
    // Es: {"UserService": [save(), validate()], "EmailService": [send()]}
    Map<String, List<MethodSignature>> callsByClass = directProjectMethods
        .stream()
        .collect(Collectors.groupingBy(
            ms -> ms.getDeclClassType().getFullyQualifiedName()));

    // 3. Estrae classi uniche mantenendo l'ordine di apparizione (per debug)
    // LinkedHashSet preserva l'ordine della prima occorrenza
    LinkedHashSet<String> uniqueClasses = directProjectMethods
        .stream()
        .map(ms -> ms.getDeclClassType().getFullyQualifiedName())
        .collect(Collectors.toCollection(LinkedHashSet::new));

    // 4. Costruisce oggetto evidenza con tutte le metriche necessarie
    return new DirectCallEvidence(
        directProjectMethods.size(), // Numero totale chiamate
        callsByClass.size(), // Numero classi uniche
        uniqueClasses, // Lista classi in ordine
        directProjectMethods, // Lista completa metodi
        callsByClass); // Raggruppamento per classe
  }

  /**
   * EVIDENZE CHIAMATE DIRETTE: Struttura dati che contiene tutte le informazioni
   * estratte dall'analisi delle chiamate dirette dal test alle classi del
   * progetto.
   * 
   * SCOPO:
   * - Centralizza tutte le metriche necessarie per la classificazione
   * - Fornisce metodi di calcolo per metriche derivate (concentrazione, focal
   * class)
   * - Facilita il debug fornendo breakdown dettagliato delle chiamate
   * 
   * IMMUTABILE: Tutti i campi sono final e le collezioni sono copie defensive.
   */
  public static class DirectCallEvidence {

    /** Numero totale di chiamate a metodi delle classi del progetto */
    public final int totalMethodCalls;

    /** Numero di classi uniche del progetto coinvolte */
    public final int uniqueClassCount;

    /** Set ordinato delle classi coinvolte (ordine di prima apparizione) */
    public final LinkedHashSet<String> uniqueClasses;

    /** Lista completa di tutti i metodi chiamati (può contenere duplicati) */
    public final List<MethodSignature> allMethods;

    /** Mappa classe → lista dei metodi chiamati su quella classe */
    public final Map<String, List<MethodSignature>> methodsByClass;

    /**
     * Costruttore che inizializza tutte le evidenze con copie defensive.
     * 
     * @param totalMethodCalls Numero totale chiamate
     * @param uniqueClassCount Numero classi uniche
     * @param uniqueClasses    Set classi in ordine
     * @param allMethods       Lista completa metodi
     * @param methodsByClass   Raggruppamento per classe
     */
    public DirectCallEvidence(
        int totalMethodCalls,
        int uniqueClassCount,
        LinkedHashSet<String> uniqueClasses,
        List<MethodSignature> allMethods,
        Map<String, List<MethodSignature>> methodsByClass) {
      this.totalMethodCalls = totalMethodCalls;
      this.uniqueClassCount = uniqueClassCount;
      this.uniqueClasses = uniqueClasses;
      this.allMethods = List.copyOf(allMethods); // Copia defensiva
      this.methodsByClass = Map.copyOf(methodsByClass); // Copia defensiva
    }

    /**
     * CONCENTRAZIONE CLASSE: Calcola quanto le chiamate sono concentrate su una
     * singola classe.
     * 
     * FORMULA: (max chiamate su 1 classe) / (totale chiamate)
     * 
     * ESEMPI:
     * - 10 chiamate tutte su UserService → concentrazione = 1.0 (100%)
     * - 6 chiamate su UserService, 4 su EmailService → concentrazione = 0.6 (60%)
     * - 1 sola classe coinvolta → concentrazione = 1.0 per definizione
     * 
     * INTERPRETAZIONE:
     * - Alta concentrazione (≥0.8) → Test focalizzato → Indicativo di UNIT test
     * - Bassa concentrazione (<0.8) → Test distribuito → Indicativo di INTEGRATION
     * test
     * 
     * @return Valore 0.0-1.0 indicante la concentrazione
     */
    public double getClassConcentration() {
      // Caso speciale: 0 o 1 classe = concentrazione massima per definizione
      if (uniqueClassCount <= 1)
        return 1.0;

      // Trova il numero massimo di chiamate verso una singola classe
      int maxCallsToOneClass = methodsByClass.values()
          .stream()
          .mapToInt(List::size)
          .max()
          .orElse(0);

      // Calcola percentuale: max_chiamate_classe / totale_chiamate
      return totalMethodCalls > 0 ? (double) maxCallsToOneClass / totalMethodCalls : 0.0;
    }

    /**
     * FOCAL CLASS PRIMARIA: Identifica la classe più chiamata (candidata focal
     * class).
     * 
     * LOGICA: La classe con il maggior numero di chiamate è probabilmente
     * la classe principale che il test sta testando (focal class).
     * 
     * UTILIZZO: Utile per UNIT test per identificare la classe sotto test.
     * Per INTEGRATION test questo concetto è meno rilevante.
     * 
     * @return Optional contenente il FQN della classe più chiamata, se presente
     */
    public Optional<String> getPrimaryFocalClass() {
      return methodsByClass.entrySet()
          .stream()
          .max(Map.Entry.comparingByValue(Comparator.comparing(List::size)))
          .map(Map.Entry::getKey);
    }

    /**
     * TEST ALTAMENTE FOCALIZZATO: Verifica se il test è focalizzato su una singola
     * classe.
     * 
     * CRITERI:
     * - Solo 1 classe coinvolta, OPPURE
     * - Concentrazione ≥ 0.8 (80% delle chiamate su una classe)
     * 
     * UTILIZZO: Indicatore forte di UNIT test. I test focalizzati tendono
     * a testare una singola unità di codice in isolamento.
     * 
     * @return true se il test appare altamente focalizzato
     */
    public boolean isHighlyFocused() {
      return uniqueClassCount == 1 || getClassConcentration() >= 0.8;
    }

    /**
     * BREAKDOWN CHIAMATE: Genera una stringa riassuntiva per debug/logging.
     * 
     * FORMATO: "Classe1: N calls, Classe2: M calls, ..."
     * 
     * ESEMPIO: "UserService: 5 calls, EmailService: 2 calls"
     * 
     * UTILIZZO: Debug, logging, analisi manuale dei risultati.
     * 
     * @return Stringa descrittiva della distribuzione delle chiamate
     */
    public String getCallBreakdown() {
      return methodsByClass.entrySet()
          .stream()
          .map(entry -> entry.getKey() + ": " + entry.getValue().size() + " calls")
          .collect(Collectors.joining(", "));
    }
  }
}