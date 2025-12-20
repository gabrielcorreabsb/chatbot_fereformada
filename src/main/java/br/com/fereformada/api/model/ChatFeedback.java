package br.com.fereformada.api.model;

import br.com.fereformada.api.model.enums.FeedbackReason;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID; // <--- Importe UUID

@Entity
@Table(name = "chat_feedback")
@Data
public class ChatFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // O ID do feedback continua Long (sequencial)

    @Column(nullable = false)
    private UUID messageId; // <--- Mude de Long para UUID (Para bater com a tabela Mensagem)

    @Column(nullable = false)
    private Boolean isPositive;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason")
    private FeedbackReason reason;

    @Column(columnDefinition = "TEXT")
    private String comment;

    private LocalDateTime createdAt = LocalDateTime.now();
}