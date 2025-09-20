package br.com.fereformada.api.repository;

import br.com.fereformada.api.model.ContentChunk;
import br.com.fereformada.api.model.Topic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

@Repository
public interface ContentChunkRepository extends JpaRepository<ContentChunk, Long> {

    /**
     * Esta é a consulta principal para a nossa IA.
     * Ela busca todos os fragmentos de conteúdo que atendem a dois critérios:
     * 1. Estão associados a um tópico com um nome específico.
     * 2. Pertencem a uma obra de um autor com um nome específico.
     * O Spring Data JPA interpreta o nome do método e constrói a consulta complexa com os JOINS necessários.
     *
     * Exemplo de uso: buscar o que 'João Calvino' disse sobre 'Soteriologia'.
     *
     * @param topicName O nome do tópico a ser buscado (ex: "Soteriologia").
     * @param authorName O nome do autor da obra (ex: "João Calvino").
     * @return Uma lista de ContentChunks que correspondem aos critérios.
     */
    List<ContentChunk> findByTopics_NameAndWork_Author_Name(String topicName, String authorName);
    List<ContentChunk> findFirst5ByTopicsIn(Set<Topic> topics);
    @Query("SELECT c FROM ContentChunk c JOIN c.topics t WHERE t IN :topics AND LOWER(c.work.title) LIKE LOWER(CONCAT('%', :workTitle, '%'))")
    List<ContentChunk> findRelevantChunks(@Param("topics") Set<Topic> topics, @Param("workTitle") String workTitle);
}

