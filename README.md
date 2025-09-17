# Tesi Project - Advanced Test Classification Analysis# Tesi Project



Questo repository contiene due applicazioni Java sviluppate per il progetto di tesi sull'analisi dei test unitari e di integrazione nei repository Java:Questo repository contiene due applicazioni Java sviluppate per il progetto di tesi:



- **repo-manager**: applicazione per scaricare repository Maven da SEART e clonarli in batch.- **repo-manager**: applicazione per scaricare repository Maven da SEART e clonarli in batch.

- **analyzer**: applicazione per analizzare i repository scaricati utilizzando tecniche avanzate di classificazione dei test e rilevamento dei metodi focali.- **analyzer**: applicazione per analizzare i repository scaricati.



## Struttura## Struttura



- `repo-manager/` — Clonazione delle repository- `repo-manager/` — Clonazione delle repository

- `analyzer/` — Analisi del codice con algoritmi avanzati di classificazione dei test- `analyzer/` — Analisi del codice



## Caratteristiche Principali dell'Analyzer## Requisiti



### 🧪 **Classificazione Avanzata dei Test**Prima di eseguire le applicazioni, è necessario:

- **EnhancedHybridTestClassifier**: Classificazione multi-fattoriale che combina analisi a livello di classe e metodo

- **Soglie configurabili**: `integrationMinProjectClasses` e `integrationMinProjectMethods` per una classificazione più precisa- Avere **Java 17** (o superiore) e **Maven** installati.

- **Analisi della concentrazione**: Rilevamento di test con alta concentrazione di chiamate a classi specifiche- Configurare e avviare un'istanza locale di [SEART](https://seart-ghs.si.usi.ch/) seguendo le istruzioni riportate nel progetto **Docker Compose** fornito (cartella `ghs`, esterna a questo repository).

- Impostare un **token GitHub** per autenticarsi durante il clonaggio dei repository.

### 🎯 **Rilevamento Intelligente dei Metodi Focali**

- **AssertionAwareFocalMethodHeuristic**: Euristica avanzata che analizza il bytecode per identificare i metodi focali## Come eseguire

- **Analisi delle asserzioni**: Riconoscimento automatico di pattern di test basati su asserzioni

- **Scoring intelligente**: Sistema di punteggio che privilegia metodi con nomi significativi e pattern di test comuni1. Assicurati di avere Java e Maven installati.

2. Imposta la variabile d’ambiente `GITHUB_TOKEN`:

### 📊 **Analisi del Call Graph**

- Integrazione con **SootUp framework** per l'analisi statica del codice   **Windows PowerShell**

- Traversal BFS con limiti configurabili per evitare esplosioni del grafo

- Rilevamento automatico dell'uso di mock e framework di test   ```powershell

   $env:GITHUB_TOKEN="ghp_tuo_token"

### ⚙️ **Configurazione Flessibile**   ```

- Parametri CLI estesi per personalizzare l'analisi
- Modalità auto-tune per l'ottimizzazione automatica delle performance
- Supporto per analisi incrementale e resume

## Parametri di Configurazione

### Nuovi Parametri per la Classificazione Avanzata

```bash
--integrationMinProjectClasses 2    # Soglia minima per classificazione INTEGRATION
--integrationMinProjectMethods 6    # Soglia minima a livello di metodo
--highConcentrationThreshold 0.8    # Soglia per rilevamento alta concentrazione
--autoFastHeuristic false           # Disabilita euristica veloce per analisi completa
```

### Esempio di Esecuzione Completa

```bash
mvn compile exec:java -Dexec.mainClass="ghs.analyzer.app.Main" \
  -Dexec.args="--base ../repo-manager/cloned_repos \
               --out ../out \
               --splitByRepo \
               --integrationMinProjectClasses 2 \
               --integrationMinProjectMethods 6 \
               --highConcentrationThreshold 0.8 \
               --autoFastHeuristic false"
```

## Requisiti

Prima di eseguire le applicazioni, è necessario:

- Avere **Java 17** (o superiore) e **Maven** installati.
- Configurare e avviare un'istanza locale di [SEART](https://seart-ghs.si.usi.ch/) seguendo le istruzioni riportate nel progetto **Docker Compose** fornito (cartella `ghs`, esterna a questo repository).
- Impostare un **token GitHub** per autenticarsi durante il clonaggio dei repository.

## Come eseguire

1. Assicurati di avere Java e Maven installati.
2. Imposta la variabile d'ambiente `GITHUB_TOKEN`:

   **Windows PowerShell**

   ```powershell
   $env:GITHUB_TOKEN="ghp_tuo_token"
   ```

3. Clona i repository con repo-manager:

   ```powershell
   cd repo-manager
   mvn compile exec:java
   ```

4. Analizza i repository con l'analyzer avanzato:

   ```powershell
   cd analyzer
   mvn compile exec:java -Dexec.mainClass="ghs.analyzer.app.Main" `
     -Dexec.args="--base ../repo-manager/cloned_repos --out ../out --splitByRepo --integrationMinProjectClasses 2 --integrationMinProjectMethods 6 --highConcentrationThreshold 0.8"
   ```

## Output dell'Analisi

L'analyzer genera file JSONL con informazioni dettagliate per ogni test:

```json
{
  "repo": "apollo",
  "testClass": "com.ctrip.framework.apollo.openapi.service.ConsumerServiceTest",
  "testMethod": "void testGenerateConsumerToken()",
  "testKind": "UNIT",
  "focalClass": "com.ctrip.framework.apollo.openapi.service.ConsumerService",
  "focalMethod": "<com.ctrip.framework.apollo.openapi.service.ConsumerService: java.lang.String generateToken(java.lang.String,java.util.Date,java.lang.String)>",
  "unit_integration_score": 0.0,
  "cgStats": {
    "projectCalls": 2,
    "uniqueProjectClasses": 1,
    "callsToFocalClass": 2,
    "maxDepthVisited": 3
  },
  "usesMocks": false
}
```

## Algoritmi Implementati

### EnhancedHybridTestClassifier
- **Analisi Multi-livello**: Combina evidenze a livello di classe e metodo
- **Classificazione Adattiva**: Utilizza soglie configurabili per diversi tipi di progetto
- **Rilevamento Pattern**: Identifica pattern specifici dei test di integrazione

### AssertionAwareFocalMethodHeuristic
- **Analisi Bytecode**: Scansione del bytecode per identificare chiamate a metodi
- **Awareness delle Asserzioni**: Riconoscimento di pattern di asserzione comuni
- **Scoring Semantico**: Punteggio basato su convenzioni di naming e pattern di test

## Contributi alla Ricerca

Questo progetto contribuisce alla ricerca sull'analisi automatica dei test software attraverso:

1. **Classificazione Automatica**: Distinzione accurata tra test unitari e di integrazione
2. **Rilevamento Metodi Focali**: Identificazione automatica dei metodi sotto test
3. **Analisi Empirica**: Valutazione su repository open-source reali
4. **Metriche Avanzate**: Nuove metriche per la qualità della classificazione dei test

## Risultati della Ricerca

L'implementazione degli algoritmi avanzati ha dimostrato:

- **Miglioramenti nella classificazione**: Classificazione più accurata dei test con zero regressioni
- **Rilevamento migliorato dei metodi focali**: Incremento del successo nella identificazione dei metodi focali per test UNIT
- **Stabilità**: Mantenimento delle performance su repository con classificazione già ottimale
- **Scalabilità**: Analisi efficiente su grandi repository con migliaia di test

## Tecnologie Utilizzate

- **Java 17**: Linguaggio di sviluppo principale
- **Maven**: Sistema di build e gestione dipendenze
- **SootUp 2.0.0**: Framework per analisi statica del codice Java
- **ASM**: Libreria per analisi bytecode
- **JGit**: Libreria per operazioni Git
- **Jackson**: Serializzazione JSON per output risultati