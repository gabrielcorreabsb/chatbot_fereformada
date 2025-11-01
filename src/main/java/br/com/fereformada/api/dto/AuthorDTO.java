package br.com.fereformada.api.dto;

import br.com.fereformada.api.model.Author;
import java.time.LocalDate;

// Este DTO será usado para ENVIAR (Request) e RECEBER (Response) dados de Autor.
// Ele NÃO inclui a lista de 'works' para evitar loops.
public record AuthorDTO(
        Long id,
        String name,
        String biography,
        LocalDate birthDate,
        LocalDate deathDate,
        String era
) {
    /**
     * Construtor para converter a Entidade Author em um AuthorDTO
     */
    public AuthorDTO(Author author) {
        this(
                author.getId(),
                author.getName(),
                author.getBiography(),
                author.getBirthDate(),
                author.getDeathDate(),
                author.getEra()
        );
    }

    /**
     * Método para aplicar os dados deste DTO em uma Entidade
     */
    public void toEntity(Author author) {
        author.setName(this.name);
        author.setBiography(this.biography);
        author.setBirthDate(this.birthDate);
        author.setDeathDate(this.deathDate);
        author.setEra(this.era);
    }
}