package br.com.fereformada.api.repository;

import br.com.fereformada.api.model.StudyNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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

}