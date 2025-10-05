package br.com.fereformada.api.dto;

import br.com.fereformada.api.model.ContentChunk;
import br.com.fereformada.api.model.StudyNote;

// Usamos um 'record' para um objeto de dados simples e imutável.
public record ContextItem(
        String source,      // A fonte já formatada (ex: "Confissão de Fé - Cap. 1, Seção 1")
        String question,    // A pergunta da fonte (para catecismos)
        String content,     // O texto do conteúdo
        double similarityScore // A pontuação de similaridade da busca vetorial
) {

    // Método de fábrica para criar um ContextItem a partir de um ContentChunk
    public static ContextItem from(ContentChunk chunk, double score) {
        String source;
        if ("CATECISMO".equals(chunk.getWork().getType())) {
            source = String.format("%s - Pergunta %d", chunk.getWork().getTitle(), chunk.getSectionNumber());
        } else {
            source = String.format("%s - Cap. %s, Seção %s", chunk.getWork().getTitle(), chunk.getChapterNumber(), chunk.getSectionNumber());
        }
        return new ContextItem(source, chunk.getQuestion(), chunk.getContent(), score);
    }

    // Método de fábrica para criar um ContextItem a partir de uma StudyNote
    public static ContextItem from(StudyNote note, double score) {
        String source = String.format("Bíblia de Genebra - %s %d:%d", note.getBook(), note.getStartChapter(), note.getStartVerse());
        // Se a nota cobre um range de versículos, podemos adicionar isso
        if (note.getStartVerse() != note.getEndVerse()) {
            source += "-" + note.getEndVerse();
        }
        return new ContextItem(source, null, note.getNoteContent(), score);
    }
}