# Tesi Project - Advanced Test Analysis with Pluggable Heuristics

Questo repository contiene due applicazioni Java sviluppate per il progetto di tesi sull'analisi automatica dei test unitari e di integrazione nei repository Java:

- **repo-manager**: applicazione per scaricare repository Maven da SEART e clonarli in batch
- **analyzer**: applicazione per analizzare i repository utilizzando un'architettura modulare basata su euristiche pluggabili

## Struttura

- `repo-manager/` — Clonazione delle repository da SEART
- `analyzer/` — Analisi del codice con architettura basata su collector + combiner

## Caratteristiche Principali dell'Analyzer

### **Architettura Modulare Heuristic-Based**

L'analyzer implementa una separazione netta tra:

- **Collector (ChaCallGraphAnalyzer)**: Raccoglie dati tramite analisi del call graph senza prendere decisioni
- **Heuristics**: Estraggono metriche specifiche con candidati e confidence scores (0-1)
- **Combiner**: Interpreta i risultati delle euristiche per produrre classificazioni finali

Questa architettura permette di:
- Aggiungere nuove euristiche senza modificare il collector
- Sperimentare con diversi combiners per logiche di classificazione alternative
- Mantenere separati i dati raccolti dalle decisioni interpretative

### **Euristiche Implementate**

1. **DirectCallsMetricHeuristic**
   - Analizza le chiamate dirette dal test method
   - Calcola concentrazione e distribuzione delle chiamate tra classi
   - Produce metriche: `totalMethodCalls`, `uniqueClassCount`, `concentration`

2. **NameBasedFocalClassHeuristicV2**
   - Rileva la focal class basandosi su convenzioni di naming
   - Pattern supportati: `XyzTest` → `Xyz`, `TestXyz` → `Xyz`
   - Confidence score basato sul match della convenzione

3. **AssertionAwareFocalMethodHeuristic**
   - Usa JavaParser per analisi AST del codice sorgente
   - Identifica metodi usati in asserzioni (es. `assertEquals(expected, obj.method())`)
   - Distingue ruoli: DIRECT (chiamata diretta in assert) vs VARIABLE_PRODUCER (produce variabile usata in assert)

4. **MockUsageHeuristic**
   - Rileva l'uso di mock: annotazioni `@Mock`, chiamate a `Mockito.mock()`
   - Analizza patterns di verification (`verify()`) e stubbing (`when()`)
   - Calcola `verificationRatio` per distinguere mock assertion-focused da mock per setup

### **Sistema di Confidence**

Ogni euristica restituisce un `HeuristicResult` contenente:
```java
record HeuristicResult(
    String heuristicId,
    List<Candidate<?>> candidates,  // Lista di candidati ordinati per confidence
    Map<String, Object> metadata    // Metriche aggiuntive
)

record Candidate<T>(
    T value,
    double confidence,   // 0.0 - 1.0
    String rationale,    // Spiegazione della scelta
    Map<String, Object> evidence  // Dettagli di supporto
)
```

Questo approccio permette di:
- Fornire più candidati alternativi invece di una singola risposta
- Quantificare l'incertezza delle decisioni
- Facilitare il debugging e l'interpretazione dei risultati

### **Analisi del Call Graph**

- Integrazione con **SootUp framework** per analisi statica CHA (Class Hierarchy Analysis)
- Traversal BFS con limiti configurabili per evitare esplosioni del grafo
- Identificazione automatica di classi di progetto vs librerie
- Supporto per progetti Maven multi-modulo

### **Combiner Configurabile**

Il `TestResultCombiner` è pluggabile:
```java
interface TestResultCombiner {
    TestRecord combine(RawTestAnalysis raw);
}
```

**ExampleTestResultCombiner** (implementazione di riferimento):
- Fonde segnali da multiple euristiche (assertion-based forte, name-based debole)
- Calcola `unitIntegrationScore` (0-1) basato su volume chiamate e concentrazione
- Classifica come UNIT/INTEGRATION con confidence score
- Applica penalità/boost basate su mock usage e verification patterns

### **Configurazione Flessibile**

- Parametri CLI estesi per personalizzare l'analisi
- Modalità **auto-tune** per ottimizzazione automatica delle performance in base alla dimensione del progetto
- Supporto per **analisi incrementale** e **resume** con checkpoint per-modulo
- Gestione robusta degli errori con skip su OutOfMemory

## Requisiti

- **Java 17** (o superiore)
- **Maven**

## Come Eseguire

### Repo Manager

Il repo-manager clona repository Maven in batch. Eseguirlo dalla directory `repo-manager/`:

```powershell
cd repo-manager
mvn compile exec:java
```

I repository vengono clonati nella cartella `cloned_repos/`. Puoi configurare la lista dei repository da clonare modificando il file `repos.txt`.

### Analyzer

L'analyzer analizza i test nei repository Maven. Eseguirlo dalla directory `analyzer/`:

**Esempio Base**
```powershell
cd analyzer
mvn -q -DskipTests exec:java -Dexec.args="--base ../repo-manager/cloned_repos --out analysis.jsonl"
```

**Esempio con Parametri Avanzati**
```powershell
mvn -q -DskipTests exec:java -Dexec.args="--base ../repo-manager/cloned_repos --out analysis.jsonl --maxDepth 3 --maxVisited 25000 --splitByRepo --autoTune --resume"
```

**Analizzare un Singolo Repository**
```powershell
mvn -q -DskipTests exec:java -Dexec.args="--base ../repo-manager/cloned_repos/commons-lang --out commons-lang-analysis.jsonl"
```

## Parametri di Configurazione

### Parametri I/O
- `--base <path>`: Directory base contenente i repository da analizzare (richiesto)
- `--out <path>`: File di output JSONL (default: `analysis.jsonl`)
- `--splitByRepo`: Crea un file separato per ogni repository
- `--append`: Appende ai file esistenti invece di sovrascriverli
- `--onlyFrom <file>`: Analizza solo i repository listati nel file specificato

### Parametri Traversal
- `--maxDepth <n>`: Profondità massima del BFS nel call graph (default: 3)
- `--maxVisited <n>`: Numero massimo di nodi visitati per test (default: 10000)

### Parametri Auto-tune
- `--autoTune`: Abilita auto-tuning in base alla dimensione del progetto
- `--bigThr <n>`: Soglia per progetti "big" (default: 20 test)
- `--hugeThr <n>`: Soglia per progetti "huge" (default: 100 test)
- `--autoBatchBig <n>`: Batch size per progetti big (default: 5)
- `--autoBatchHuge <n>`: Batch size per progetti huge (default: 1)
- `--autoVisitedBig <n>`: MaxVisited per progetti big (default: 3000)
- `--autoVisitedHuge <n>`: MaxVisited per progetti huge (default: 1000)

### Parametri Resume e Robustezza
- `--resume`: Riprende l'analisi da dove si era interrotta
- `--resumeReset`: Resetta lo stato di resume prima di iniziare
- `--skipOnOom`: Salta i moduli che causano OutOfMemoryError
- `--batchSize <n>`: Numero di test da analizzare prima di ricreare SootUp view (default: 10)
- `--batchesPerView <n>`: Numero di batch prima di ricostruire la view (default: 5)
- `--preflightN <n>`: Numero di test usati per preflight check (default: 5)
- `--preflightMinHeadroomMb <n>`: Memoria minima richiesta dopo preflight (default: 500 MB)

## Output dell'Analisi

L'analyzer genera file JSONL con un record per ogni test analizzato:

```json
{
  "repo": "apollo",
  "module": "/path/to/cloned_repos/apollo/apollo-core",
  "cfgId": "d3_v10000",
  "testClass": "com.ctrip.framework.apollo.openapi.service.ConsumerServiceTest",
  "testMethod": "void testGenerateConsumerToken()",
  "focalClass": "com.ctrip.framework.apollo.openapi.service.ConsumerService",
  "focalMethod": "<com.ctrip.framework.apollo.openapi.service.ConsumerService: java.lang.String generateToken(java.lang.String,java.util.Date,java.lang.String)>",
  "unitIntegrationScore": 0.23,
  "testKind": "UNIT",
  "classificationConfidence": 0.85,
  "heuristicResults": [
    {
      "heuristicId": "name_based_focal_class",
      "candidates": [
        {
          "value": "com.ctrip.framework.apollo.openapi.service.ConsumerService",
          "confidence": 0.9,
          "rationale": "Test name pattern match",
          "evidence": {"pattern": "ConsumerServiceTest -> ConsumerService"}
        }
      ],
      "metadata": {}
    },
    {
      "heuristicId": "assertion_focal_method",
      "candidates": [
        {
          "value": "generateToken",
          "confidence": 0.8,
          "rationale": "Direct call in assertion",
          "evidence": {"role": "DIRECT", "callSite": "line 42"}
        }
      ],
      "metadata": {}
    },
    {
      "heuristicId": "direct_calls_metrics",
      "candidates": [],
      "metadata": {
        "totalMethodCalls": 5,
        "uniqueClassCount": 2,
        "concentration": 0.6
      }
    },
    {
      "heuristicId": "mock_usage",
      "candidates": [],
      "metadata": {
        "mockCount": 0,
        "verificationRatio": 0.0
      }
    }
  ]
}
```

### Campi del TestRecord

- **repo**: Nome del repository
- **module**: Path del modulo Maven analizzato
- **cfgId**: ID configurazione (es. `d3_v10000` = depth 3, maxVisited 10000)
- **testClass**: Fully qualified name della classe di test
- **testMethod**: Signature del metodo di test
- **focalClass**: Focal class identificata (può essere null)
- **focalMethod**: Focal method identificato (può essere null)
- **unitIntegrationScore**: Score 0-1 (valori bassi = UNIT, alti = INTEGRATION)
- **testKind**: Classificazione finale (`UNIT` o `INTEGRATION`)
- **classificationConfidence**: Confidence della classificazione (0-1)
- **heuristicResults**: Lista completa dei risultati di tutte le euristiche

## Architettura del Sistema

```
┌─────────────────────────────────────────────────────────────┐
│                    AnalyzerPipeline                         │
│  - Scansiona moduli Maven                                   │
│  - Gestisce resume e OOM handling                           │
└─────────────────────┬───────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────┐
│                   ModuleAnalyzer                            │
│  - Warm-up SootUp                                           │
│  - Test discovery (JUnit 4/5, TestNG)                       │
│  - Auto-tuning batch size                                   │
└─────────────────────┬───────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────┐
│              FullCallGraphStrategy                          │
│  - Costruisce call graph (CHA)                              │
│  - Esegue euristiche                                        │
│  - Combina risultati                                        │
└─────┬─────────────────────────────┬─────────────────────────┘
      │                             │
      ▼                             ▼
┌──────────────────┐        ┌──────────────────────┐
│ ChaCallGraphAnalyzer      │  HeuristicEngine     │
│ - BFS traversal           │  - Esegue tutte      │
│ - Raccolta dati           │    le euristiche     │
│ - NO decisioni            │  - Gestione errori   │
│                           │  - Aggregazione      │
└──────────┬────────┘       └──────────┬───────────┘
           │                            │
           ▼                            ▼
┌──────────────────────────────────────────────────┐
│            RawTestAnalysis                       │
│  - repo, module, test identifiers                │
│  - usesMocks flag                                │
│  - directProjectClasses                          │
│  - heuristicResults (List<HeuristicResult>)      │
└──────────────────────┬───────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────┐
│         TestResultCombiner                       │
│  (ExampleTestResultCombiner)                     │
│  - Fonde segnali euristiche                      │
│  - Calcola scores                                │
│  - Classifica UNIT/INTEGRATION                   │
└──────────────────────┬───────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────┐
│             TestRecord                           │
│  - Output finale scritto in JSONL                │
└──────────────────────────────────────────────────┘
```

## Estendibilità

### Aggiungere una Nuova Euristica

1. Implementare l'interfaccia `Heuristic`:

```java
package ghs.heuristics;

public class MyCustomHeuristic implements Heuristic {
    @Override
    public String id() {
        return "my_custom_heuristic";
    }
    
    @Override
    public HeuristicResult run(HeuristicContext ctx) throws Exception {
        // ctx contiene: testMethod, callGraph, projectClasses, testSource (Optional)
        
        List<Candidate<String>> candidates = new ArrayList<>();
        // ... logica di analisi ...
        
        candidates.add(new Candidate<>(
            "candidateValue",
            0.75,  // confidence
            "Why this candidate was chosen",
            Map.of("key", "evidence")
        ));
        
        return new HeuristicResult(
            id(),
            candidates,
            Map.of("metric1", value1, "metric2", value2)
        );
    }
}
```

2. Registrare l'euristica in `Main.java`:

```java
List<Heuristic> heuristics = List.of(
    new NameBasedFocalClassHeuristic(),
    new AssertionAwareFocalMethodHeuristic(),
    new MockUsageHeuristic(),
    new DirectCallsMetricHeuristic(),
    new MyCustomHeuristic()  // <-- aggiungere qui
);
```

### Implementare un Combiner Personalizzato

1. Implementare l'interfaccia `TestResultCombiner`:

```java
package ghs.combine;

public class MyCustomCombiner implements TestResultCombiner {
    @Override
    public TestRecord combine(RawTestAnalysis raw) {
        // Leggere i risultati delle euristiche
        List<HeuristicResult> results = raw.heuristicResults();
        
        // Implementare logica custom di combinazione
        String focalClass = determineFocalClass(results);
        double score = calculateScore(results);
        TestKind kind = classify(score);
        
        return new TestRecord(
            raw.repo(),
            raw.module(),
            raw.cfgId(),
            raw.testClass(),
            raw.testMethod(),
            focalClass,
            focalMethod,
            score,
            kind,
            confidence,
            results
        );
    }
}
```

2. Usare il nuovo combiner in `Main.java`:

```java
TestResultCombiner combiner = new MyCustomCombiner();
```

## Tecnologie Utilizzate

- **Java 17**: Linguaggio di sviluppo principale
- **Maven**: Sistema di build e gestione dipendenze
- **SootUp 2.0.0**: Framework per analisi statica del codice Java (CHA, call graph)
- **JavaParser 3.26.2**: Parsing e analisi AST del codice sorgente Java
- **JGit**: Libreria per operazioni Git
- **Jackson**: Serializzazione/deserializzazione JSON per output risultati
- **JUnit 4/5 & TestNG**: Rilevamento dei test method

## Vantaggi dell'Architettura

1. **Separazione delle Responsabilità**: Il collector raccoglie dati grezzi, il combiner interpreta
2. **Estendibilità**: Aggiungere nuove euristiche o combiners senza modificare il core
3. **Trasparenza**: Tutti i dati intermedi sono disponibili nell'output per debugging
4. **Riproducibilità**: I risultati grezzi possono essere ri-combinati offline con logiche diverse
5. **Confidence-based**: Ogni decisione è quantificata con un livello di certezza

## Contributi alla Ricerca

Questo progetto contribuisce alla ricerca sull'analisi automatica dei test software attraverso:

1. **Architettura Modulare**: Separazione netta tra raccolta dati e decisioni interpretative
2. **Sistema di Confidence**: Quantificazione dell'incertezza invece di decisioni binarie
3. **Multi-Source Evidence**: Fusione di segnali da multiple fonti (AST, call graph, convenzioni)
4. **Analisi Empirica**: Valutazione su repository open-source reali con migliaia di test
5. **Reproducibilità**: Output completo con tutti i dati intermedi per validazione

## Licenza

Questo progetto è sviluppato per scopi di ricerca accademica presso l'Università di Bologna.
