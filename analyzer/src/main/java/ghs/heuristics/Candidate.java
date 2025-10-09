package ghs.heuristics;

import java.util.Map;

public record Candidate<T> (
    T value,                 // Oggetto proposto (classe, metodo, stringa, o null se è solo una metrica)
    double confidence,       // 0..1: affidabilità / forza dell’evidenza
    String rationale,        // Etichetta / spiegazione sintetica del perché esiste questo candidato
    Map<String,Object> evidence // Feature grezze a supporto (per auditing / combinazione)
) {}
