package br.com.fereformada.api.dto;

public record SourceReference(
        int number,         // O número (ex: 1)
        String sourceName,  // O nome intacto (ex: "Bíblia de Genebra - Romanos 8:29")
        String content      // O texto COMPLETO do chunk

) {}