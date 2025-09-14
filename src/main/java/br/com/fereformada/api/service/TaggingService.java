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

        // Regra para "Sagradas Escrituras" (Capítulo 1 da CFW, Perguntas 2-4 do CM)
        findTopic("Sagradas Escrituras", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("escrituras", "palavra de deus", "cânon", "testamento", "inspiração", "regra de fé"))
        );

        // Regra para "Deus e a Santíssima Trindade" (Capítulo 2 da CFW)
        findTopic("Deus e a Santíssima Trindade", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("trindade", "um só deus", "pai, o filho e o espírito santo", "três pessoas", "atributos de deus"))
        );

        // Regra para "Decretos de Deus" (Capítulo 3 da CFW)
        findTopic("Decretos de Deus", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("decreto de deus", "predestinação", "eleição", "preordenou", "escolheu em cristo"))
        );

        // Regra para "Criação" (Capítulo 4 da CFW)
        findTopic("Criação", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("criação", "criar o mundo", "criou o homem", "imagem de deus"))
        );

        // Regra para "Providência" (Capítulo 5 da CFW)
        findTopic("Providência", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("providência", "governa todas as criaturas", "sustenta", "dirige"))
        );

        // Regra para "A Queda e o Pecado" (Capítulo 6 da CFW)
        findTopic("A Queda e o Pecado", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("queda do homem", "pecado", "corrupção", "transgressão", "culpa", "natureza corrompida"))
        );

        // Regra para "Pacto de Deus" (Capítulo 7 da CFW)
        findTopic("Pacto de Deus", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("pacto de deus", "pacto de obras", "pacto da graça"))
        );

        // Regra para "Cristo, o Mediador" (Capítulo 8 da CFW)
        findTopic("Cristo, o Mediador", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("cristo, o mediador", "jesus", "filho de deus", "redenção", "sacrifício", "duas naturezas"))
        );

        // Regra para "Livre-Arbítrio" (Capítulo 9 da CFW)
        findTopic("Livre-Arbítrio", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("livre-arbítrio", "vontade do homem"))
        );

        // Regra para "Vocação Eficaz" (Capítulo 10 da CFW)
        findTopic("Vocação Eficaz", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("vocação eficaz", "chamar eficazmente", "chamados"))
        );

        // Regra para "Justificação pela Fé" (Capítulo 11 da CFW)
        findTopic("Justificação pela Fé", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("justificação", "justifica", "fé salvadora", "imputando", "obediência de cristo"))
        );

        // Regra para "A Lei de Deus" (Capítulo 19 da CFW)
        findTopic("A Lei de Deus", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("lei de deus", "mandamentos", "dez mandamentos", "lei moral"))
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