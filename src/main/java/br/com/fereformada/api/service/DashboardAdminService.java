package br.com.fereformada.api.service;

import br.com.fereformada.api.dto.ContentCountByWorkDTO;
import br.com.fereformada.api.dto.DashboardStatsDTO;
import br.com.fereformada.api.dto.StudyNoteSourceDTO;
import br.com.fereformada.api.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DashboardAdminService {

    // Injetamos todos os repositórios necessários
    private final WorkRepository workRepository;
    private final AuthorRepository authorRepository;
    private final TopicRepository topicRepository;
    private final ContentChunkRepository contentChunkRepository;
    private final StudyNoteRepository studyNoteRepository;

    public DashboardAdminService(WorkRepository workRepository,
                                 AuthorRepository authorRepository,
                                 TopicRepository topicRepository,
                                 ContentChunkRepository contentChunkRepository,
                                 StudyNoteRepository studyNoteRepository) {
        this.workRepository = workRepository;
        this.authorRepository = authorRepository;
        this.topicRepository = topicRepository;
        this.contentChunkRepository = contentChunkRepository;
        this.studyNoteRepository = studyNoteRepository;
    }

    @Transactional(readOnly = true)
    public DashboardStatsDTO getDashboardStats() {

        // 1. Contagens Simples
        long works = workRepository.count();
        long authors = authorRepository.count();
        long topics = topicRepository.count();
        long chunks = contentChunkRepository.count();
        long notes = studyNoteRepository.count();

        // 2. Métricas de Saúde (Vetorização)
        long chunksNoVec = contentChunkRepository.countByContentVectorIsNull();
        long notesNoVec = studyNoteRepository.countByNoteVectorIsNull();

        // 3. Dados para Gráficos
        List<StudyNoteSourceDTO> notesBySource = studyNoteRepository.getStudyNoteCountsBySource();
        List<ContentCountByWorkDTO> chunksByWork = contentChunkRepository.findChunkCountsByWorkAcronym();

        // 4. Monta o DTO final
        return new DashboardStatsDTO(
                works, authors, topics,
                chunks, chunksNoVec,
                notes, notesNoVec,
                notesBySource,
                chunksByWork
        );
    }
}