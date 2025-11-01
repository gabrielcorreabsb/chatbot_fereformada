package br.com.fereformada.api.dto;

/**
 * Uma projeção minúscula usada pelo repositório
 * para buscar as relações ManyToMany entre Chunks e Topics.
 */
public record ChunkTopicProjection(
        Long chunkId,
        Long topicId,
        String topicName,
        String topicDescription
) {}