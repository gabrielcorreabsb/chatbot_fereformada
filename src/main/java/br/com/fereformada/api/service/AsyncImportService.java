package br.com.fereformada.api.service;

import br.com.fereformada.api.dto.ChunkImportDTO;
import br.com.fereformada.api.model.ContentChunk;
import br.com.fereformada.api.model.ImportTask;
import br.com.fereformada.api.model.Topic;
import br.com.fereformada.api.model.Work;
import br.com.fereformada.api.model.enums.TaskStatus;
import br.com.fereformada.api.repository.ContentChunkRepository;
import br.com.fereformada.api.repository.ImportTaskRepository;
import br.com.fereformada.api.repository.TopicRepository;
import br.com.fereformada.api.repository.WorkRepository;
import com.pgvector.PGvector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class AsyncImportService {

    private final ImportTaskRepository importTaskRepository;
    private final WorkRepository workRepository;
    private final TopicRepository topicRepository;
    private final ContentChunkRepository contentChunkRepository;
    private final GeminiApiClient geminiApiClient;

    private static final int EMBEDDING_BATCH_SIZE = 50;
    private static final Logger logger = LoggerFactory.getLogger(AsyncImportService.class);

    public AsyncImportService(ImportTaskRepository importTaskRepository,
                              WorkRepository workRepository,
                              TopicRepository topicRepository,
                              ContentChunkRepository contentChunkRepository,
                              GeminiApiClient geminiApiClient) {
        this.importTaskRepository = importTaskRepository;
        this.workRepository = workRepository;
        this.topicRepository = topicRepository;
        this.contentChunkRepository = contentChunkRepository;
        this.geminiApiClient = geminiApiClient;
    }

    /**
     * Este é o método de trabalho assíncrono.
     * O Spring o executará em uma thread separada.
     */
    @Async
    @Transactional
    public void processImport(Long taskId, List<ChunkImportDTO> dtoList) {
        // 1. Buscar a tarefa (ou falhar)
        ImportTask task = importTaskRepository.findById(taskId)
                .orElseThrow(() -> new RuntimeException("Tarefa não encontrada: " + taskId));

        try {
            // 2. Marcar a tarefa como "Em Processamento"
            task.setStatus(TaskStatus.PROCESSING);
            task.setCurrentLog("Iniciando pré-processamento...");
            importTaskRepository.save(task);

            // --- Lógica copiada do ContentAdminService ---
            List<String> textsToEmbed = new ArrayList<>();
            List<ContentChunk> chunksToSave = new ArrayList<>();

            for (ChunkImportDTO dto : dtoList) {
                Work work = workRepository.findByAcronym(dto.workAcronym())
                        .orElseThrow(() -> new IllegalArgumentException("Acrônimo não encontrado: " + dto.workAcronym()));
                Set<Topic> topics = (dto.topics() != null && !dto.topics().isEmpty()) ?
                        topicRepository.findByNameIn(dto.topics()) : new HashSet<>();

                textsToEmbed.add(buildTextToEmbed(dto));
                chunksToSave.add(createChunkEntity(dto, work, topics));
            }

            // --- ETAPA 2: Vetorização em Lotes (com log de progresso) ---
            int totalChunks = chunksToSave.size();
            for (int i = 0; i < totalChunks; i += EMBEDDING_BATCH_SIZE) {
                int end = Math.min(i + EMBEDDING_BATCH_SIZE, totalChunks);

                // 2.1 Atualizar o Log da Tarefa
                int batchNum = (i / EMBEDDING_BATCH_SIZE) + 1;
                int totalBatches = (int) Math.ceil((double) totalChunks / EMBEDDING_BATCH_SIZE);
                String logMessage = "Processando lote " + batchNum + " de " + totalBatches + "...";

                logger.info(logMessage);
                task.setCurrentLog(logMessage);
                importTaskRepository.save(task); // Salva o progresso

                // 2.2 Processar o lote
                List<String> textBatch = textsToEmbed.subList(i, end);
                List<ContentChunk> chunkBatch = chunksToSave.subList(i, end);

                List<PGvector> vectorBatch = geminiApiClient.generateEmbeddingsInBatch(textBatch);

                if (vectorBatch.size() != chunkBatch.size()) {
                    throw new RuntimeException("Falha na vetorização: contagens de lote não correspondem.");
                }

                // 2.3 "Costurar" vetores e atualizar progresso
                for (int j = 0; j < chunkBatch.size(); j++) {
                    chunkBatch.get(j).setContentVector(vectorBatch.get(j) != null ? vectorBatch.get(j).toArray() : null);
                }

                task.setProcessedItems(end); // Atualiza o total processado
                importTaskRepository.save(task);
            }

            // --- ETAPA 3: Salvar ---
            task.setCurrentLog("Salvando " + totalChunks + " chunks no banco de dados...");
            importTaskRepository.save(task);

            contentChunkRepository.saveAll(chunksToSave);

            // --- ETAPA 4: Concluir ---
            task.setStatus(TaskStatus.COMPLETED);
            task.setCurrentLog("Importação concluída com sucesso.");
            task.setEndTime(LocalDateTime.now());

        } catch (Exception e) {
            // --- ETAPA DE FALHA ---
            logger.error("Falha na tarefa de importação " + taskId, e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage(e.getMessage());
            task.setEndTime(LocalDateTime.now());
        } finally {
            // Garantir que o estado final (COMPLETED ou FAILED) seja salvo
            importTaskRepository.save(task);
        }
    }

    // --- Métodos Helper (Copiados do ContentAdminService) ---

    private ContentChunk createChunkEntity(ChunkImportDTO dto, Work work, Set<Topic> topics) {
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
        return chunk;
    }

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
}