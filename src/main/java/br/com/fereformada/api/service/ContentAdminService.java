package br.com.fereformada.api.service;

import br.com.fereformada.api.dto.ChunkRequestDTO; // <-- CORRIGIDO para maiúsculas
import br.com.fereformada.api.dto.WorkDTO;       // <-- CORRIGIDO para maiúsculas
import br.com.fereformada.api.model.Author;
import br.com.fereformada.api.model.ContentChunk;
import br.com.fereformada.api.model.Work;
import br.com.fereformada.api.repository.AuthorRepository;
import br.com.fereformada.api.repository.ContentChunkRepository;
import br.com.fereformada.api.repository.WorkRepository;
import com.pgvector.PGvector;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContentAdminService {

    private final ContentChunkRepository contentChunkRepository;
    private final WorkRepository workRepository;
    private final AuthorRepository authorRepository;
    private final GeminiApiClient geminiApiClient;

    public ContentAdminService(ContentChunkRepository contentChunkRepository,
                               WorkRepository workRepository,
                               AuthorRepository authorRepository,
                               GeminiApiClient geminiApiClient) {
        this.contentChunkRepository = contentChunkRepository;
        this.workRepository = workRepository;
        this.authorRepository = authorRepository;
        this.geminiApiClient = geminiApiClient;
    }

    // --- Métodos de Obras (Works) ---

    @Transactional
    public Work createWork(WorkDTO dto) { // <-- CORRIGIDO
        Author author = authorRepository.findById(dto.authorId())
                .orElseThrow(() -> new EntityNotFoundException("Autor não encontrado: " + dto.authorId()));

        Work work = new Work();
        work.setTitle(dto.title());
        work.setType(dto.type());
        work.setAcronym(dto.acronym());
        work.setPublicationYear(dto.publicationYear());
        work.setAuthor(author);

        return workRepository.save(work);
    }

    @Transactional
    public Work updateWork(Long workId, WorkDTO dto) { // <-- CORRIGIDO
        Work work = workRepository.findById(workId)
                .orElseThrow(() -> new EntityNotFoundException("Obra não encontrada: " + workId));
        Author author = authorRepository.findById(dto.authorId())
                .orElseThrow(() -> new EntityNotFoundException("Autor não encontrado: " + dto.authorId()));

        work.setTitle(dto.title());
        work.setType(dto.type());
        work.setAcronym(dto.acronym());
        work.setPublicationYear(dto.publicationYear());
        work.setAuthor(author);

        return workRepository.save(work);
    }

    @Transactional
    public void deleteWork(Long workId) {
        if (!workRepository.existsById(workId)) {
            throw new EntityNotFoundException("Obra não encontrada: " + workId);
        }
        // Isso agora vai funcionar porque vamos definir o método no repositório
        contentChunkRepository.deleteByWorkId(workId);
        workRepository.deleteById(workId);
    }

    public Page<Work> findAllWorks(Pageable pageable) {
        return workRepository.findAll(pageable);
    }

    // --- Métodos de Chunks ---

    @Transactional
    public ContentChunk createChunk(Long workId, ChunkRequestDTO dto) { // <-- CORRIGIDO
        Work work = workRepository.findById(workId)
                .orElseThrow(() -> new EntityNotFoundException("Obra (Work) não encontrada com ID: " + workId));

        ContentChunk chunk = new ContentChunk();
        dto.toEntity(chunk);
        chunk.setWork(work);
        vectorizeChunk(chunk);
        return contentChunkRepository.save(chunk);
    }

    @Transactional
    public ContentChunk updateChunk(Long chunkId, ChunkRequestDTO dto) { // <-- CORRIGIDO
        ContentChunk chunk = contentChunkRepository.findById(chunkId)
                .orElseThrow(() -> new EntityNotFoundException("Chunk não encontrado com ID: " + chunkId)); // <-- CORRIGIDO (removido o "Z")

        String oldContent = chunk.getContent();
        dto.toEntity(chunk);

        // Se o texto mudou, re-vetoriza
        if (oldContent == null || !oldContent.equals(dto.content()) || (chunk.getQuestion() != null && !chunk.getQuestion().equals(dto.question()))) {
            vectorizeChunk(chunk);
        }
        return contentChunkRepository.save(chunk);
    }

    @Transactional
    public void deleteChunk(Long chunkId) {
        if (!contentChunkRepository.existsById(chunkId)) {
            throw new EntityNotFoundException("Chunk não encontrado com ID: " + chunkId); // <-- CORRIGIDO (só por consistência)
        }
        contentChunkRepository.deleteById(chunkId);
    }

    public Page<ContentChunk> findChunksByWork(Long workId, Pageable pageable) {
        // Isso agora vai funcionar porque vamos definir o método no repositório
        return contentChunkRepository.findByWorkId(workId, pageable);
    }

    // --- Lógica Central de Vetorização ---

    private void vectorizeChunk(ContentChunk chunk) {
        String textToEmbed = (chunk.getQuestion() != null ? chunk.getQuestion() + "\n" : "") +
                (chunk.getContent() != null ? chunk.getContent() : "");

        if (textToEmbed.isBlank()) {
            chunk.setContentVector(null);
            return;
        }

        try {
            PGvector vector = geminiApiClient.generateEmbedding(textToEmbed);
            chunk.setContentVector(convertPGvectorToFloatArray(vector));
        } catch (Exception e) {
            System.err.println("Falha ao vetorizar chunk " + chunk.getId() + ": " + e.getMessage());
        }
    }

    // Supondo que você tenha este método em algum lugar.
    // Se ele estava no seu Seeder, considere torná-lo um utilitário estático.
    private float[] convertPGvectorToFloatArray(PGvector pgVector) {
        if (pgVector == null) return null;
        return pgVector.toArray();
    }
}