package br.com.fereformada.api.service;

import br.com.fereformada.api.model.ImportTask;
import br.com.fereformada.api.model.enums.TaskStatus;
import br.com.fereformada.api.repository.ContentChunkRepository;
import br.com.fereformada.api.repository.ImportTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskCreationService {

    private final ContentChunkRepository contentChunkRepository;
    private final ImportTaskRepository importTaskRepository;

    public TaskCreationService(ContentChunkRepository contentChunkRepository, ImportTaskRepository importTaskRepository) {
        this.contentChunkRepository = contentChunkRepository;
        this.importTaskRepository = importTaskRepository;
    }

    /**
     * Este método executa em sua própria transação e faz o COMMIT
     * assim que termina.
     */
    @Transactional
    public ImportTask createBackfillTask() {
        long totalItems = contentChunkRepository.countChunksNeedingQuestionVectorBackfill();

        if (totalItems == 0) {
            // Retorna um marcador "dummy" para o controller, não salva nada
            ImportTask dummyTask = new ImportTask();
            dummyTask.setTotalItems(0);
            return dummyTask;
        }

        ImportTask task = new ImportTask();
        task.setTotalItems((int) totalItems);
        task.setCurrentLog("Tarefa enfileirada, aguardando início...");
        task.setStatus(TaskStatus.PROCESSING); // Status inicial correto

        // Salva e faz o COMMIT (ao sair do método)
        return importTaskRepository.save(task);
    }
}