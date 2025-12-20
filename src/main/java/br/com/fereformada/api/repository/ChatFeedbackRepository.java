package br.com.fereformada.api.repository;

import br.com.fereformada.api.dto.FeedbackResponseDTO;
import br.com.fereformada.api.model.ChatFeedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChatFeedbackRepository extends JpaRepository<ChatFeedback, Long> {

    Optional<ChatFeedback> findByMessageId(UUID messageId);

    // 🚀 NOVA QUERY: Traz o Feedback + Conteúdo da Mensagem da tabela 'mensagem'
    // Assumindo que sua entidade Mensagem está mapeada como 'Mensagem'
    @Query("""
        SELECT new br.com.fereformada.api.dto.FeedbackResponseDTO(
            f.id,
            f.messageId,
            f.isPositive,
            f.reason,
            f.comment,
            m.content,
            f.createdAt
        )
        FROM ChatFeedback f
        JOIN Mensagem m ON f.messageId = m.id
        ORDER BY f.createdAt DESC
    """)
    Page<FeedbackResponseDTO> findAllFeedbacksWithContext(Pageable pageable);
}