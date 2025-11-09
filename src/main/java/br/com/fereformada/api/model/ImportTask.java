package br.com.fereformada.api.model;

import br.com.fereformada.api.model.enums.TaskStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "import_tasks")
@Getter
@Setter
public class ImportTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status;

    @Column(nullable = false)
    private LocalDateTime startTime;

    @Column
    private LocalDateTime endTime;

    @Column
    private Integer totalItems;

    @Column
    private Integer processedItems;

    @Column(length = 255) // Um log curto para o frontend
    private String currentLog;

    @Column(columnDefinition = "TEXT") // Espaço para mensagens de erro completas
    private String errorMessage;

    // Construtor padrão
    public ImportTask() {
        this.processedItems = 0;
        this.status = TaskStatus.PENDING;
        this.startTime = LocalDateTime.now();
    }
}