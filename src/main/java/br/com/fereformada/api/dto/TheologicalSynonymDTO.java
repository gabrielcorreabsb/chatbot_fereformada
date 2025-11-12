package br.com.fereformada.api.dto;

import br.com.fereformada.api.model.TheologicalSynonym;
import jakarta.validation.constraints.NotBlank;

/**
 * DTO para criar e exibir Sinônimos Teológicos.
 */
public record TheologicalSynonymDTO(
        Long id,
        @NotBlank String mainTerm,
        @NotBlank String synonym
) {
    /**
     * Construtor para converter a Entidade em DTO.
     */
    public TheologicalSynonymDTO(TheologicalSynonym entity) {
        this(
                entity.getId(),
                entity.getMainTerm(),
                entity.getSynonym()
        );
    }

    /**
     * Helper para converter este DTO em uma nova Entidade.
     */
    public TheologicalSynonym toEntity() {
        TheologicalSynonym entity = new TheologicalSynonym();
        entity.setMainTerm(this.mainTerm);
        entity.setSynonym(this.synonym);
        return entity;
    }
}