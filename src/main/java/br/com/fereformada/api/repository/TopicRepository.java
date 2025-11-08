package br.com.fereformada.api.repository;

import br.com.fereformada.api.model.Topic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

@Repository
public interface TopicRepository extends JpaRepository<Topic, Long> {

    /**
     * Busca um tópico pelo seu nome exato.
     * Essencial para o processo de carga de dados, para associar fragmentos de conteúdo
     * aos tópicos corretos sem precisar saber o ID de antemão.
     * @param name O nome do tópico (ex: "Soteriologia").
     * @return um Optional contendo o Topic se encontrado, ou um Optional vazio caso contrário.
     */
    Optional<Topic> findByName(String name);
    Set<Topic> findByNameIn(Collection<String> names);
}