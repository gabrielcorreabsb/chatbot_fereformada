package br.com.fereformada.api.repository;

import br.com.fereformada.api.dto.StudyNoteProjection;
import br.com.fereformada.api.model.StudyNote;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface StudyNoteRepository extends JpaRepository<StudyNote, Long> {

    long countBySource(String source);
    long countByBook(String book);

    @Query("SELECT CONCAT(s.startChapter, ':', s.startVerse) FROM StudyNote s WHERE s.book = ?1")
    Set<String> findExistingNoteKeysByBook(String bookName);

    @Query("SELECT COUNT(DISTINCT s.book) FROM StudyNote s WHERE s.source = ?1")
    long countDistinctBookBySource(String source);

    // ===== BUSCA VETORIAL MODIFICADA =====
    @Query(nativeQuery = true, value = """
        SELECT
            id,
            book,
            start_chapter,
            start_verse,
            end_chapter,
            end_verse,
            note_content,
            1 - (note_vector <=> CAST(:embedding AS vector)) AS similarity_score
        FROM
            study_notes
        WHERE
            -- Filtros dinâmicos (serão NULL se não usados)
            (:livroBiblico IS NULL OR book = :livroBiblico)
            AND (:capitulo IS NULL OR start_chapter = :capitulo)
            -- Filtra versículo se fornecido
            AND (:versiculo IS NULL OR 
                 (:versiculo >= start_verse AND :versiculo <= end_verse))
        ORDER BY
            similarity_score DESC
        LIMIT :limit
    """)
    List<Object[]> findSimilarNotesRaw(
            @Param("embedding") String embedding,
            @Param("limit") int limit,
            @Param("livroBiblico") String livroBiblico, // NOVO
            @Param("capitulo") Integer capitulo,       // NOVO
            @Param("versiculo") Integer versiculo     // NOVO
    );

    @Query(value = """
        SELECT 
            s.id, s.book, s.start_chapter, s.start_verse, s.end_chapter, s.end_verse, s.note_content,
            ts_rank(
                to_tsvector('portuguese', s.note_content || ' ' || s.book), 
                to_tsquery('portuguese', :tsquery)
            ) as fts_rank
        FROM study_notes s
        WHERE 
            (to_tsvector('portuguese', s.note_content || ' ' || s.book)
              @@ to_tsquery('portuguese', :tsquery))
            -- Filtros dinâmicos (serão NULL se não usados)
            AND (:livroBiblico IS NULL OR book = :livroBiblico)
            AND (:capitulo IS NULL OR start_chapter = :capitulo)
            -- Filtra versículo se fornecido
            AND (:versiculo IS NULL OR 
                 (:versiculo >= start_verse AND :versiculo <= end_verse))
        ORDER BY fts_rank DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> searchByKeywordsFTS(
            @Param("tsquery") String tsquery,
            @Param("limit") int limit,
            @Param("livroBiblico") String livroBiblico, // NOVO
            @Param("capitulo") Integer capitulo,       // NOVO
            @Param("versiculo") Integer versiculo     // NOVO
    );
    // ===== JPQL FALLBACK (mantido) =====
    @Query("""
        SELECT s FROM StudyNote s 
        WHERE LOWER(s.noteContent) LIKE LOWER(CONCAT('%', :keyword, '%'))
           OR LOWER(s.book) LIKE LOWER(CONCAT('%', :keyword, '%'))
        ORDER BY 
            CASE 
                WHEN LOWER(s.book) LIKE LOWER(CONCAT('%', :keyword, '%')) THEN 1
                ELSE 2
            END
        """)
    List<StudyNote> searchByKeywords(@Param("keyword") String keyword, Pageable pageable);

    @Query("""
        SELECT s FROM StudyNote s
        WHERE s.book = :book
          AND ((s.startChapter < :chapter) OR 
               (s.startChapter = :chapter AND s.startVerse <= :verse))
          AND ((s.endChapter > :chapter) OR
               (s.endChapter = :chapter AND s.endVerse >= :verse))
        ORDER BY s.startChapter, s.startVerse
        """)
    List<StudyNote> findByBiblicalReference(
            @Param("book") String book,
            @Param("chapter") int chapter,
            @Param("verse") int verse
    );

    @Query("""
        SELECT s FROM StudyNote s
        WHERE s.book = :book
          AND s.startChapter <= :chapter
          AND s.endChapter >= :chapter
        ORDER BY s.startChapter, s.startVerse
        """)
    List<StudyNote> findByBookAndChapter(
            @Param("book") String book,
            @Param("chapter") int chapter
    );

    // ===================================================================
    // NOVOS MÉTODOS - PAINEL ADMIN
    // ===================================================================

    /**
     * Busca uma página de StudyNotes usando Projeção (seguro contra vetor nulo).
     */
    @Query("SELECT new br.com.fereformada.api.dto.StudyNoteProjection(" +
            "  s.id, s.book, s.startChapter, s.startVerse, s.endChapter, " +
            "  s.endVerse, s.noteContent, s.source " +
            ") " +
            "FROM StudyNote s")
    Page<StudyNoteProjection> findAllProjection(Pageable pageable);

    /**
     * Busca uma página de StudyNotes usando ProjeKAO e um termo de busca.
     */
    @Query("SELECT new br.com.fereformada.api.dto.StudyNoteProjection(" +
            "  s.id, s.book, s.startChapter, s.startVerse, s.endChapter, " +
            "  s.endVerse, s.noteContent, s.source " +
            ") " +
            "FROM StudyNote s " +
            "WHERE ( " +
            "   LOWER(s.book) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            "   LOWER(s.noteContent) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            "   LOWER(s.source) LIKE LOWER(CONCAT('%', :search, '%')) " +
            ")")
    Page<StudyNoteProjection> searchAllProjection(
            @Param("search") String search,
            Pageable pageable
    );

    /**
     * Busca uma ÚNICA StudyNote por ID usando Projeção.
     * Necessário para o modal "Editar".
     */
    @Query("SELECT new br.com.fereformada.api.dto.StudyNoteProjection(" +
            "  s.id, s.book, s.startChapter, s.startVerse, s.endChapter, " +
            "  s.endVerse, s.noteContent, s.source " +
            ") " +
            "FROM StudyNote s " +
            "WHERE s.id = :id")
    Optional<StudyNoteProjection> findProjectionById(@Param("id") Long id);
}