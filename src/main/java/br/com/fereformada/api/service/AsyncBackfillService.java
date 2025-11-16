package br.com.fereformada.api.service;

import br.com.fereformada.api.model.ImportTask;
import br.com.fereformada.api.model.enums.TaskStatus;
import br.com.fereformada.api.repository.ChunkBackfillProjection;
import br.com.fereformada.api.repository.ContentChunkRepository;
import br.com.fereformada.api.repository.ImportTaskRepository;
import com.pgvector.PGvector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
// 🚀 REMOVA OS IMPORTS TRAN SACIONAIS DAQUI
// import org.springframework.transaction.annotation.Propagation;
// import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AsyncBackfillService {

    private final ImportTaskRepository importTaskRepository;
    private final ContentChunkRepository contentChunkRepository;
    private final GeminiApiClient geminiApiClient;
    private final AsyncBackfillWorker backfillWorker; // 🚀 1. INJETAR O NOVO WORKER

    private static final int EMBEDDING_BATCH_SIZE = 10;
    private static final Logger logger = LoggerFactory.getLogger(AsyncBackfillService.class);

    public AsyncBackfillService(ImportTaskRepository importTaskRepository,
                                ContentChunkRepository contentChunkRepository,
                                GeminiApiClient geminiApiClient,
                                AsyncBackfillWorker backfillWorker) { // 🚀 2. INJETAR AQUI
        this.importTaskRepository = importTaskRepository;
        this.contentChunkRepository = contentChunkRepository;
        this.geminiApiClient = geminiApiClient;
        this.backfillWorker = backfillWorker; // 🚀 3. ATRIBUIR
    }

    /**
     * Este é o método de trabalho assíncrono para o backfill.
     */
    @Async
    public void processBackfill(Long taskId) {
        // 1. Buscar a tarefa
        ImportTask task = importTaskRepository.findById(taskId)
                .orElseThrow(() -> new RuntimeException("Tarefa de backfill não encontrada: " + taskId));

        try {
            // 2. Marcar a tarefa como "Em Processamento"
            task.setStatus(TaskStatus.PROCESSING);
            task.setCurrentLog("Buscando chunks que precisam de backfill...");
            importTaskRepository.save(task);

            // 3. Buscar os chunks que precisam ser corrigidos
            List<ChunkBackfillProjection> chunksToFix = contentChunkRepository.findChunksNeedingQuestionVectorBackfill();
            int totalChunks = chunksToFix.size();

            if (totalChunks == 0) {
                task.setCurrentLog("Nenhum chunk precisava de atualização.");
                task.setStatus(TaskStatus.COMPLETED);
                task.setEndTime(LocalDateTime.now());
                importTaskRepository.save(task);
                return;
            }

            logger.info("[Backfill] Encontrados {} chunks para atualizar.", totalChunks);
            task.setTotalItems(totalChunks); // Atualiza o total
            importTaskRepository.save(task);

            // --- 4. Processamento em Lotes (igual ao seu AsyncImportService) ---
            for (int i = 0; i < totalChunks; i += EMBEDDING_BATCH_SIZE) {
                int end = Math.min(i + EMBEDDING_BATCH_SIZE, totalChunks);

                // 4.1 Atualizar o Log da Tarefa
                int batchNum = (i / EMBEDDING_BATCH_SIZE) + 1;
                int totalBatches = (int) Math.ceil((double) totalChunks / EMBEDDING_BATCH_SIZE);
                String logMessage = "Processando lote " + batchNum + " de " + totalBatches + " (chunks " + i + "-" + end + ")";

                logger.info("[Backfill] " + logMessage);
                task.setCurrentLog(logMessage);
                importTaskRepository.save(task); // Salva o progresso

                // 4.2 Preparar o lote
                List<ChunkBackfillProjection> chunkBatch = chunksToFix.subList(i, end);

                List<String> textBatch = chunkBatch.stream()
                        .map(ChunkBackfillProjection::getQuestion)
                        .collect(Collectors.toList());

                // 4.3 Chamar a API em lote
                List<PGvector> vectorBatch = geminiApiClient.generateEmbeddingsInBatch(textBatch);

                if (vectorBatch.size() != chunkBatch.size()) {
                    throw new RuntimeException("Falha no backfill: contagens de lote não correspondem.");
                }

                // 4.4 🚀 MODIFICADO: Chamar o WORKER externo
                try {
                    // Chama o método no BEAN injetado
                    backfillWorker.updateBatchInTransaction(chunkBatch, vectorBatch); // 🚀 4. CHAMAR O WORKER
                } catch (Exception e) {
                    logger.error("Falha ao salvar o lote {} (chunks {}-{}): {}", batchNum, i, end, e.getMessage(), e); // 🚀 Adicionado ", e" para stack trace
                    // Vamos continuar
                }

                // 4.6 Atualizar progresso
                task.setProcessedItems(end);
                importTaskRepository.save(task);
            }

            // --- 5. Concluir ---
            task.setStatus(TaskStatus.COMPLETED);
            task.setCurrentLog("Backfill concluído com sucesso.");
            task.setEndTime(LocalDateTime.now());

        } catch (Exception e) {
            // --- 6. Tratar Falha ---
            logger.error("Falha na tarefa de backfill " + taskId, e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage(e.getMessage());
            task.setEndTime(LocalDateTime.now());
        } finally {
            // Garantir que o estado final (COMPLETED ou FAILED) seja salvo
            importTaskRepository.save(task);
        }
    }
}