package br.com.fereformada.api.dto;

import br.com.fereformada.api.model.ContentChunk;
import br.com.fereformada.api.model.StudyNote;
import br.com.fereformada.api.model.Work; // Import necessário

/**
 * DTO imutável que representa um item de contexto para RAG.
 * ATUALIZADO para suportar boosts dinâmicos (Bloco 1).
 */
public record ContextItem(
        Long id,
        String source,
        String question,
        String content,
        double similarityScore,

        // --- CAMPOS DE BOOST DINÂMICO (NOMENCLATURA CORRIGIDA) ---
        boolean isBiblicalNote, // O 'record' cria o método 'isBiblicalNote()' automaticamente
        String workType,        // "CATECISMO", "CONFISSAO", etc.
        Integer boostPriority   // 0, 1, 2, 3...
) {

    // --- MÉTODOS DE FÁBRICA ('from') ---

    /**
     * MÉTODO PRINCIPAL PARA ContentChunk (Atualizado)
     * Aceita os dados dinâmicos da Work.
     */
    public static ContextItem from(ContentChunk chunk, double score, String contextualSource, Work work) {
        return new ContextItem(
                chunk.getId(),
                contextualSource, // Fonte rica em contexto
                chunk.getQuestion(),
                chunk.getContent(),
                score,
                false, // Não é nota bíblica
                work.getType(),
                work.getBoostPriority()
        );
    }

    /**
     * MÉTODO DE FALLBACK (Atualizado)
     * Mantido para compatibilidade, também carrega dados de boost.
     */
    public static ContextItem from(ContentChunk chunk, double score) {
        Work work = chunk.getWork();
        String source;
        String workType = work.getType() != null ? work.getType() : "";

        // Lógica de 'source' original
        if ("CATECISMO".equals(workType)) {
            source = String.format("%s - Pergunta %d",
                    work.getTitle(),
                    chunk.getSectionNumber() != null ? chunk.getSectionNumber() : 0
            );
        } else if (chunk.getChapterNumber() != null && chunk.getSectionNumber() != null) {
            source = String.format("%s - Cap. %s, Seção %s",
                    work.getTitle(),
                    chunk.getChapterNumber(),
                    chunk.getSectionNumber()
            );
        } else {
            source = work.getTitle() + (chunk.getChapterTitle() != null ? " - " + chunk.getChapterTitle() : "");
        }

        return new ContextItem(
                chunk.getId(),
                source,
                chunk.getQuestion(),
                chunk.getContent(),
                score,
                false, // Não é nota bíblica
                work.getType(),
                work.getBoostPriority()
        );
    }

    /**
     * Método de fábrica para StudyNote (Atualizado)
     * Preenche os novos campos com valores fixos.
     */
    public static ContextItem from(StudyNote note, double score) {
        String source = String.format("Bíblia de Genebra - %s %d:%d",
                note.getBook(),
                note.getStartChapter(),
                note.getStartVerse()
        );
        // Lógica de formatação de versículo final
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
                null, // StudyNotes não têm 'question'
                note.getNoteContent(),
                score,
                true, // É uma nota bíblica
                "NOTAS_BIBLICAS", // Tipo fixo
                3 // Prioridade máxima fixa (Opção 1)
        );
    }

    // --- MÉTODOS AUXILIARES ---

    /**
     * Cria uma nova instância com score ajustado (Atualizado).
     */
    public ContextItem withAdjustedScore(double newScore) {
        return new ContextItem(
                this.id,
                this.source,
                this.question,
                this.content,
                newScore,
                this.isBiblicalNote, // Propaga o valor
                this.workType,
                this.boostPriority
        );
    }

    public boolean hasQuestion() {
        return this.question != null && !this.question.isEmpty();
    }

    public String getContentPreview(int maxLength) {
        if (content == null || content.isEmpty()) return "";
        return content.length() <= maxLength ? content : content.substring(0, maxLength) + "...";
    }

    // --- MÉTODOS CORRIGIDOS ---

    // O método 'isBiblicalNote()' é criado automaticamente pelo 'record'.
    // O @Override que causou o erro 1 foi REMOVIDO.

    /**
     * Retorna o tipo de fonte (lendo o campo dinâmico).
     */
    public String getSourceType() {
        return this.workType;
    }

    /**
     * Override toString para melhor logging (Atualizado).
     */
    @Override // @Override aqui está CORRETO
    public String toString() {
        return String.format("ContextItem{id=%d, source='%s', score=%.3f, type=%s, boost=%d}",
                id, source, similarityScore, workType, boostPriority != null ? boostPriority : -1);
    }
}