package br.com.fereformada.api.dto;

/**
 * DTO para transportar a contagem de notas por fonte.
 * Usado para preencher o dropdown de filtro no Admin.
 */
public record StudyNoteSourceDTO(
        String source,
        long count
) {
}