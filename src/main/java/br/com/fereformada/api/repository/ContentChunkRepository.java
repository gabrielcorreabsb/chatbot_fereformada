package br.com.fereformada.api.repository;

import br.com.fereformada.api.model.ContentChunk;
import br.com.fereformada.api.model.Topic;
import org.springframework.data.domain.Pageable; // <-- Importante
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface ContentChunkRepository extends JpaRepository<ContentChunk, Long> {

    // NOVO MÉTODO DE BUSCA:
    // Encontra os N trechos mais relevantes (usando Pageable) para um conjunto de tópicos
    // DENTRO de uma obra específica.
    @Query("SELECT c FROM ContentChunk c JOIN c.topics t WHERE c.work.title = :workTitle AND t IN :topics")
    List<ContentChunk> findTopByTopicsAndWorkTitle(
            @Param("topics") Set<Topic> topics,
            @Param("workTitle") String workTitle,
            Pageable pageable
    );

    // **** NOVO MÉTODO PARA BUSCA POR PALAVRA-CHAVE ****
    List<ContentChunk> findByContentContainingIgnoreCase(String keyword, Pageable pageable);

    // Método para contar chunks por título da obra
    @Query("SELECT COUNT(c) FROM ContentChunk c JOIN c.work w WHERE w.title = :workTitle")
    long countByWorkTitle(@Param("workTitle") String workTitle);

}