package br.com.fereformada.api.dto;

import br.com.fereformada.api.model.ContentChunk;
import br.com.fereformada.api.model.Topic;
import lombok.Getter;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.HashSet;

@Getter
public class ChunkResponseDTO {

    private final Long id;
    private final String content;
    private final String question;
    private final String sectionTitle;
    private final String chapterTitle;
    private final Integer chapterNumber;
    private final Integer sectionNumber;
    private final String subsectionTitle;
    private final String subSubsectionTitle;
    private final Long workId;
    private final String workTitle;
    private final Set<TopicDTO> topics; // <-- Este será preenchido pelo Serviço

    /**
     * Construtor de Mapeamento (USADO PELO SERVIÇO)
     * Este é o construtor de "costura".
     * Ele pega a Projeção Simples (Passo 1) e a Lista de Tópicos (Passo 2)
     * e os combina no DTO final que o React espera.
     */
    public ChunkResponseDTO(ChunkProjection projection, Set<TopicDTO> topics) {
        this.id = projection.id();
        // Limita o conteúdo para a tabela
        this.content = projection.content();
        this.question = projection.question();
        this.sectionTitle = projection.sectionTitle();
        this.chapterTitle = projection.chapterTitle();
        this.chapterNumber = projection.chapterNumber();
        this.sectionNumber = projection.sectionNumber();
        this.subsectionTitle = projection.subsectionTitle();
        this.subSubsectionTitle = projection.subSubsectionTitle();
        this.workId = projection.workId();
        this.workTitle = projection.workTitle();
        this.topics = topics; // <-- Atribui os tópicos "costurados"
    }

    // =================================================================
    // NÓS AINDA PRECISAMOS DESTE CONSTRUTOR ANTIGO
    // =================================================================
    // Os métodos createChunk e updateChunk retornam a Entidade completa
    // (pois eles não têm o bug do vetor NULL).
    // Este construtor lida com a resposta desses métodos.
    public ChunkResponseDTO(ContentChunk chunk) {
        this.id = chunk.getId();
        this.content = chunk.getContent();
        this.question = chunk.getQuestion();
        this.sectionTitle = chunk.getSectionTitle();
        this.chapterTitle = chunk.getChapterTitle();
        this.chapterNumber = chunk.getChapterNumber();
        this.sectionNumber = chunk.getSectionNumber();
        this.subsectionTitle = chunk.getSubsectionTitle();
        this.subSubsectionTitle = chunk.getSubSubsectionTitle();
        this.workId = chunk.getWork().getId();
        this.workTitle = chunk.getWork().getTitle();
        this.topics = chunk.getTopics().stream()
                .map(topic -> new TopicDTO(topic))
                .collect(Collectors.toCollection(HashSet::new));
    }
}