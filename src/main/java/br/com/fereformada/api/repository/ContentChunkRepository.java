package br.com.fereformada.api.repository;

import br.com.fereformada.api.model.ContentChunk;
import br.com.fereformada.api.model.Topic;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
                    c.id, c.content, c.question, c.section_title, c.chapter_title,
                    c.chapter_number, c.section_number, c.work_id,
                    c.subsection_title, c.sub_subsection_title,
                    1 - (c.content_vector <=> CAST(:embedding AS vector)) AS similarity_score
                FROM
                    content_chunks c
                JOIN works w ON c.work_id = w.id -- JOIN para filtrar por acrônimo
                WHERE
                    c.content_vector IS NOT NULL
                    -- Filtros dinâmicos (serão NULL se não usados)
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
            @Param("obraAcronimo") String obraAcronimo, // NOVO
            @Param("capitulo") Integer capitulo,       // NOVO
            @Param("secao") Integer secao             // NOVO
    );

    // ===== FTS MODIFICADO =====
    @Query(value = """
            SELECT 
                c.id, c.content, c.question, c.section_title, c.chapter_title,
                c.chapter_number, c.section_number, c.work_id,
                ts_rank(
                    to_tsvector('portuguese', c.content || ' ' || COALESCE(c.question, '') || ' ' || COALESCE(c.chapter_title, '')), 
                    to_tsquery('portuguese', :tsquery)
                ) as fts_rank
            FROM content_chunks c
            JOIN works w ON c.work_id = w.id -- JOIN para filtrar por acrônimo
            WHERE 
                (to_tsvector('portuguese', c.content || ' ' || COALESCE(c.question, '') || ' ' || COALESCE(c.chapter_title, ''))
                   @@ to_tsquery('portuguese', :tsquery))
                -- Filtros dinâmicos (serão NULL se não usados)
                AND (:obraAcronimo IS NULL OR LOWER(w.acronym) = LOWER(:obraAcronimo))
                AND (:capitulo IS NULL OR c.chapter_number = :capitulo)
                AND (:secao IS NULL OR c.section_number = :secao)
            ORDER BY fts_rank DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> searchByKeywordsFTS(
            @Param("tsquery") String tsquery,
            @Param("limit") int limit,
            @Param("obraAcronimo") String obraAcronimo, // NOVO
            @Param("capitulo") Integer capitulo,       // NOVO
            @Param("secao") Integer secao             // NOVO
    );

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

            // NOVA LÓGICA DE CAPÍTULO:
            // Se o parâmetro :chapter for NULL, nós EXIGIMOS que c.chapterNumber SEJA NULL.
            // Se o parâmetro :chapter NÃO for NULL, nós EXIGIMOS que c.chapterNumber seja IGUAL a ele.
            "((:chapter IS NULL AND c.chapterNumber IS NULL) OR (c.chapterNumber = :chapter)) AND " +

            // NOVA LÓGICA DE SEÇÃO:
            // Mesma lógica para a seção.
            "((:section IS NULL AND c.sectionNumber IS NULL) OR (c.sectionNumber = :section))")
    List<ContentChunk> findDirectReference(
            @Param("acronym") String acronym,
            @Param("chapter") Integer chapter,      // Deve ser Integer (wrapper)
            @Param("section") Integer section);

    /**
     * Encontra uma página de Chunks pertencentes a uma Obra (Work) específica.
     * O Spring Data JPA cria a query automaticamente a partir do nome do método.
     */
    Page<ContentChunk> findByWorkId(Long workId, Pageable pageable);

    /**
     * Deleta todos os Chunks associados a um workId.
     * Usamos @Modifying e @Query para uma deleção em massa eficiente.
     * AVISO: Isso NÃO limpará a tabela 'chunk_topics'.
     * Para isso, a lógica em deleteWork no serviço precisaria ser mais complexa
     * (buscar chunks, limpar 'topics', salvar, e então deletar).
     * Por enquanto, isso corresponde ao seu código.
     */
    @Modifying
    @Query("DELETE FROM ContentChunk c WHERE c.work.id = :workId")
    void deleteByWorkId(@Param("workId") Long workId);
}
