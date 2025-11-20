package br.com.fereformada.api.dto;

import java.util.List; // <--- Adicione o import
import java.util.UUID;

// Adicione o campo 'references' no final
public record MensagemDTO(
        UUID id,
        String role,
        String content,
        List<SourceReference> references
) {}