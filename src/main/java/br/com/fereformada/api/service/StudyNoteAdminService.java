package br.com.fereformada.api.service;

import br.com.fereformada.api.dto.StudyNoteProjection;
import br.com.fereformada.api.dto.StudyNoteRequestDTO; // (Precisaremos criar este)
import br.com.fereformada.api.model.StudyNote;
import br.com.fereformada.api.repository.StudyNoteRepository;
import com.pgvector.PGvector;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudyNoteAdminService {

    private final StudyNoteRepository studyNoteRepository;
    private final GeminiApiClient geminiApiClient;
    private static final Logger logger = LoggerFactory.getLogger(StudyNoteAdminService.class);

    public StudyNoteAdminService(StudyNoteRepository studyNoteRepository, GeminiApiClient geminiApiClient) {
        this.studyNoteRepository = studyNoteRepository;
        this.geminiApiClient = geminiApiClient;
    }

    /**
     * Lista ou Busca notas de estudo (para o painel de admin).
     * Usa Projeção para segurança.
     */
    @Transactional(readOnly = true)
    public Page<StudyNoteProjection> findAll(String search, Pageable pageable) {
        if (search != null && !search.isBlank()) {
            return studyNoteRepository.searchAllProjection(search, pageable);
        } else {
            return studyNoteRepository.findAllProjection(pageable);
        }
    }

    /**
     * Busca uma única nota por ID (para o modal "Editar").
     * Usa Projeção.
     */
    @Transactional(readOnly = true)
    public StudyNoteProjection findById(Long id) {
        return studyNoteRepository.findProjectionById(id)
                .orElseThrow(() -> new EntityNotFoundException("Nota de Estudo não encontrada: " + id));
    }

    /**
     * Cria uma nova Nota de Estudo.
     * Isso inclui vetorização.
     */
    @Transactional
    public StudyNote create(StudyNoteRequestDTO dto) {
        StudyNote note = new StudyNote();
        dto.toEntity(note); // Mapeia os campos
        vectorizeStudyNote(note); // Gera o vetor
        return studyNoteRepository.save(note);
    }

    /**
     * Atualiza uma Nota de Estudo.
     * Re-vetoriza se o conteúdo mudar.
     */
    @Transactional
    public StudyNote update(Long id, StudyNoteRequestDTO dto) {
        StudyNote note = studyNoteRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Nota de Estudo não encontrada: " + id));

        String oldContent = note.getNoteContent();
        dto.toEntity(note); // Atualiza os campos

        // Se o conteúdo mudou, re-vetoriza
        if (oldContent == null || !oldContent.equals(dto.noteContent())) {
            vectorizeStudyNote(note);
        }

        return studyNoteRepository.save(note);
    }

    /**
     * Deleta uma Nota de Estudo.
     */
    @Transactional
    public void delete(Long id) {
        if (!studyNoteRepository.existsById(id)) {
            throw new EntityNotFoundException("Nota de Estudo não encontrada: " + id);
        }
        studyNoteRepository.deleteById(id);
    }

    /**
     * Método helper para vetorizar a nota.
     */
    private void vectorizeStudyNote(StudyNote note) {
        String textToEmbed = note.getBook() + " " +
                note.getStartChapter() + ":" + note.getStartVerse() + "\n" +
                note.getNoteContent();

        try {
            PGvector vector = geminiApiClient.generateEmbedding(textToEmbed);
            note.setNoteVector(vector.toArray());
        } catch (Exception e) {
            logger.error("Falha ao vetorizar Nota de Estudo {}: {}", note.getId(), e.getMessage());
            // Decide-se por não falhar a operação, apenas não vetoriza
            note.setNoteVector(null);
        }
    }
}