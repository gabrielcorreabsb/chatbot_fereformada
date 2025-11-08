package br.com.fereformada.api.repository;

import br.com.fereformada.api.dto.ChunkProjection;
import br.com.fereformada.api.dto.ChunkTopicProjection;
import br.com.fereformada.api.model.ContentChunk;
import br.com.fereformada.api.model.Topic;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ContentChunkRepository extends JpaRepository<ContentChunk, Long> {

    // ===================================================================
    // MÉTODOS USADOS PELO RAG (QueryService)
    // ===================================================================

    @Query(nativeQuery = true, value = """
            SELECT
                c.id, c.content, c.question, c.section_title, c.chapter_title,
                c.chapter_number, c.section_number, c.work_id,
                c.subsection_title, c.sub_subsection_title,
                1 - (c.content_vector <=> CAST(:embedding AS vector)) AS similarity_score
            FROM content_chunks c
            JOIN works w ON c.work_id = w.id
            WHERE
                c.content_vector IS NOT NULL
                AND (:obraAcronimo IS NULL OR LOWER(w.acronym) = LOWER(:obraAcronimo))
                AND (:capitulo IS NULL OR c.chapter_number = :capitulo)
                AND (:secao IS NULL OR c.section_number = :secao)
            ORDER BY similarity_score DESC
            LIMIT :limit
            """)
    List<Object[]> findSimilarChunksRaw(
            @Param("embedding") String embedding,
            @Param("limit") int limit,
            @Param("obraAcronimo") String obraAcronimo,
            @Param("capitulo") Integer capitulo,
            @Param("secao") Integer secao
    );

    @Query(value = """
            SELECT 
                c.id, c.content, c.question, c.section_title, c.chapter_title,
                c.chapter_number, c.section_number, c.work_id,
                ts_rank(
                    to_tsvector('portuguese', c.content || ' ' || COALESCE(c.question, '') || ' ' || COALESCE(c.chapter_title, '')), 
                    to_tsquery('portuguese', :tsquery)
                ) as fts_rank
            FROM content_chunks c
            JOIN works w ON c.work_id = w.id
            WHERE 
                (to_tsvector('portuguese', c.content || ' ' || COALESCE(c.question, '') || ' ' || COALESCE(c.chapter_title, ''))
                   @@ to_tsquery('portuguese', :tsquery))
                AND (:obraAcronimo IS NULL OR LOWER(w.acronym) = LOWER(:obraAcronimo))
                AND (:capitulo IS NULL OR c.chapter_number = :capitulo)
                AND (:secao IS NULL OR c.section_number = :secao)
            ORDER BY fts_rank DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> searchByKeywordsFTS(
            @Param("tsquery") String tsquery,
            @Param("limit") int limit,
            @Param("obraAcronimo") String obraAcronimo,
            @Param("capitulo") Integer capitulo,
            @Param("secao") Integer secao
    );

    @Query("SELECT c FROM ContentChunk c JOIN c.work w WHERE " +
            "LOWER(w.acronym) = LOWER(:acronym) AND " +
            "((:chapter IS NULL AND c.chapterNumber IS NULL) OR (c.chapterNumber = :chapter)) AND " +
            "((:section IS NULL AND c.sectionNumber IS NULL) OR (c.sectionNumber = :section))")
    List<ContentChunk> findDirectReference(
            @Param("acronym") String acronym,
            @Param("chapter") Integer chapter,
            @Param("section") Integer section);

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

    long countByContentVectorIsNotNull();

    long countByWorkTitle(@Param("workTitle") String workTitle);

    List<ContentChunk> findByContentContainingIgnoreCase(@Param("keyword") String keyword, Pageable pageable);

    List<ContentChunk> findTopByTopicsAndWorkTitle(@Param("topics") Set<Topic> topics, @Param("workTitle") String workTitle, Pageable pageable);

    // ===================================================================
    // MÉTODOS USADOS PELO PAINEL ADMIN (ContentAdminService)
    // ===================================================================

    @Query("SELECT new br.com.fereformada.api.dto.ChunkProjection(" +
            "  c.id, c.content, c.question, c.sectionTitle, c.chapterTitle, " +
            "  c.chapterNumber, c.sectionNumber, c.subsectionTitle, " +
            "  c.subSubsectionTitle, c.work.id, c.work.title " +
            ") " +
            "FROM ContentChunk c " +
            "WHERE c.work.id = :workId")
    Page<ChunkProjection> findByWorkIdProjection(@Param("workId") Long workId, Pageable pageable);

    @Query("SELECT c FROM ContentChunk c LEFT JOIN FETCH c.topics WHERE c.id IN :ids")
    List<ContentChunk> findChunksWithTopics(@Param("ids") List<Long> ids);

    List<ContentChunk> findAllByWorkId(Long workId);

    @Query("SELECT new br.com.fereformada.api.dto.ChunkTopicProjection(" +
            "  c.id, t.id, t.name, t.description" +
            ") " +
            "FROM ContentChunk c " +
            "JOIN c.topics t " +
            "WHERE c.id IN :ids")
    List<ChunkTopicProjection> findTopicsForChunkIds(@Param("ids") List<Long> ids);

    // ✅ CORREÇÃO: Retorna apenas IDs, sem carregar o content_vector
    @Query("SELECT c.id FROM ContentChunk c WHERE c.work.id = :workId")
    List<Long> findChunkIdsByWorkId(@Param("workId") Long workId);

    // ✅ NOVO: Deleta relações na tabela chunk_topics via SQL nativo
    @Modifying
    @Query(value = "DELETE FROM chunk_topics WHERE chunk_id IN :chunkIds", nativeQuery = true)
    void deleteChunkTopicsByChunkIds(@Param("chunkIds") List<Long> chunkIds);

    // ✅ NOVO: Deleta chunks via SQL nativo (evita carregar entidades)
    @Modifying
    @Query(value = "DELETE FROM content_chunks WHERE id IN :chunkIds", nativeQuery = true)
    void deleteChunksByIds(@Param("chunkIds") List<Long> chunkIds);

    // ✅ NOVO: Verifica se um chunk existe sem carregar o vetor
    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM ContentChunk c WHERE c.id = :id")
    boolean existsByIdSafe(@Param("id") Long id);

    // ✅ NOVO: Deleta um único chunk via SQL nativo
    @Modifying
    @Query(value = "DELETE FROM content_chunks WHERE id = :chunkId", nativeQuery = true)
    void deleteChunkById(@Param("chunkId") Long chunkId);


    //    PROCURA UM ÚNICO CHUNK
    @Query("SELECT new br.com.fereformada.api.dto.ChunkProjection(" +
            "  c.id, c.content, c.question, c.sectionTitle, c.chapterTitle, " +
            "  c.chapterNumber, c.sectionNumber, c.subsectionTitle, " +
            "  c.subSubsectionTitle, c.work.id, c.work.title " +
            ") " +
            "FROM ContentChunk c " +
            "WHERE c.id = :chunkId")
    Optional<ChunkProjection> findProjectionById(@Param("chunkId") Long chunkId);

    @Modifying
    @Query(value = "DELETE FROM chunk_topics WHERE chunk_id = :chunkId", nativeQuery = true)
    void deleteChunkTopicsByChunkId(@Param("chunkId") Long chunkId);
}
