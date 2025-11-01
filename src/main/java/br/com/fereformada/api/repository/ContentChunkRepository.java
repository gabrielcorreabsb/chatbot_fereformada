package br.com.fereformada.api.repository;

import br.com.fereformada.api.dto.ChunkProjection; // <-- Importe o DTO de projeção
import br.com.fereformada.api.dto.ChunkTopicProjection;
import br.com.fereformada.api.model.ContentChunk;
import br.com.fereformada.api.model.Topic;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

/**
 * Repositório completo para ContentChunk, corrigido para o bug do 'content_vector'
 * e atendendo a todas as dependências do QueryService e do ContentAdminService.
 */
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
                FROM
                    content_chunks c
                JOIN works w ON c.work_id = w.id
                WHERE
                    c.content_vector IS NOT NULL
                    AND (:obraAcronimo IS NULL OR LOWER(w.acronym) = LOWER(:obraAcronimo))
                    AND (:capitulo IS NULL OR c.chapter_number = :capitulo)
                    AND (:secao IS NULL OR c.section_number = :secao)
                ORDER BY
                    similarity_score DESC
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

    /**
     * Método de fallback (JPQL LIKE) usado pelo QueryService.
     */
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

    // Métodos auxiliares do RAG que você já tinha
    long countByContentVectorIsNotNull();
    long countByWorkTitle(@Param("workTitle") String workTitle);
    List<ContentChunk> findByContentContainingIgnoreCase(@Param("keyword") String keyword, Pageable pageable);
    List<ContentChunk> findTopByTopicsAndWorkTitle(@Param("topics") Set<Topic> topics, @Param("workTitle") String workTitle, Pageable pageable);


    // ===================================================================
    // MÉTODOS USADOS PELO PAINEL ADMIN (ContentAdminService)
    // ===================================================================

    /**
     * PASSO 1 DA BUSCA (Projeção de Duas Etapas):
     * Busca os dados simples usando o DTO 'ChunkProjection'.
     * Esta query IGNORA a coluna 'content_vector' e 'topics',
     * evitando o bug 'PSQLException'.
     */
    @Query("SELECT new br.com.fereformada.api.dto.ChunkProjection(" +
            "  c.id, c.content, c.question, c.sectionTitle, c.chapterTitle, " +
            "  c.chapterNumber, c.sectionNumber, c.subsectionTitle, " +
            "  c.subSubsectionTitle, c.work.id, c.work.title " +
            ") " +
            "FROM ContentChunk c " +
            "WHERE c.work.id = :workId")
    Page<ChunkProjection> findByWorkIdProjection(@Param("workId") Long workId, Pageable pageable);
    /**
     * PASSO 2 DA BUSCA (Projeção de Duas Etapas):
     * Busca as entidades completas (com tópicos) para os IDs
     * que foram encontrados no Passo 1.
     */
    @Query("SELECT c FROM ContentChunk c LEFT JOIN FETCH c.topics WHERE c.id IN :ids")
    List<ContentChunk> findChunksWithTopics(@Param("ids") List<Long> ids);

    /**
     * Usado pelo 'deleteWork' no serviço para limpar os tópicos
     * antes de deletar uma obra.
     */
    List<ContentChunk> findAllByWorkId(Long workId);

    @Query("SELECT new br.com.fereformada.api.dto.ChunkTopicProjection(" +
            "  c.id, t.id, t.name, t.description" +
            ") " +
            "FROM ContentChunk c " +
            "JOIN c.topics t " + // JOIN para a tabela de tópicos
            "WHERE c.id IN :ids")
    List<ChunkTopicProjection> findTopicsForChunkIds(@Param("ids") List<Long> ids);
}