package br.com.fereformada.api.dto;

import br.com.fereformada.api.model.ContentChunk;
import br.com.fereformada.api.model.StudyNote;
import br.com.fereformada.api.model.Work;

import java.util.HashMap;
import java.util.Map;

/**
 * DTO imutável que representa um item de contexto para RAG.
 * ATUALIZADO: Agora carrega metadados ricos para linkagem no Frontend.
 */
public record ContextItem(
        Long id,              // ID do contexto (pode ser igual ao originalId)
        String source,        // Texto legível da fonte (ex: "CFW 1.1")
        String question,
        String content,
        double similarityScore,

        // --- NOVOS CAMPOS DE RASTREAMENTO (TAREFA 1) ---
        Long originalId,              // ID real no banco (ContentChunk.id ou StudyNote.id)
        String sourceType,            // "CHUNK" ou "NOTE"
        String referenceLabel,        // Rótulo curto (ex: "CFW 1.1")
        Map<String, Object> metadata, // Dados para o Frontend criar links

        // --- CAMPOS DE BOOST DINÂMICO ---
        boolean isBiblicalNote,
        String workType,
        Integer boostPriority
) {

    // --- MÉTODOS DE FÁBRICA ('from') ---

    /**
     * MÉTODO PRINCIPAL PARA ContentChunk
     */
    public static ContextItem from(ContentChunk chunk, double score, String contextualSource, Work work) {
        // 1. Construção dos Metadados para Linkagem
        Map<String, Object> meta = new HashMap<>();
        meta.put("workId", work.getId());
        meta.put("workAcronym", work.getAcronym());

        // CORREÇÃO: Usamos o acrônimo em minúsculo como slug, já que Work não tem getSlug()
        if (work.getAcronym() != null) {
            meta.put("workSlug", work.getAcronym().toLowerCase());
        } else {
            meta.put("workSlug", "obra-" + work.getId());
        }

        meta.put("chapter", chunk.getChapterNumber());
        meta.put("section", chunk.getSectionNumber());

        // 2. Definição do Label Curto
        String label = (work.getAcronym() != null ? work.getAcronym() : "DOC") + " " +
                (chunk.getChapterNumber() != null ? chunk.getChapterNumber() : "") +
                (chunk.getSectionNumber() != null ? "." + chunk.getSectionNumber() : "");

        return new ContextItem(
                chunk.getId(),
                contextualSource,
                chunk.getQuestion(),
                chunk.getContent(),
                score,
                // Novos Campos
                chunk.getId(),
                "CHUNK",
                label.trim(),
                meta,
                // Campos Antigos
                false,
                work.getType(),
                work.getBoostPriority()
        );
    }

    /**
     * MÉTODO DE FALLBACK (Compatibilidade)
     */
    public static ContextItem from(ContentChunk chunk, double score) {
        // Recupera a Work de dentro do chunk para garantir acesso aos dados
        return from(chunk, score, chunk.getWork().getTitle(), chunk.getWork());
    }

    /**
     * MÉTODO DE FÁBRICA PARA StudyNote
     */
    public static ContextItem from(StudyNote note, double score) {
        String source = String.format("Bíblia de Genebra - %s %d:%d",
                note.getBook(), note.getStartChapter(), note.getStartVerse());

        // Lógica de formatação de versículo final
        if (note.getStartVerse() != note.getEndVerse() || note.getStartChapter() != note.getEndChapter()) {
            if (note.getStartChapter() == note.getEndChapter()) {
                source += "-" + note.getEndVerse();
            } else {
                source += "-" + note.getEndChapter() + ":" + note.getEndVerse();
            }
        }

        // 1. Construção dos Metadados
        Map<String, Object> meta = new HashMap<>();
        meta.put("book", note.getBook());
        meta.put("chapter", note.getStartChapter());
        meta.put("verse", note.getStartVerse());

        return new ContextItem(
                note.getId(),
                source,
                null,
                note.getNoteContent(),
                score,
                // Novos Campos
                note.getId(),
                "NOTE",
                "Genebra " + note.getBook() + " " + note.getStartChapter(), // Label curto
                meta,
                // Campos Antigos
                true,
                "NOTAS_BIBLICAS",
                3
        );
    }

    // --- MÉTODOS AUXILIARES ---

    public ContextItem withAdjustedScore(double newScore) {
        return new ContextItem(
                this.id,
                this.source,
                this.question,
                this.content,
                newScore, // Score novo
                this.originalId, // Propaga
                this.sourceType, // Propaga
                this.referenceLabel, // Propaga
                this.metadata, // Propaga
                this.isBiblicalNote,
                this.workType,
                this.boostPriority
        );
    }

    public boolean hasQuestion() {
        return this.question != null && !this.question.isEmpty();
    }

    public String workType() { return this.workType; }

    @Override
    public String toString() {
        return String.format("ContextItem{id=%d, label='%s', score=%.3f}", id, referenceLabel, similarityScore);
    }
}