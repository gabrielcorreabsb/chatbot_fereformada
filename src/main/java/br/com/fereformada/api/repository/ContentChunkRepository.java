package br.com.fereformada.api.repository;

import br.com.fereformada.api.model.ContentChunk;
import br.com.fereformada.api.model.Topic;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface ContentChunkRepository extends JpaRepository<ContentChunk, Long> {
    long countByContentVectorIsNotNull();

    @Query("SELECT COUNT(c) FROM ContentChunk c JOIN c.work w WHERE w.title = :workTitle")
    long countByWorkTitle(@Param("workTitle") String workTitle);

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

    // ===== NOVO: FTS POSTGRESQL =====
    @Query(value = """
        SELECT 
            c.id, c.content, c.question, c.section_title, c.chapter_title,
            c.chapter_number, c.section_number, c.work_id,
            ts_rank(
                to_tsvector('portuguese', c.content || ' ' || COALESCE(c.question, '') || ' ' || COALESCE(c.chapter_title, '')), 
                to_tsquery('portuguese', :tsquery)
            ) as fts_rank
        FROM content_chunks c
        WHERE to_tsvector('portuguese', c.content || ' ' || COALESCE(c.question, '') || ' ' || COALESCE(c.chapter_title, ''))
              @@ to_tsquery('portuguese', :tsquery)
        ORDER BY fts_rank DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> searchByKeywordsFTS(@Param("tsquery") String tsquery, @Param("limit") int limit);

    // ===== JPQL FALLBACK (mantido) =====
    @Query("""
        SELECT c FROM ContentChunk c 
        WHERE LOWER(c.content) LIKE LOWER(CONCAT('%', :keyword, '%'))
           OR LOWER(COALESCE(c.question, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
           OR LOWER(COALESCE(c.chapterTitle, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
        ORDER BY 
            CASE 
                WHEN LOWER(COALESCE(c.question, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) THEN 1
                WHEN LOWER(COALESCE(c.chapterTitle, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) THEN 2
                ELSE 3
            END
        """)
    List<ContentChunk> searchByKeywords(@Param("keyword") String keyword, Pageable pageable);

    @Query("""
        SELECT c FROM ContentChunk c 
        WHERE LOWER(c.content) LIKE LOWER(CONCAT('%', :keyword, '%'))
        ORDER BY c.id
        """)
    List<ContentChunk> findByContentContainingIgnoreCase(
            @Param("keyword") String keyword,
            Pageable pageable
    );

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

    @Query("SELECT c FROM ContentChunk c JOIN c.work w WHERE " +
            "LOWER(w.acronym) = LOWER(:acronym) AND " +
            "c.chapterNumber = :chapterOrQuestion AND " +
            "(:section IS NULL OR c.sectionNumber = :section)")
    List<ContentChunk> findDirectReference(
            @Param("acronym") String acronym,
            @Param("chapterOrQuestion") int chapterOrQuestion,
            @Param("section") Integer section);
}
