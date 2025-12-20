package br.com.fereformada.api.dto;

import java.util.List;
import java.util.UUID;

public record ChatApiResponse(
        String answer,
        List<SourceReference> references,
        UUID chatId,
        UUID messageId // <--- Mude de Long para UUID
) {}