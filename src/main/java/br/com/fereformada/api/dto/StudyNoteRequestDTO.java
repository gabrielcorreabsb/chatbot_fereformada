package br.com.fereformada.api.dto;

import br.com.fereformada.api.model.StudyNote;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * DTO para CRIAR ou ATUALIZAR uma StudyNote.
 * Recebe os dados do formulário do frontend.
 */
public record StudyNoteRequestDTO(
        @NotBlank String book,
        @NotNull Integer startChapter,
        @NotNull Integer startVerse,
        Integer endChapter, // Pode ser nulo se for 1 versículo
        Integer endVerse,   // Pode ser nulo se for 1 versículo
        @NotBlank String noteContent,
        String source
) {
    /**
     * Helper para mapear os dados deste DTO para a Entidade.
     */
    public void toEntity(StudyNote note) {
        note.setBook(this.book);
        note.setStartChapter(this.startChapter);
        note.setStartVerse(this.startVerse);

        // Lógica para lidar com versículo único
        note.setEndChapter(this.endChapter != null ? this.endChapter : this.startChapter);
        note.setEndVerse(this.endVerse != null ? this.endVerse : this.startVerse);

        note.setNoteContent(this.noteContent);
        note.setSource(this.source);
    }
}