package br.com.fereformada.api.controller;

import br.com.fereformada.api.dto.ImportTaskDTO;
import br.com.fereformada.api.model.ImportTask;
import br.com.fereformada.api.model.enums.TaskStatus;
import br.com.fereformada.api.repository.ContentChunkRepository;
import br.com.fereformada.api.repository.ImportTaskRepository;
import br.com.fereformada.api.service.AsyncBackfillService;
import br.com.fereformada.api.service.TaskCreationService; // 🚀 IMPORTAR
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
// import org.springframework.transaction.annotation.Transactional; // 🚀 REMOVER

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/utils")
public class AdminUtilityController {

    private static final Logger logger = LoggerFactory.getLogger(AdminUtilityController.class);

    // 🚀 REMOVIDOS REPOSITÓRIOS DESNECESSÁRIOS
    private final AsyncBackfillService asyncBackfillService;
    private final TaskCreationService taskCreationService; // 🚀 INJETAR NOVO SERVIÇO

    public AdminUtilityController(AsyncBackfillService asyncBackfillService,
                                  TaskCreationService taskCreationService) {
        this.asyncBackfillService = asyncBackfillService;
        this.taskCreationService = taskCreationService;
    }

    /**
     * Endpoint para INICIAR a tarefa assíncrona de backfill.
     * Este método NÃO é transacional.
     */
    @PostMapping("/backfill-question-vectors")
    // @Transactional // 🚀 REMOVIDO
    public ResponseEntity<ImportTaskDTO> backfillQuestionVectors() {
        logger.info("Recebida requisição de backfill de question_vectors...");

        // 1. Chama o serviço que CRIA E COMITA a tarefa.
        ImportTask savedTask = taskCreationService.createBackfillTask();

        // 2. Verifica se a tarefa "dummy" foi retornada (nada a fazer)
        if (savedTask.getTotalItems() == 0) {
            logger.info("Nenhum chunk precisando de backfill. Nada a fazer.");
            ImportTaskDTO dto = new ImportTaskDTO(
                    0L, TaskStatus.COMPLETED, null, null, 0, 0,
                    "Nenhum chunk precisava de atualização.", null
            );
            return ResponseEntity.ok(dto);
        }

        logger.info("Enfileirando tarefa de backfill para {} chunks.", savedTask.getTotalItems());

        // 3. Chama o serviço assíncrono.
        asyncBackfillService.processBackfill(savedTask.getId());

        // 4. Retorna o "recibo" (ImportTaskDTO) imediatamente para o frontend
        logger.info("Tarefa de backfill {} enfileirada. Retornando recibo.", savedTask.getId());
        return ResponseEntity.ok(new ImportTaskDTO(savedTask));
    }
}