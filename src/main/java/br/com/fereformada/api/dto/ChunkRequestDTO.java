package br.com.fereformada.api.dto;

import br.com.fereformada.api.model.ContentChunk;

public record ChunkRequestDTO(
        String content,
        String question,
        String sectionTitle,
        String chapterTitle,
        Integer chapterNumber,
        Integer sectionNumber,
        String subsectionTitle,
        String subSubsectionTitle
) {
    // Método auxiliar para mapear o DTO para a Entidade
    public void toEntity(ContentChunk chunk) {
        chunk.setContent(this.content);
        chunk.setQuestion(this.question);
        chunk.setSectionTitle(this.sectionTitle);
        chunk.setChapterTitle(this.chapterTitle);
        chunk.setChapterNumber(this.chapterNumber);
        chunk.setSectionNumber(this.sectionNumber);
        chunk.setSubsectionTitle(this.subsectionTitle);
        chunk.setSubSubsectionTitle(this.subSubsectionTitle);
    }
}