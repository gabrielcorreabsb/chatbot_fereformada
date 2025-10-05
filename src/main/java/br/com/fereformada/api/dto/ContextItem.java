package br.com.fereformada.api.dto;

import br.com.fereformada.api.model.ContentChunk;
import br.com.fereformada.api.model.StudyNote;

/**
 * DTO imutável que representa um item de contexto para RAG.
 * Contém informações sobre a fonte, conteúdo e pontuação de relevância.
 */
public record ContextItem(
        Long id,            // ID único para deduplicação
        String source,      // A fonte já formatada (ex: "Confissão de Fé - Cap. 1, Seção 1")
        String question,    // A pergunta da fonte (para catecismos, pode ser null)
        String content,     // O texto do conteúdo
        double similarityScore // A pontuação de similaridade da busca vetorial
) {

    /**
     * Método de fábrica para criar um ContextItem a partir de um ContentChunk
     */
    public static ContextItem from(ContentChunk chunk, double score) {
        String source;
        if ("CATECISMO".equals(chunk.getWork().getType())) {
            source = String.format("%s - Pergunta %d",
                    chunk.getWork().getTitle(),
                    chunk.getSectionNumber()
            );
        } else {
            source = String.format("%s - Cap. %s, Seção %s",
                    chunk.getWork().getTitle(),
                    chunk.getChapterNumber(),
                    chunk.getSectionNumber()
            );
        }

        return new ContextItem(
                chunk.getId(),
                source,
                chunk.getQuestion(),
                chunk.getContent(),
                score
        );
    }

    /**
     * Método de fábrica para criar um ContextItem a partir de uma StudyNote
     */
    public static ContextItem from(StudyNote note, double score) {
        String source = String.format("Bíblia de Genebra - %s %d:%d",
                note.getBook(),
                note.getStartChapter(),
                note.getStartVerse()
        );

        // Se a nota cobre um range de versículos, adicionar o range
        if (note.getStartVerse() != note.getEndVerse() ||
                note.getStartChapter() != note.getEndChapter()) {

            if (note.getStartChapter() == note.getEndChapter()) {
                // Mesmo capítulo: "Gênesis 1:1-3"
                source += "-" + note.getEndVerse();
            } else {
                // Capítulos diferentes: "Gênesis 1:1-2:3"
                source += "-" + note.getEndChapter() + ":" + note.getEndVerse();
            }
        }

        return new ContextItem(
                note.getId(),
                source,
                null, // StudyNotes não têm campo question
                note.getNoteContent(),
                score
        );
    }

    /**
     * Cria uma nova instância com score ajustado.
     * Usado durante o processo de reranking.
     */
    public ContextItem withAdjustedScore(double newScore) {
        return new ContextItem(
                this.id,
                this.source,
                this.question,
                this.content,
                newScore
        );
    }

    /**
     * Verifica se este item tem uma pergunta associada.
     * Útil para lógica de boosting no reranking.
     */
    public boolean hasQuestion() {
        return this.question != null && !this.question.isEmpty();
    }

    /**
     * Retorna uma preview curta do conteúdo para logging.
     */
    public String getContentPreview(int maxLength) {
        if (content == null || content.isEmpty()) {
            return "";
        }

        if (content.length() <= maxLength) {
            return content;
        }

        return content.substring(0, maxLength) + "...";
    }

    /**
     * Verifica se é uma nota bíblica (StudyNote)
     */
    public boolean isBiblicalNote() {
        return this.source.contains("Bíblia de Genebra");
    }

    /**
     * Verifica se é de um catecismo
     */
    public boolean isCatechism() {
        return this.source.contains("Catecismo");
    }

    /**
     * Verifica se é da Confissão de Fé
     */
    public boolean isConfession() {
        return this.source.contains("Confissão");
    }

    /**
     * Verifica se é das Institutas
     */
    public boolean isInstitutes() {
        return this.source.contains("Institutas");
    }

    /**
     * Retorna o tipo de fonte para logging/analytics
     */
    public String getSourceType() {
        if (isCatechism()) return "CATECISMO";
        if (isConfession()) return "CONFISSÃO";
        if (isInstitutes()) return "INSTITUTAS";
        if (isBiblicalNote()) return "NOTA_BÍBLICA";
        return "OUTRO";
    }

    /**
     * Override toString para melhor logging
     */
    @Override
    public String toString() {
        return String.format("ContextItem{id=%d, source='%s', score=%.3f, type=%s}",
                id, source, similarityScore, getSourceType());
    }
}