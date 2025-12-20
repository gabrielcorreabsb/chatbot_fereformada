package br.com.fereformada.api.dto;

import java.util.List;
import java.util.UUID; // <--- Importe UUID

public record QueryServiceResult(
        String answer,
        List<SourceReference> references,
        UUID messageId // <--- Mude de Long para UUID
) {}