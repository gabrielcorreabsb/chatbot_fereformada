package br.com.fereformada.api.dto;

import br.com.fereformada.api.model.enums.FeedbackReason;
import java.time.LocalDateTime;
import java.util.UUID;

public record FeedbackResponseDTO(
        Long feedbackId,
        UUID messageId,
        Boolean isPositive,
        FeedbackReason reason,
        String comment,
        String aiResponseContent, // O texto que a IA gerou
        LocalDateTime createdAt
) {}