package br.com.fereformada.api.dto;

import br.com.fereformada.api.model.enums.FeedbackReason;
import java.util.UUID; // <--- Importe UUID

public record FeedbackRequestDTO(
        UUID messageId, // <--- Mude de Long para UUID
        Boolean isPositive,
        FeedbackReason reason,
        String comment
) {}