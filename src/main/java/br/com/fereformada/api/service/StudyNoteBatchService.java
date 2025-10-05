package br.com.fereformada.api.service;

import br.com.fereformada.api.model.StudyNote;
import br.com.fereformada.api.repository.StudyNoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class StudyNoteBatchService {

    private final StudyNoteRepository studyNoteRepository;

    public StudyNoteBatchService(StudyNoteRepository studyNoteRepository) {
        this.studyNoteRepository = studyNoteRepository;
    }

    /**
     * Salva uma lista (lote) de notas de estudo.
     * A anotação @Transactional garante que esta operação seja atômica.
     * Cada chamada a este método cria e commita uma nova transação.
     */
    @Transactional
    public void saveBatch(List<StudyNote> notes) {
        if (notes != null && !notes.isEmpty()) {
            studyNoteRepository.saveAll(notes);
        }
    }
}