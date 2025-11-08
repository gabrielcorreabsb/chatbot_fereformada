package br.com.fereformada.api.repository;

import br.com.fereformada.api.model.Work;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WorkRepository extends JpaRepository<Work, Long> {

    /**
     * Busca uma obra pelo seu título exato.
     * Pode ser útil para a API ou para evitar duplicidade na carga de dados.
     * @param title O título da obra a ser buscada.
     * @return um Optional contendo a Work se encontrada, ou um Optional vazio caso contrário.
     */
    Optional<Work> findByTitle(String title);
    Optional<Work> findByAcronym(String acronym);

}