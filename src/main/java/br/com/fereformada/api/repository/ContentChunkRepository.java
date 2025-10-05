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
    long countByContentVectorIsNotNull();

    @Query("SELECT COUNT(c) FROM ContentChunk c JOIN c.work w WHERE w.title = :workTitle")
    long countByWorkTitle(@Param("workTitle") String workTitle);

    // *** BUSCA POR SIMILARIDADE - USANDO QUERY NATIVA SEM MAPEAR content_vector ***
    @Query(nativeQuery = true, value = """
        SELECT
            id, content, question, section_title, chapter_title,
            chapter_number, section_number, work_id,
            1 - (content_vector <=> CAST(:embedding AS vector)) AS similarity_score
        FROM
            content_chunks
        ORDER BY
            similarity_score DESC
        LIMIT :limit
    """)
    List<Object[]> findSimilarChunksRaw(String embedding, int limit);


    // *** BUSCA POR PALAVRA-CHAVE - SEM content_vector ***
    @Query("""
        SELECT c FROM ContentChunk c 
        WHERE LOWER(c.content) LIKE LOWER(CONCAT('%', :keyword, '%'))
        ORDER BY c.id
        """)
    List<ContentChunk> findByContentContainingIgnoreCase(
            @Param("keyword") String keyword,
            Pageable pageable
    );

    // *** BUSCA POR TÓPICOS ***
    @Query("""
        SELECT c FROM ContentChunk c 
        JOIN c.topics t 
        JOIN c.work w 
        WHERE t IN :topics AND w.title = :workTitle
        ORDER BY c.chapterNumber, c.sectionNumber
        """)
    List<ContentChunk> findTopByTopicsAndWorkTitle(
            @Param("topics") Set<Topic> topics,
            @Param("workTitle") String workTitle,
            Pageable pageable
    );
}