package br.com.fereformada.api.repository;

import br.com.fereformada.api.model.StudyNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

@Repository
public interface StudyNoteRepository extends JpaRepository<StudyNote, Long> {

    /**
     * Conta o número de notas de estudo de uma fonte específica.
     * Este método será usado pelo DatabaseSeeder para verificar se as notas
     * da Bíblia de Genebra já foram carregadas.
     *
     * @param source A fonte da nota (ex: "Bíblia de Genebra").
     * @return O número total de notas encontradas para a fonte.
     */
    long countBySource(String source);
    long countByBook(String book);

    @Query("SELECT CONCAT(s.startChapter, ':', s.startVerse) FROM StudyNote s WHERE s.book = ?1")
    Set<String> findExistingNoteKeysByBook(String bookName);

    @Query("SELECT COUNT(DISTINCT s.book) FROM StudyNote s WHERE s.source = ?1")
    long countDistinctBookBySource(String source);

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
        ORDER BY
            similarity_score DESC
        LIMIT :limit
    """)
    List<Object[]> findSimilarNotesRaw(String embedding, int limit);
}