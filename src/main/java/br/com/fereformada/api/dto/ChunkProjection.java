package br.com.fereformada.api.dto;

/**
 * DTO de Projeção. O Repositório usará isso para
 * buscar APENAS os campos simples do ContentChunk, ignorando
 * o 'content_vector' (quebrado) e a coleção 'topics' (LAZY).
 */
public record ChunkProjection(
        Long id,
        String content,
        String question,
        String sectionTitle,
        String chapterTitle,
        Integer chapterNumber,
        Integer sectionNumber,
        String subsectionTitle,
        String subSubsectionTitle,
        Long workId,
        String workTitle
) {
    /**
     * Construtor de Mapeamento para o Spring Data JPA.
     * Esta é a assinatura exata que nossa query JPQL vai chamar.
     */
    public ChunkProjection(
            Long id, String content, String question, String sectionTitle,
            String chapterTitle, Integer chapterNumber, Integer sectionNumber,
            String subsectionTitle, String subSubsectionTitle,
            Long workId, String workTitle) {
        this.id = id;
        this.content = content;
        this.question = question;
        this.sectionTitle = sectionTitle;
        this.chapterTitle = chapterTitle;
        this.chapterNumber = chapterNumber;
        this.sectionNumber = sectionNumber;
        this.subsectionTitle = subsectionTitle;
        this.subSubsectionTitle = subSubsectionTitle;
        this.workId = workId;
        this.workTitle = workTitle;
    }
}