package br.com.fereformada.api.repository;

/**
 * Projeção leve usada pelo AsyncBackfillService.
 * Contém apenas os campos estritamente necessários, evitando
 * carregar os campos de vetor (float[]) que causam o bug do Hibernate.
 */
public interface ChunkBackfillProjection {
    Long getId();
    String getQuestion();
}