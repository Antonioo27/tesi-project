\# Tesi Project



Questo repository contiene due applicazioni Java sviluppate per il progetto di tesi:



\- \*\*repo-manager\*\*: applicazione per scaricare repository Maven da SEART e clonarli in batch.

\- \*\*analyzer\*\*: applicazione per analizzare i repository scaricati.



\## Struttura



\- `repo-manager/` — Clonazione delle repository

\- `analyzer/` — Analisi del codice



\## Requisiti



Prima di eseguire le applicazioni, è necessario:



\- Avere Java (versione 17 o superiore) e Maven installati.

\- Configurare e avviare un'istanza locale di \[SEART](https://seart-ghs.si.usi.ch/) seguendo le istruzioni riportate nel progetto Docker Compose fornito (cartella `ghs` esterna a questo repository).

\- Impostare un token GitHub per autenticarsi durante il clonaggio dei repository.



\## Come eseguire



1\. Assicurati di avere Java e Maven installati.

2\. Imposta la variabile ambiente `GITHUB\_TOKEN`:



&nbsp;  \*\*Windows PowerShell\*\*

&nbsp;  ```powershell

&nbsp;  $env:GITHUB\_TOKEN="ghp\_tuo\_token"



