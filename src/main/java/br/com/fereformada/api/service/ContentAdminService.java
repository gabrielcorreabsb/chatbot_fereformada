package br.com.fereformada.api.service;

import br.com.fereformada.api.dto.*;
import br.com.fereformada.api.model.Author;
import br.com.fereformada.api.model.ContentChunk;
import br.com.fereformada.api.model.Topic;
import br.com.fereformada.api.model.Work;
import br.com.fereformada.api.repository.AuthorRepository;
import br.com.fereformada.api.repository.ContentChunkRepository;
import br.com.fereformada.api.repository.TopicRepository;
import br.com.fereformada.api.repository.WorkRepository;
import com.pgvector.PGvector;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ContentAdminService {

    private final ContentChunkRepository contentChunkRepository;
    private final WorkRepository workRepository;
    private final AuthorRepository authorRepository;
    private final TopicRepository topicRepository;
    private final GeminiApiClient geminiApiClient;

    // Construtor (como você já tem)
    public ContentAdminService(ContentChunkRepository contentChunkRepository,
                               WorkRepository workRepository,
                               AuthorRepository authorRepository,
                               TopicRepository topicRepository,
                               GeminiApiClient geminiApiClient) {
        // ... (atribuições)
        this.contentChunkRepository = contentChunkRepository;
        this.workRepository = workRepository;
        this.authorRepository = authorRepository;
        this.topicRepository = topicRepository;
        this.geminiApiClient = geminiApiClient;
    }

    // --- Métodos de Obras (Works) ---
    // (findAllWorks, createWork, updateWork, deleteWork - MANTENHA TODOS)
    @Transactional(readOnly = true)
    public Page<WorkResponseDTO> findAllWorks(Pageable pageable) {
        return workRepository.findAll(pageable).map(WorkResponseDTO::new);
    }
    @Transactional
    public WorkResponseDTO createWork(WorkDTO dto) {
        Author author = authorRepository.findById(dto.authorId()).orElseThrow(/*...*/);
        Work work = new Work();
        work.setTitle(dto.title());
        work.setAcronym(dto.acronym());
        work.setType(dto.type());
        work.setPublicationYear(dto.publicationYear());
        work.setAuthor(author);
        Work newWork = workRepository.save(work);
        return new WorkResponseDTO(newWork);
    }
    @Transactional
    public WorkResponseDTO updateWork(Long workId, WorkDTO dto) {
        Work work = workRepository.findById(workId).orElseThrow(/*...*/);
        Author author = authorRepository.findById(dto.authorId()).orElseThrow(/*...*/);
        work.setTitle(dto.title());
        work.setAcronym(dto.acronym());
        work.setType(dto.type());
        work.setPublicationYear(dto.publicationYear());
        work.setAuthor(author);
        Work updatedWork = workRepository.save(work);
        return new WorkResponseDTO(updatedWork);
    }
    @Transactional
    public void deleteWork(Long workId) {
        if (!workRepository.existsById(workId)) throw new EntityNotFoundException(/*...*/);
        List<ContentChunk> chunksToDelete = contentChunkRepository.findAllByWorkId(workId);
        for (ContentChunk chunk : chunksToDelete) {
            chunk.getTopics().clear();
        }
        contentChunkRepository.saveAll(chunksToDelete);
        contentChunkRepository.deleteAll(chunksToDelete);
        workRepository.deleteById(workId);
    }

    // --- Métodos de Autores (Authors) ---
    // (findAllAuthors, findAllAuthorsList, createAuthor, updateAuthor, deleteAuthor - MANTENHA TODOS)
    @Transactional(readOnly = true)
    public Page<AuthorDTO> findAllAuthors(Pageable pageable) {
        return authorRepository.findAll(pageable).map(AuthorDTO::new);
    }
    @Transactional(readOnly = true)
    public List<AuthorDTO> findAllAuthorsList() {
        return authorRepository.findAll().stream().map(AuthorDTO::new).collect(Collectors.toList());
    }
    @Transactional
    public AuthorDTO createAuthor(AuthorDTO dto) {
        Author author = new Author();
        author.setName(dto.name());
        author.setBiography(dto.biography());
        author.setBirthDate(dto.birthDate());
        author.setDeathDate(dto.deathDate());
        author.setEra(dto.era());
        Author savedAuthor = authorRepository.save(author);
        return new AuthorDTO(savedAuthor);
    }
    @Transactional
    public AuthorDTO updateAuthor(Long authorId, AuthorDTO dto) {
        Author author = authorRepository.findById(authorId).orElseThrow(/*...*/);
        author.setName(dto.name());
        author.setBiography(dto.biography());
        author.setBirthDate(dto.birthDate());
        author.setDeathDate(dto.deathDate());
        author.setEra(dto.era());
        Author updatedAuthor = authorRepository.save(author);
        return new AuthorDTO(updatedAuthor);
    }
    @Transactional
    public void deleteAuthor(Long authorId) {
        Author author = authorRepository.findById(authorId).orElseThrow(/*...*/);
        if (author.getWorks() != null && !author.getWorks().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Autor está associado a obras.");
        }
        authorRepository.delete(author);
    }

    // --- Métodos de Tópicos (Topics) ---
    // (findAllTopics, findAllTopicsList, createTopic, updateTopic, deleteTopic - MANTENHA TODOS)
    @Transactional(readOnly = true)
    public Page<TopicDTO> findAllTopics(Pageable pageable) {
        return topicRepository.findAll(pageable).map(TopicDTO::new);
    }
    @Transactional(readOnly = true)
    public List<TopicDTO> findAllTopicsList() {
        return topicRepository.findAll().stream().map(TopicDTO::new).collect(Collectors.toList());
    }
    @Transactional
    public TopicDTO createTopic(TopicDTO dto) {
        Topic topic = new Topic();
        dto.toEntity(topic);
        Topic savedTopic = topicRepository.save(topic);
        return new TopicDTO(savedTopic);
    }
    @Transactional
    public TopicDTO updateTopic(Long topicId, TopicDTO dto) {
        Topic topic = topicRepository.findById(topicId).orElseThrow(/*...*/);
        dto.toEntity(topic);
        Topic updatedTopic = topicRepository.save(topic);
        return new TopicDTO(updatedTopic);
    }
    @Transactional
    public void deleteTopic(Long topicId) {
        Topic topic = topicRepository.findById(topicId).orElseThrow(/*...*/);
        if (topic.getContentChunks() != null && !topic.getContentChunks().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tópico está associado a chunks.");
        }
        topicRepository.delete(topic);
    }

    // --- Métodos de Chunks (A Lógica Crítica) ---

    /**
     * ======================================================
     * MÉTODO 'findChunksByWork' CORRIGIDO (Projeção de Três Etapas)
     * ======================================================
     */
    @Transactional(readOnly = true)
    public Page<ChunkResponseDTO> findChunksByWork(Long workId, Pageable pageable) {

        // PASSO 1: Busca a página de projeções (rápido e seguro, sem vetor)
        Page<ChunkProjection> projectionPage = contentChunkRepository.findByWorkIdProjection(workId, pageable);

        // PASSO 2: Pega os IDs dos chunks desta página
        List<Long> chunkIds = projectionPage.getContent().stream()
                .map(ChunkProjection::id)
                .collect(Collectors.toList());

        if (chunkIds.isEmpty()) {
            return Page.empty(pageable);
        }

        // PASSO 3: Faz uma SEGUNDA query para buscar as relações de Tópicos
        List<ChunkTopicProjection> topicRelations = contentChunkRepository.findTopicsForChunkIds(chunkIds);

        // PASSO 4: Agrupa os tópicos por ChunkID
        // (Map<ID_DO_CHUNK, Set<TopicDTO>>)
        Map<Long, Set<TopicDTO>> topicsByChunkId = topicRelations.stream()
                .collect(Collectors.groupingBy(
                        ChunkTopicProjection::chunkId,
                        Collectors.mapping(
                                proj -> new TopicDTO(proj.topicId(), proj.topicName(), proj.topicDescription()),
                                Collectors.toSet()
                        )
                ));

        // PASSO 5: "Costura" os dados
        List<ChunkResponseDTO> responseDTOs = projectionPage.getContent().stream()
                .map(projection -> {
                    // Pega o Set de tópicos para este chunk (ou um Set vazio)
                    Set<TopicDTO> topics = topicsByChunkId.getOrDefault(projection.id(), new HashSet<>());

                    // Constrói o DTO final usando nosso novo construtor
                    return new ChunkResponseDTO(projection, topics);
                })
                .collect(Collectors.toList());

        // PASSO 6: Retorna a página final
        return new PageImpl<>(responseDTOs, pageable, projectionPage.getTotalElements());
    }

    // (createChunk, updateChunk, deleteChunk, findChunkById - MANTENHA-OS)
    // (Eles funcionam pois retornam 'new ChunkResponseDTO(entidade)',
    // e como a entidade FOI SALVA, ela não tem o bug do vetor NULL)
    @Transactional
    public ChunkResponseDTO createChunk(Long workId, ChunkRequestDTO dto) {
        Work work = workRepository.findById(workId).orElseThrow(/*...*/);
        ContentChunk chunk = new ContentChunk();
        dto.toEntity(chunk);
        chunk.setWork(work);
        if (dto.topicIds() != null && !dto.topicIds().isEmpty()) {
            Set<Topic> topics = new HashSet<>(topicRepository.findAllById(dto.topicIds()));
            chunk.setTopics(topics);
        }
        vectorizeChunk(chunk); // GERA O VETOR
        ContentChunk savedChunk = contentChunkRepository.save(chunk);
        return new ChunkResponseDTO(savedChunk);
    }
    @Transactional
    public ChunkResponseDTO updateChunk(Long chunkId, ChunkRequestDTO dto) {
        ContentChunk chunk = contentChunkRepository.findById(chunkId).orElseThrow(/*...*/);
        String oldContent = chunk.getContent();
        dto.toEntity(chunk);
        if (dto.topicIds() != null) {
            Set<Topic> topics = new HashSet<>(topicRepository.findAllById(dto.topicIds()));
            chunk.setTopics(topics);
        }
        if (oldContent == null || !oldContent.equals(dto.content()) || (chunk.getQuestion() != null && !chunk.getQuestion().equals(dto.question()))) {
            vectorizeChunk(chunk);
        }
        ContentChunk updatedChunk = contentChunkRepository.save(chunk);
        return new ChunkResponseDTO(updatedChunk);
    }
    @Transactional(readOnly = true)
    public ChunkResponseDTO findChunkById(Long chunkId) {
        ContentChunk chunk = contentChunkRepository.findById(chunkId).orElseThrow(/*...*/);
        return new ChunkResponseDTO(chunk);
    }
    @Transactional
    public void deleteChunk(Long chunkId) {
        ContentChunk chunk = contentChunkRepository.findById(chunkId).orElseThrow(/*...*/);
        chunk.getTopics().clear();
        contentChunkRepository.save(chunk);
        contentChunkRepository.delete(chunk);
    }

    // --- Lógica Central de Vetorização ---
    // (Permanece a mesma)
    private void vectorizeChunk(ContentChunk chunk) {
        String textToEmbed = (chunk.getQuestion() != null ? chunk.getQuestion() + "\n" : "") +
                (chunk.getContent() != null ? chunk.getContent() : "");
        if (textToEmbed.isBlank()) {
            chunk.setContentVector(null);
            return;
        }
        try {
            PGvector vector = geminiApiClient.generateEmbedding(textToEmbed);
            chunk.setContentVector(vector.toArray());
        } catch (Exception e) {
            System.err.println("Falha ao vetorizar chunk " + chunk.getId() + ": " + e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public WorkResponseDTO findWorkById(Long workId) {
        Work work = workRepository.findById(workId)
                .orElseThrow(() -> new EntityNotFoundException("Obra não encontrada: " + workId));
        return new WorkResponseDTO(work); // Converte para o DTO seguro
    }
}