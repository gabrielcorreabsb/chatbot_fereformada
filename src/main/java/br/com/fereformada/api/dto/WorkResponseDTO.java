package br.com.fereformada.api.dto;

import br.com.fereformada.api.model.Work;

// DTO para representar a 'Work' na resposta da API
public record WorkResponseDTO(
        Long id,
        String title,
        String acronym,
        String type,
        Integer publicationYear,
        AuthorDTO author
) {
    /**
     * Construtor de Mapeamento:
     * Converte a entidade 'Work' (do banco) neste DTO (para o JSON).
     */
    public WorkResponseDTO(Work work) {
        this(
                work.getId(),
                work.getTitle(),
                work.getAcronym(),
                work.getType(),
                work.getPublicationYear(),

                // ======================================================

                // Em vez de tentar construir o AuthorDTO manualmente,
                // nós simplesmente passamos o objeto work.getAuthor()
                // para o construtor do AuthorDTO que já criamos.
                new AuthorDTO(work.getAuthor())
                // ======================================================
        );
    }
}