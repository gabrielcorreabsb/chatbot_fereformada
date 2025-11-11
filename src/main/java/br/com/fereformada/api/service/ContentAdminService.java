package br.com.fereformada.api.service;

import br.com.fereformada.api.dto.*;
import br.com.fereformada.api.model.*;
import br.com.fereformada.api.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ContentAdminService {

    private final ObjectMapper objectMapper;
    private final ContentChunkRepository contentChunkRepository;
    private final WorkRepository workRepository;
    private final AuthorRepository authorRepository;
    private final TopicRepository topicRepository;
    private final GeminiApiClient geminiApiClient;
    private final ImportTaskRepository importTaskRepository;
    private final AsyncImportService asyncImportService;

    private static final int EMBEDDING_BATCH_SIZE = 50;

    private static final Logger logger = LoggerFactory.getLogger(ContentAdminService.class);

    public ContentAdminService(ContentChunkRepository contentChunkRepository,
                               WorkRepository workRepository,
                               AuthorRepository authorRepository,
                               TopicRepository topicRepository,
                               GeminiApiClient geminiApiClient, ObjectMapper objectMapper, ImportTaskRepository importTaskRepository, AsyncImportService asyncImportService) {

        this.contentChunkRepository = contentChunkRepository;
        this.workRepository = workRepository;
        this.authorRepository = authorRepository;
        this.topicRepository = topicRepository;
        this.geminiApiClient = geminiApiClient;
        this.objectMapper = objectMapper;
        this.importTaskRepository = importTaskRepository;
        this.asyncImportService = asyncImportService;
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
        work.setBoostPriority(dto.boostPriority());
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
        work.setBoostPriority(dto.boostPriority());
        Work updatedWork = workRepository.save(work);
        return new WorkResponseDTO(updatedWork);
    }

    @Transactional
    public void deleteWork(Long workId) {
        if (!workRepository.existsById(workId)) {
            throw new EntityNotFoundException("Obra não encontrada: " + workId);
        }

        // 1. Busca apenas os IDs dos chunks (SEM carregar o content_vector)
        List<Long> chunkIds = contentChunkRepository.findChunkIdsByWorkId(workId);

        if (!chunkIds.isEmpty()) {
            // 2. Deleta as relações ManyToMany na tabela chunk_topics
            contentChunkRepository.deleteChunkTopicsByChunkIds(chunkIds);

            // 3. Deleta os chunks usando SQL nativo direto
            contentChunkRepository.deleteChunksByIds(chunkIds);
        }

        // 4. Finalmente, deleta a obra
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
    public Page<ChunkResponseDTO> findChunksByWork(Long workId, Pageable pageable, String search) { // <-- 1. NOVO PARÂMETRO

        Page<ChunkProjection> projectionPage;

        // --- A NOVA LÓGICA DE DECISÃO ---
        if (search != null && !search.isBlank()) {
            // Se tem busca, chama o novo método de busca
            projectionPage = contentChunkRepository.searchByWorkIdProjection(workId, search, pageable);
        } else {
            // Se não tem busca, chama o método que você já usa
            projectionPage = contentChunkRepository.findByWorkIdProjection(workId, pageable);
        }
        // --- FIM DA NOVA LÓGICA ---


        // O RESTO DO SEU MÉTODO (PASSOS 2-6) PERMANECE IDÊNTICO:
        // Nós apenas mudamos *qual* 'projectionPage' ele vai usar.

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
                    Set<TopicDTO> topics = topicsByChunkId.getOrDefault(projection.id(), new HashSet<>());
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
        // ContentChunk chunk = contentChunkRepository.findById(chunkId).orElseThrow(/*...*/); // <-- O BUG ESTÁ AQUI
        // return new ChunkResponseDTO(chunk);

        // --- A NOVA LÓGICA ---

        // 1. Busca o Chunk (sem vetor) usando a projeção
        ChunkProjection projection = contentChunkRepository.findProjectionById(chunkId) // <-- Nome novo
                .orElseThrow(() -> new EntityNotFoundException("Chunk não encontrado: " + chunkId));

        // 2. Busca os Tópicos para esse chunk
        List<ChunkTopicProjection> topicRelations = contentChunkRepository.findTopicsForChunkIds(List.of(chunkId));

        // 3. Mapeia os tópicos para DTOs
        Set<TopicDTO> topics = topicRelations.stream()
                .map(proj -> new TopicDTO(proj.topicId(), proj.topicName(), proj.topicDescription()))
                .collect(Collectors.toSet());

        // 4. "Costura" os dados no DTO de resposta final
        return new ChunkResponseDTO(projection, topics);
    }

    @Transactional
    public void deleteChunk(Long chunkId) {
        // ✅ Verifica existência sem carregar o vetor
        if (!contentChunkRepository.existsByIdSafe(chunkId)) {
            throw new EntityNotFoundException("Chunk não encontrado: " + chunkId);
        }

        // 1. Deleta as relações ManyToMany
        contentChunkRepository.deleteChunkTopicsByChunkId(chunkId);

        // 2. Deleta o chunk
        contentChunkRepository.deleteChunkById(chunkId);
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

    @Transactional
    public String bulkImportChunksFromJson(MultipartFile file) throws Exception {
        if (!"application/json".equals(file.getContentType())) {
            throw new IllegalArgumentException("Formato de arquivo inválido. Apenas .json é permitido.");
        }

        List<ChunkImportDTO> dtoList;
        try (InputStream inputStream = file.getInputStream()) {
            dtoList = objectMapper.readValue(inputStream, new TypeReference<List<ChunkImportDTO>>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("Erro ao processar o JSON: " + e.getMessage());
        }

        if (dtoList == null || dtoList.isEmpty()) {
            throw new IllegalArgumentException("O arquivo JSON está vazio ou mal formatado.");
        }

        int processedCount = 0;
        for (ChunkImportDTO dto : dtoList) {
            // 1. Encontrar a Obra (Work) pelo acrônimo
            Work work = workRepository.findByAcronym(dto.workAcronym())
                    .orElseThrow(() -> new IllegalArgumentException("Acrônimo de Obra não encontrado: " + dto.workAcronym()));

            // 2. Encontrar os Tópicos (Topics)
            Set<Topic> topics = topicRepository.findByNameIn(dto.topics());

            // 3. Montar o texto para vetorização
            String textToEmbed = buildTextToEmbed(dto);

            // 4. Gerar o embedding (VETORIZAÇÃO NA INGESTÃO!)
            PGvector pgVector = geminiApiClient.generateEmbedding(textToEmbed);
            float[] vector = (pgVector != null) ? pgVector.toArray() : null;

            // 5. Criar a entidade ContentChunk
            ContentChunk chunk = new ContentChunk();
            chunk.setWork(work);
            chunk.setChapterTitle(dto.chapterTitle());
            chunk.setChapterNumber(dto.chapterNumber());
            chunk.setSectionTitle(dto.sectionTitle());
            chunk.setSectionNumber(dto.sectionNumber());
            chunk.setSubsectionTitle(dto.subsectionTitle());
            chunk.setSubSubsectionTitle(dto.subSubsectionTitle());
            chunk.setQuestion(dto.question());
            chunk.setContent(dto.content());
            chunk.setTopics(topics);
            chunk.setContentVector(vector); // Vetor é salvo aqui!

            // 6. Salvar
            contentChunkRepository.save(chunk);
            processedCount++;
        }

        return "Importação concluída com sucesso. " + processedCount + " chunks processados e salvos.";
    }

    /**
     * Helper para construir o texto que será enviado ao Gemini para vetorização.
     */
    private String buildTextToEmbed(ChunkImportDTO dto) {
        StringBuilder sb = new StringBuilder();
        if (dto.question() != null && !dto.question().isBlank()) {
            sb.append(dto.question()).append("\n").append(dto.content());
        } else {
            if (dto.chapterTitle() != null) sb.append(dto.chapterTitle()).append(". ");
            if (dto.sectionTitle() != null) sb.append(dto.sectionTitle()).append(". ");
            if (dto.subsectionTitle() != null) sb.append(dto.subsectionTitle()).append(". ");
            sb.append(dto.content());
        }
        return sb.toString();
    }

    @Transactional
    public ImportTaskDTO createImportTask(List<ChunkImportDTO> dtoList) {
        if (dtoList == null || dtoList.isEmpty()) {
            throw new IllegalArgumentException("A lista de chunks está vazia.");
        }

        // 1. Criar a "Tarefa" (o recibo)
        ImportTask task = new ImportTask();
        task.setTotalItems(dtoList.size());
        task.setCurrentLog("Tarefa enfileirada, aguardando início...");
        ImportTask savedTask = importTaskRepository.save(task);

        // 2. REMOVEMOS a chamada ao 'asyncImportService.processImport' daqui.
        // A transação fará o commit assim que este método retornar.

        // 3. Retornar o DTO da tarefa
        return new ImportTaskDTO(savedTask);
    }

    // 5. ADICIONE O MÉTODO "GET STATUS"
    @Transactional(readOnly = true)
    public ImportTaskDTO getTaskStatus(Long taskId) {
        ImportTask task = importTaskRepository.findById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Tarefa não encontrada: " + taskId));
        return new ImportTaskDTO(task);
    }

    @Transactional
    public void bulkDeleteChunks(List<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }

        // 1. Deleta as relações ManyToMany (Nós já temos este método!)
        contentChunkRepository.deleteChunkTopicsByChunkIds(chunkIds);

        // 2. Deleta os chunks (Nós também já temos este método!)
        contentChunkRepository.deleteChunksByIds(chunkIds);
    }

    /**
     * Adiciona tópicos em massa a um grupo de chunks.
     */
    @Transactional
    public void bulkAddTopics(BulkTopicDTO dto) {
        if (dto == null || dto.chunkIds() == null || dto.topicIds() == null ||
                dto.chunkIds().isEmpty() || dto.topicIds().isEmpty()) {
            logger.warn("Tentativa de bulkAddTopics com DTO inválido.");
            return;
        }

        logger.info("Executando bulk add topics: {} chunks, {} tópicos.", dto.chunkIds().size(), dto.topicIds().size());

        // --- A CORREÇÃO ESTÁ AQUI ---
        // Convertemos as Listas em Arrays nativos Java.
        // O driver JDBC saberá como converter Long[] para um array SQL (bigint[]).
        Long[] chunkIdsArray = dto.chunkIds().toArray(new Long[0]);
        Long[] topicIdsArray = dto.topicIds().toArray(new Long[0]);
        // --- FIM DA CORREÇÃO ---

        // 3. Chama o novo método do repositório com os arrays
        contentChunkRepository.bulkAddTopicsToChunks(chunkIdsArray, topicIdsArray);
    }
}

