package br.com.fereformada.api.service;

import br.com.fereformada.api.dto.FeedbackRequestDTO;
import br.com.fereformada.api.model.ChatFeedback;
import br.com.fereformada.api.repository.ChatFeedbackRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedbackService {

    private final ChatFeedbackRepository repository;

    public FeedbackService(ChatFeedbackRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void processFeedback(FeedbackRequestDTO dto) {
        // Busca se já existe feedback para essa mensagem para evitar duplicatas
        ChatFeedback feedback = repository.findByMessageId(dto.messageId())
                .orElse(new ChatFeedback());

        // Se for criação, vincula o ID
        if (feedback.getId() == null) {
            feedback.setMessageId(dto.messageId());
        }

        // Atualiza os dados
        feedback.setIsPositive(dto.isPositive());
        feedback.setReason(dto.reason()); // Salva o Enum
        feedback.setComment(dto.comment());

        repository.save(feedback);
    }
}