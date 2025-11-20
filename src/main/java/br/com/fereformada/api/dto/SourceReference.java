package br.com.fereformada.api.dto;

import lombok.Builder;
import java.util.Map;

/**
 * DTO final enviado ao Frontend.
 * Agora inclui metadados ricos para construção de links e ícones.
 */
@Builder
public record SourceReference(
        int number,           // O número de referência (ex: 1)
        String text,          // Nome legível da fonte (ex: "Bíblia de Genebra - Romanos 8:29")
        String preview,       // Trecho ou resumo do conteúdo (para o tooltip ou lista)

        // --- NOVOS CAMPOS (Tarefa 1) ---
        Long sourceId,        // ID original do banco (para navegação)
        String type,          // "CHUNK" (Livro) ou "NOTE" (Nota de Estudo)
        String label,         // Rótulo curto para chips (ex: "CFW 1.1")
        Map<String, Object> metadata // Dados extras (slug, capítulo, versículo) para gerar a URL
) {
}