package br.com.fereformada.api.dto;

import java.util.List;
import java.util.UUID;

public record ChatApiResponse(
        String answer, // A resposta da IA (com ¹, ²)
        List<SourceReference> references, // A lista de fontes para o rodapé
        UUID chatId // <-- Este campo estava faltando
) {}