package br.com.fereformada.api.dto;

import java.util.List;

/**
 * Representa a resposta JSON do LLM Roteador
 * Classifica a pergunta como "simple" ou "complex".
 */
public record QueryRouterResponse(
        String type, // "simple" ou "complex"
        List<String> queries // Lista de sub-perguntas (ou a original se for simples)
) {
    // Construtor padrão para o Jackson (fallback)
    public QueryRouterResponse {
        if (type == null || queries == null || queries.isEmpty()) {
            throw new IllegalArgumentException("Tipo e queries não podem ser nulos/vazios");
        }
    }
}