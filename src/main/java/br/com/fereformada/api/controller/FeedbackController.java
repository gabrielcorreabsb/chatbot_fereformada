package br.com.fereformada.api.controller;

import br.com.fereformada.api.dto.FeedbackRequestDTO;
import br.com.fereformada.api.dto.FeedbackResponseDTO; // Import novo
import br.com.fereformada.api.model.ChatFeedback;
import br.com.fereformada.api.repository.ChatFeedbackRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/v1/feedback")
public class FeedbackController {

    private final ChatFeedbackRepository repository;

    public FeedbackController(ChatFeedbackRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    public ResponseEntity<?> registrarFeedback(@RequestBody FeedbackRequestDTO dto) {
        Optional<ChatFeedback> existente = repository.findByMessageId(dto.messageId());

        ChatFeedback feedback = existente.orElse(new ChatFeedback());
        feedback.setMessageId(dto.messageId());
        feedback.setIsPositive(dto.isPositive());
        feedback.setReason(dto.reason()); // Certifique-se de salvar o reason!
        feedback.setComment(dto.comment());

        repository.save(feedback);
        return ResponseEntity.ok().build();
    }

    // 🚀 NOVO ENDPOINT DE ADMIN
    @GetMapping
    public ResponseEntity<Page<FeedbackResponseDTO>> listarFeedbacks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        // Traz os feedbacks mais recentes primeiro
        Page<FeedbackResponseDTO> feedbacks = repository.findAllFeedbacksWithContext(
                PageRequest.of(page, size)
        );
        return ResponseEntity.ok(feedbacks);
    }
}