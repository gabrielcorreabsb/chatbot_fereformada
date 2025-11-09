package br.com.fereformada.api.dto;

import br.com.fereformada.api.model.ImportTask;
import br.com.fereformada.api.model.enums.TaskStatus;
import java.time.LocalDateTime;

public record ImportTaskDTO(
        Long id,
        TaskStatus status,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Integer totalItems,
        Integer processedItems,
        String currentLog,
        String errorMessage
) {
    // Construtor de conveniência para mapear da Entidade
    public ImportTaskDTO(ImportTask task) {
        this(
                task.getId(),
                task.getStatus(),
                task.getStartTime(),
                task.getEndTime(),
                task.getTotalItems(),
                task.getProcessedItems(),
                task.getCurrentLog(),
                task.getErrorMessage()
        );
    }
}