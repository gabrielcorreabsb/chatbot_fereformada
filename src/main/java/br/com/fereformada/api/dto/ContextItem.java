package br.com.fereformada.api.dto;

import br.com.fereformada.api.model.ContentChunk;
import br.com.fereformada.api.model.StudyNote;

/**
 * DTO imutável que representa um item de contexto para RAG.
 * Contém informações sobre a fonte, conteúdo e pontuação de relevância.
 * ESTA VERSÃO FOI ATUALIZADA para lidar com fontes hierárquicas complexas.
 */
public record ContextItem(
        Long id,
        String source,
        String question,
        String content,
        double similarityScore,
        // NOVO CAMPO para diferenciar facilmente as fontes na lógica de reranking.
        boolean isBiblicalNote
) {

    // --- MÉTODOS DE FÁBRICA ('from') ---

    /**
     * MÉTODO PRINCIPAL PARA ContentChunk (Hierárquico)
     * Este novo método aceita uma 'source' pré-formatada e contextual.
     * É usado pelo QueryService para obras complexas como a Teologia Sistemática.
     * RESOLVE O ERRO DE COMPILAÇÃO.
     */
    public static ContextItem from(ContentChunk chunk, double score, String contextualSource) {
        return new ContextItem(
                chunk.getId(),
                contextualSource, // Usa a fonte rica em contexto que foi passada
                chunk.getQuestion(),
                chunk.getContent(),
                score,
                false // Chunks de obras não são notas bíblicas diretas
        );
    }

    /**
     * MÉTODO DE FALLBACK para ContentChunk (Simples)
     * Mantido para compatibilidade com partes do código que não constroem a fonte contextual.
     * Gera uma fonte simples baseada no tipo da obra.
     */
    public static ContextItem from(ContentChunk chunk, double score) {
        String source;
        String workType = chunk.getWork().getType() != null ? chunk.getWork().getType() : "";

        // Mantém a sua lógica original para Catecismos e Confissão
        if ("CATECISMO".equals(workType)) {
            source = String.format("%s - Pergunta %d",
                    chunk.getWork().getTitle(),
                    chunk.getSectionNumber() != null ? chunk.getSectionNumber() : 0
            );
        } else if (chunk.getChapterNumber() != null && chunk.getSectionNumber() != null) {
            source = String.format("%s - Cap. %s, Seção %s",
                    chunk.getWork().getTitle(),
                    chunk.getChapterNumber(),
                    chunk.getSectionNumber()
            );
        } else {
            // Fallback para Institutas ou outras obras sem números de seção claros
            source = chunk.getWork().getTitle() + (chunk.getChapterTitle() != null ? " - " + chunk.getChapterTitle() : "");
        }

        return new ContextItem(
                chunk.getId(),
                source,
                chunk.getQuestion(),
                chunk.getContent(),
                score,
                false
        );
    }

    /**
     * Método de fábrica para criar um ContextItem a partir de uma StudyNote (inalterado).
     */
    public static ContextItem from(StudyNote note, double score) {
        String source = String.format("Bíblia de Genebra - %s %d:%d",
                note.getBook(),
                note.getStartChapter(),
                note.getStartVerse()
        );

        if (note.getStartVerse() != note.getEndVerse() || note.getStartChapter() != note.getEndChapter()) {
            if (note.getStartChapter() == note.getEndChapter()) {
                source += "-" + note.getEndVerse();
            } else {
                source += "-" + note.getEndChapter() + ":" + note.getEndVerse();
            }
        }

        return new ContextItem(
                note.getId(),
                source,
                null, // StudyNotes não têm campo question
                note.getNoteContent(),
                score,
                true // É uma nota bíblica
        );
    }

    // --- MÉTODOS AUXILIARES (mantidos e aprimorados) ---

    /**
     * Cria uma nova instância com score ajustado.
     */
    public ContextItem withAdjustedScore(double newScore) {
        return new ContextItem(
                this.id,
                this.source,
                this.question,
                this.content,
                newScore,
                this.isBiblicalNote
        );
    }

    /**
     * Verifica se este item tem uma pergunta associada.
     */
    public boolean hasQuestion() {
        return this.question != null && !this.question.isEmpty();
    }

    /**
     * Retorna uma preview curta do conteúdo para logging.
     */
    public String getContentPreview(int maxLength) {
        if (content == null || content.isEmpty()) return "";
        return content.length() <= maxLength ? content : content.substring(0, maxLength) + "...";
    }

    /**
     * Verifica se é uma nota bíblica (usando o novo campo para mais eficiência).
     */
    @Override
    public boolean isBiblicalNote() {
        return this.isBiblicalNote;
    }

    // MÉTODOS DE VERIFICAÇÃO DE TIPO (atualizados para incluir a nova obra)
    public boolean isCatechism() { return this.source.contains("Catecismo"); }
    public boolean isConfession() { return this.source.contains("Confissão"); }
    public boolean isInstitutes() { return this.source.contains("Institutas"); }
    public boolean isSystematicTheology() { return this.source.contains("Teologia Sistemática"); }

    /**
     * Retorna o tipo de fonte para logging/analytics.
     */
    public String getSourceType() {
        if (isCatechism()) return "CATECISMO";
        if (isConfession()) return "CONFISSÃO";
        if (isInstitutes()) return "INSTITUTAS";
        if (isSystematicTheology()) return "TEOLOGIA_SISTEMATICA"; // NOVO TIPO
        if (isBiblicalNote()) return "NOTA_BIBLICA";
        return "OUTRO";
    }

    /**
     * Override toString para melhor logging.
     */
    @Override
    public String toString() {
        return String.format("ContextItem{id=%d, source='%s', score=%.3f, type=%s}",
                id, source, similarityScore, getSourceType());
    }
}