package br.com.fereformada.api.repository;

import br.com.fereformada.api.model.ImportTask;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportTaskRepository extends JpaRepository<ImportTask, Long> {
    // Spring Data JPA cuida de tudo
}