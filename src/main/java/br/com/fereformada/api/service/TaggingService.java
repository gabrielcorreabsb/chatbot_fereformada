package br.com.fereformada.api.service;

import br.com.fereformada.api.model.Topic;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class TaggingService {

    private final Map<Topic, List<String>> taggingRules = new HashMap<>();

    /**
     * Inicializa o "dicionário" de regras de etiquetagem.
     * Este é o centro da curadoria: mapeamos Tópicos a palavras-chave.
     */
    public void initializeRules(List<Topic> allTopics) {
        taggingRules.clear();

        findTopic("Sagradas Escrituras", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("escrituras", "palavra de deus", "cânon", "testamento"))
        );
        findTopic("Deus e a Santíssima Trindade", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("deus e da santíssima trindade", "um só deus", "três pessoas"))
        );
        findTopic("Decretos de Deus", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("decretos de deus"))
        );
        findTopic("Criação", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("da criação"))
        );
        findTopic("Providência", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("da providência"))
        );
        findTopic("A Queda e o Pecado", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("queda do homem", "do pecado", "corrupção de nossa natureza"))
        );
        findTopic("Pacto de Deus", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("pacto de deus", "pacto de obras", "pacto da graça"))
        );
        findTopic("Cristo, o Mediador", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("cristo, o mediador", "operação secreta do espirito"))
        );
        findTopic("Fé", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("da fé", "fé salvadora"))
        );
        findTopic("Regeneração e Arrependimento", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("regenerados mediante a fé", "arrependimento", "penitência"))
        );
        findTopic("Vida Cristã", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("vida do homem cristão", "renúncia pessoal", "tomar a cruz", "meditação da vida futura", "uso da presente vida"))
        );
        findTopic("Justificação pela Fé", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("justificação pela fé", "méritos das obras", "promessas da lei e do evangelho"))
        );
        findTopic("Oração", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("da oração", "oração do senhor"))
        );
        findTopic("Eleição e Predestinação", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("eleição", "predestinação", "reprovação"))
        );
        findTopic("Ressurreição Final", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("ressurreição final", "juízo final", "estado do homem depois da morte"))
        );
        findTopic("A Igreja", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("da igreja", "comunhão dos santos", "censuras eclesiásticas", "sínodos e concílios"))
        );
        findTopic("Sacramentos", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("sacramentos", "batismo", "ceia do senhor", "indulgências", "purgatório"))
        );
        findTopic("Liberdade Cristã", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("liberdade cristã", "poder eclesiástico", "administração política", "magistrado civil"))
        );
        findTopic("Livre-Arbítrio", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("do livre-arbítrio"))
        );
        findTopic("Vocação Eficaz", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("da vocação eficaz", "adoção"))
        );
        findTopic("A Lei de Deus", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("da lei", "explanação do decálogo"))
        );
    }

    /**
     * Aplica as regras de etiquetagem a um ou mais textos.
     * @param texts Os textos a serem analisados (ex: pergunta, resposta).
     * @return Um Set com os Tópicos correspondentes.
     */
    public Set<Topic> getTagsFor(String... texts) {
        Set<Topic> foundTopics = new HashSet<>();
        String combinedText = String.join(" ", texts).toLowerCase();

        if (combinedText.isBlank()) {
            return foundTopics;
        }

        for (Map.Entry<Topic, List<String>> rule : taggingRules.entrySet()) {
            for (String keyword : rule.getValue()) {
                if (combinedText.contains(keyword)) {
                    foundTopics.add(rule.getKey());
                    break; // Vai para a próxima regra assim que encontra uma palavra-chave
                }
            }
        }
        return foundTopics;
    }

    private Optional<Topic> findTopic(String name, List<Topic> topics) {
        return topics.stream().filter(t -> t.getName().equals(name)).findFirst();
    }
}