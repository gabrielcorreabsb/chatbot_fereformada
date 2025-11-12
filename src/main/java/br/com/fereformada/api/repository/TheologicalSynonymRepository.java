package br.com.fereformada.api.repository;

import br.com.fereformada.api.model.TheologicalSynonym;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TheologicalSynonymRepository extends JpaRepository<TheologicalSynonym, Long> {

    /**
     * Busca todos os sinônimos ordenados pelo termo principal (para o admin).
     */
    List<TheologicalSynonym> findAllByOrderByMainTermAsc();

    /**
     * Verifica se um par exato já existe (ignorando maiúsculas/minúsculas).
     * Usado pelo admin para evitar duplicatas.
     */
    boolean existsByMainTermIgnoreCaseAndSynonymIgnoreCase(String mainTerm, String synonym);

    /**
     * Encontra um sinônimo pelo ID para exclusão.
     */
    @Override
    Optional<TheologicalSynonym> findById(Long id);
}