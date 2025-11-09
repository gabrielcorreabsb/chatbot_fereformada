package br.com.fereformada.api.dto;

import java.util.List;

/**
 * DTO principal que agrega todas as estatísticas para o Dashboard.
 */
public record DashboardStatsDTO(
        long totalWorks,
        long totalAuthors,
        long totalTopics,

        // Métricas de Chunks
        long totalChunks,
        long chunksWithoutVector,

        // Métricas de Notas de Estudo
        long totalStudyNotes,
        long notesWithoutVector,

        // Para o Gráfico 1 (Notas por Fonte)
        List<StudyNoteSourceDTO> notesBySource,

        // Para o Gráfico 2 (Chunks por Obra)
        List<ContentCountByWorkDTO> chunksByWork
) {}