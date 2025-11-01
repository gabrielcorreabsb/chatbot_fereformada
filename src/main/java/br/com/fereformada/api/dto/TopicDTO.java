package br.com.fereformada.api.dto;

import br.com.fereformada.api.model.Topic;

// DTO para criar, atualizar e ler Tópicos
public record TopicDTO(
        Long id,
        String name,
        String description
) {
    /**
     * Construtor para converter a Entidade Topic em um TopicDTO
     */
    public TopicDTO(Topic topic) {
        this(
                topic.getId(),
                topic.getName(),
                topic.getDescription()
        );
    }

    /**
     * Método para aplicar os dados deste DTO em uma Entidade
     */
    public void toEntity(Topic topic) {
        topic.setName(this.name);
        topic.setDescription(this.description);
    }
}