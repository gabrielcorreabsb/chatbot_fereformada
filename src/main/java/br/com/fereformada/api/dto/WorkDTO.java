package br.com.fereformada.api.dto;

public record WorkDTO(
        String title,
        String type, // Ex: "CATECISMO", "CONFISSAO", "LIVRO"
        String acronym, // Ex: "CM", "CFW"
        Integer publicationYear,
        Long authorId // O ID do Autor (da tabela 'authors')
) {}