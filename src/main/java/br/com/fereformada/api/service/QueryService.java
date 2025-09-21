package br.com.fereformada.api.service;

import br.com.fereformada.api.dto.QueryResponse;
import br.com.fereformada.api.model.ContentChunk;
import br.com.fereformada.api.model.Topic;
import br.com.fereformada.api.model.Work;
import br.com.fereformada.api.repository.ContentChunkRepository;
import br.com.fereformada.api.repository.TopicRepository;
import br.com.fereformada.api.repository.WorkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class QueryService {

    private static final Logger logger = LoggerFactory.getLogger(QueryService.class);
    private final ContentChunkRepository contentChunkRepository;
    private final TopicRepository topicRepository;
    private final WorkRepository workRepository; // <-- NOVA DEPENDÊNCIA
    private final GeminiApiClient geminiApiClient;

    public QueryService(ContentChunkRepository contentChunkRepository,
                        TopicRepository topicRepository,
                        WorkRepository workRepository, // <-- ADICIONAR AO CONSTRUTOR
                        GeminiApiClient geminiApiClient) {
        this.contentChunkRepository = contentChunkRepository;
        this.topicRepository = topicRepository;
        this.workRepository = workRepository; // <-- ADICIONAR ATRIBUIÇÃO
        this.geminiApiClient = geminiApiClient;
    }

    public QueryResponse query(String userQuestion) {
        logger.info("Nova pergunta recebida: '{}'", userQuestion);

        // --- ETAPA 1: BUSCA HÍBRIDA ---
        Set<ContentChunk> combinedResults = new LinkedHashSet<>(); // Usa LinkedHashSet para manter a ordem e evitar duplicatas

        // 1a: Busca por Tópicos (a que já tínhamos)
        Set<Topic> topics = findTopicsUsingAi(userQuestion);
        if (!topics.isEmpty()) {
            logger.info("Tópicos identificados pela IA: {}", topics.stream().map(Topic::getName).collect(Collectors.joining(", ")));
            List<Work> allWorks = workRepository.findAll();
            for (Work work : allWorks) {
                List<ContentChunk> chunksFromWork = contentChunkRepository.findTopByTopicsAndWorkTitle(
                        topics, work.getTitle(), PageRequest.of(0, 2));
                combinedResults.addAll(chunksFromWork);
            }
        }

        // 1b: Busca por Palavra-Chave Direta
        String cleanedQuestion = userQuestion.replaceAll("[^a-zA-Z0-9áéíóúâêôãõç\\s]", "").toLowerCase();
        // Pega as palavras mais significativas (evita "o", "a", "de")
        String[] keywords = Arrays.stream(cleanedQuestion.split("\\s+"))
                .filter(word -> word.length() > 3)
                .toArray(String[]::new);

        for (String keyword : keywords) {
            logger.info("Buscando por palavra-chave direta: '{}'", keyword);
            combinedResults.addAll(contentChunkRepository.findByContentContainingIgnoreCase(keyword, PageRequest.of(0, 2)));
        }

        if (combinedResults.isEmpty()) {
            return new QueryResponse("Não consegui localizar um contexto relevante para responder.", Collections.emptyList());
        }

        // Limita o número de chunks para não sobrecarregar a IA
        List<ContentChunk> limitedChunks = combinedResults.stream().limit(7).toList();

        // --- ETAPA 2: GERAR RESPOSTA (com o prompt aprimorado) ---
        String prompt = buildPrompt(userQuestion, limitedChunks);
        logger.debug("Prompt enviado para a IA:\n{}", prompt);
        String aiAnswer = geminiApiClient.generateContent(prompt);
        List<String> sources = limitedChunks.stream().map(this::formatChunkSource).toList();

        return new QueryResponse(aiAnswer, sources);
    }

    private Set<Topic> findTopicsUsingAi(String userQuestion) {
        // 1. Busca todos os tópicos disponíveis no seu banco de dados.
        List<Topic> allTopics = topicRepository.findAll();
        String availableTopics = allTopics.stream()
                .map(Topic::getName)
                .collect(Collectors.joining(", "));

        // 2. Monta um prompt específico para a tarefa de classificação.
        String classificationPrompt = String.format("""
                Você é um classificador de texto especialista em teologia reformada.
                Sua tarefa é analisar a pergunta do usuário e identificar qual dos seguintes tópicos teológicos é o mais relevante.
                Responda APENAS com o nome exato de um ou mais tópicos da lista, separados por vírgula. Não adicione nenhuma outra palavra ou explicação.
                
                Lista de Tópicos Disponíveis:
                [%s]
                
                Pergunta do Usuário:
                "%s"
                
                Tópicos Relevantes:
                """, availableTopics, userQuestion);

        // 3. Chama a IA.
        String response = geminiApiClient.generateContent(classificationPrompt).trim();
        logger.info("Resposta da IA para classificação de tópicos: '{}'", response);

        // 4. Processa a resposta da IA para encontrar os objetos Topic correspondentes.
        if (response.isBlank() || response.toLowerCase().contains("não sei")) {
            return Collections.emptySet();
        }

        Set<String> topicNamesFromAi = Arrays.stream(response.split(","))
                .map(String::trim)
                .collect(Collectors.toSet());

        return allTopics.stream()
                .filter(topic -> topicNamesFromAi.contains(topic.getName()))
                .collect(Collectors.toSet());
    }

    private String extractWorkFilter(String question) {
        String lowerCaseQuestion = question.toLowerCase();
        if (lowerCaseQuestion.contains("confissão")) return "Confissão de Fé";
        if (lowerCaseQuestion.contains("institutas")) return "Institutas";
        if (lowerCaseQuestion.contains("catecismo maior")) return "Catecismo Maior";
        if (lowerCaseQuestion.contains("breve catecismo")) return "Breve Catecismo";
        return ""; // Se não encontrar, a busca inicial será em todas as obras
    }

    private String buildPrompt(String question, List<ContentChunk> chunks) {
        StringBuilder context = new StringBuilder();
        context.append("Contexto Fornecido:\n");
        for (ContentChunk chunk : chunks) {
            context.append("- Fonte: ").append(formatChunkSource(chunk));
            if (chunk.getQuestion() != null) context.append("\n  Pergunta da Fonte: ").append(chunk.getQuestion());
            context.append("\n  Texto da Fonte: ").append(chunk.getContent()).append("\n\n");
        }

        // **** PROMPT NOVO E MAIS INTELIGENTE ****
        return String.format("""
            Você é um assistente teológico especialista na Fé Reformada, com um tom professoral, claro e didático.
            Sua principal tarefa é responder à pergunta do usuário baseando-se PRIMARIAMENTE no "Contexto Fornecido".

            Siga estas regras:
            1. SEMPRE priorize a informação encontrada no "Contexto Fornecido". Cite as fontes.
            2. Se o contexto não contiver a resposta para uma pergunta factual simples (como "Qual o primeiro mandamento?" ou "Quem foi o autor das Institutas?"), você PODE usar seu conhecimento geral para responder.
            3. Ao usar seu conhecimento geral, deixe claro que a informação não veio das fontes catalogadas. Por exemplo, comece com "Embora não encontrado diretamente nas fontes fornecidas, o primeiro mandamento é...".
            4. Sempre finalize com um parágrafo de resumo conciso.

            %s
            Pergunta do Usuário:
            %s
            """, context.toString(), question);
    }


    // Método de formatação de fonte para consistência
    private String formatChunkSource(ContentChunk chunk) {
        // Se a obra for um catecismo, use o número da pergunta
        if ("CATECISMO".equals(chunk.getWork().getType())) {
            return String.format("%s - Pergunta %d", chunk.getWork().getTitle(), chunk.getSectionNumber());
        }
        // Para outros tipos de obra, use capítulo e seção
        else {
            return String.format("%s - Cap. %d, Seção %d", chunk.getWork().getTitle(), chunk.getChapterNumber(), chunk.getSectionNumber());
        }
    }

}