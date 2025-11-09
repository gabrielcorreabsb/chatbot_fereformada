package br.com.fereformada.api.dto;

/**
 * Projeção DTO para a entidade StudyNote.
 * Usado para buscar dados do repositório para o painel de admin,
 * IGNORANDO a coluna 'note_vector' para evitar o PSQLException.
 */
public record StudyNoteProjection(
        Long id,
        String book,
        Integer startChapter,
        Integer startVerse,
        Integer endChapter,
        Integer endVerse,
        String noteContent,
        String source
) {
    // O construtor é gerado automaticamente pelo 'record'
}