package br.com.fereformada.api.dto;

/**
 * DTO auxiliar para o Dashboard: Contagem de chunks por Obra.
 */
public record ContentCountByWorkDTO(String workAcronym, long count) {}