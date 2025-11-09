package br.com.fereformada.api.dto;

import java.util.List;

// Este DTO será usado para receber a requisição de /admin/chunks/bulk-add-topics
public record BulkTopicDTO(
        List<Long> chunkIds,
        List<Long> topicIds
) {
}