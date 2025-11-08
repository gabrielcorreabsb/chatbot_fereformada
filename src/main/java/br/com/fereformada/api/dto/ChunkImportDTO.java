package br.com.fereformada.api.dto;

import java.util.List;

public record ChunkImportDTO(
        String workAcronym, // "CFW", "CM", "ICR", "TSB"
        String chapterTitle,
        Integer chapterNumber,
        String sectionTitle,
        Integer sectionNumber,
        String subsectionTitle,
        String subSubsectionTitle,
        String question, // Para catecismos
        String content, // O texto principal (resposta do catecismo ou parágrafo)
        List<String> topics // Lista de nomes de tópicos (ex: ["Justificação pela Fé", "A Lei de Deus"])
) {
}