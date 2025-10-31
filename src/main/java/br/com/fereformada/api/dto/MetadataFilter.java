package br.com.fereformada.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Representa os filtros estruturais extraídos da pergunta do usuário.
 * Usado para filtrar a busca vetorial e FTS antes do reranking.
 *
 * Mapeia diretamente os campos do seu banco de dados:
 * - work.acronym (ex: "CFW", "TSB", "BC")
 * - content_chunks.chapter_number
 * - content_chunks.section_number
 * - study_notes.book (ex: "Jó", "Romanos")
 * - study_notes.start_chapter
 * - study_notes.start_verse
 */
public record MetadataFilter(
        @JsonProperty("obra_acronimo")
        String obraAcronimo, // Ex: "CFW", "CM", "TSB"

        @JsonProperty("livro_biblico")
        String livroBiblico, // Ex: "Gênesis", "Romanos"

        @JsonProperty("capitulo")
        Integer capitulo,    // Ex: 1, 24

        @JsonProperty("secao_ou_versiculo")
        Integer secaoOuVersiculo // Ex: 1, 18
) {
    /**
     * Verifica se algum filtro foi preenchido.
     * @return true se todos os campos são nulos, false caso contrário.
     */
    public boolean isEmpty() {
        return obraAcronimo == null &&
                livroBiblico == null &&
                capitulo == null &&
                secaoOuVersiculo == null;
    }
}