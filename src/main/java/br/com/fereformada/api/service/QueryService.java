package br.com.fereformada.api.service;

import br.com.fereformada.api.dto.QueryResponse;
import br.com.fereformada.api.model.ContentChunk;
import br.com.fereformada.api.model.Topic;
import br.com.fereformada.api.model.Work;
import br.com.fereformada.api.repository.ContentChunkRepository;
import br.com.fereformada.api.repository.TopicRepository;
import br.com.fereformada.api.repository.WorkRepository;
import com.pgvector.PGvector;
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
    private final WorkRepository workRepository;
    private final GeminiApiClient geminiApiClient;

    public QueryService(ContentChunkRepository contentChunkRepository,
                        TopicRepository topicRepository,
                        WorkRepository workRepository,
                        GeminiApiClient geminiApiClient) {
        this.contentChunkRepository = contentChunkRepository;
        this.topicRepository = topicRepository;
        this.workRepository = workRepository;
        this.geminiApiClient = geminiApiClient;
    }

    public QueryResponse query(String userQuestion) {
        logger.info("Nova pergunta recebida: '{}'", userQuestion);

        Set<ContentChunk> combinedResults = new LinkedHashSet<>();

        // 1a: BUSCA VETORIAL (PRINCIPAL)
        List<ContentChunk> vectorResults = performVectorSearch(userQuestion);
        if (!vectorResults.isEmpty()) {
            logger.info("🎯 Busca vetorial encontrou {} chunks relevantes", vectorResults.size());
            combinedResults.addAll(vectorResults);
        }

        // 1b: Busca por Tópicos (complementar)
        Set<Topic> topics = findTopicsUsingAi(userQuestion);
        if (!topics.isEmpty()) {
            logger.info("📚 Tópicos identificados pela IA: {}", topics.stream().map(Topic::getName).collect(Collectors.joining(", ")));
            List<Work> allWorks = workRepository.findAll();
            for (Work work : allWorks) {
                List<ContentChunk> chunksFromWork = contentChunkRepository.findTopByTopicsAndWorkTitle(
                        topics, work.getTitle(), PageRequest.of(0, 2));
                combinedResults.addAll(chunksFromWork);
            }
        }

        // 1c: Busca por Palavra-Chave (fallback)
        if (combinedResults.size() < 3) {
            List<ContentChunk> keywordResults = performKeywordSearch(userQuestion);
            combinedResults.addAll(keywordResults);
            logger.info("🔍 Busca por palavra-chave adicionou {} chunks", keywordResults.size());
        }

        if (combinedResults.isEmpty()) {
            return new QueryResponse("Não consegui localizar um contexto relevante para responder.", Collections.emptyList());
        }

        List<ContentChunk> limitedChunks = combinedResults.stream().limit(5).toList();

        String prompt = buildPrompt(userQuestion, limitedChunks);
        logger.debug("Prompt enviado para a IA:\n{}", prompt);
        String aiAnswer = geminiApiClient.generateContent(prompt);
        List<String> sources = limitedChunks.stream().map(this::formatChunkSource).toList();

        return new QueryResponse(aiAnswer, sources);
    }

    /**
     * BUSCA VETORIAL USANDO QUERY RAW
     */
    private List<ContentChunk> performVectorSearch(String userQuestion) {
        try {
            logger.info("🔍 Realizando busca vetorial para: '{}'", userQuestion);

            // 1. Gerar embedding da pergunta
            PGvector questionVector = geminiApiClient.generateEmbedding(userQuestion);
            if (questionVector == null) {
                logger.warn("⚠️ Não foi possível gerar embedding para a pergunta");
                return Collections.emptyList();
            }

            // 2. Buscar usando query raw
            List<Object[]> rawResults = contentChunkRepository.findSimilarChunksRaw(
                    questionVector.toString(), 5
            );

            // 3. Converter resultados raw para ContentChunk
            List<ContentChunk> chunks = convertRawResultsToChunks(rawResults);

            logger.info("✅ Busca vetorial retornou {} chunks", chunks.size());
            return chunks;

        } catch (Exception e) {
            logger.error("❌ Erro na busca vetorial: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Converte resultados raw da query nativa para objetos ContentChunk
     */
    private List<ContentChunk> convertRawResultsToChunks(List<Object[]> rawResults) {
        List<ContentChunk> chunks = new ArrayList<>();

        for (Object[] row : rawResults) {
            try {
                ContentChunk chunk = new ContentChunk();

                // Mapear campos da query: id, content, question, section_title, chapter_title, chapter_number, section_number, work_id, similarity_score
                chunk.setId(((Number) row[0]).longValue());
                chunk.setContent((String) row[1]);
                chunk.setQuestion((String) row[2]);
                chunk.setSectionTitle((String) row[3]);
                chunk.setChapterTitle((String) row[4]);
                chunk.setChapterNumber(row[5] != null ? ((Number) row[5]).intValue() : null);
                chunk.setSectionNumber(row[6] != null ? ((Number) row[6]).intValue() : null);

                // Buscar a obra
                Long workId = ((Number) row[7]).longValue();
                Work work = workRepository.findById(workId).orElse(null);
                chunk.setWork(work);

                // Similarity score está em row[8] se precisar usar

                chunks.add(chunk);

            } catch (Exception e) {
                logger.warn("Erro ao converter resultado raw: {}", e.getMessage());
            }
        }

        return chunks;
    }

    private List<ContentChunk> performKeywordSearch(String userQuestion) {
        String cleanedQuestion = userQuestion.replaceAll("[^a-zA-Z0-9áéíóúâêôãõç\\s]", "").toLowerCase();
        String[] keywords = Arrays.stream(cleanedQuestion.split("\\s+"))
                .filter(word -> word.length() > 3)
                .toArray(String[]::new);

        Set<ContentChunk> keywordResults = new LinkedHashSet<>();
        for (String keyword : keywords) {
            logger.info("Buscando por palavra-chave direta: '{}'", keyword);
            try {
                List<ContentChunk> chunks = contentChunkRepository.findByContentContainingIgnoreCase(
                        keyword, PageRequest.of(0, 2)
                );
                keywordResults.addAll(chunks);
            } catch (Exception e) {
                logger.warn("Erro na busca por palavra-chave '{}': {}", keyword, e.getMessage());
            }
        }

        return new ArrayList<>(keywordResults);
    }

    private Set<Topic> findTopicsUsingAi(String userQuestion) {
        List<Topic> allTopics = topicRepository.findAll();
        String availableTopics = allTopics.stream()
                .map(Topic::getName)
                .collect(Collectors.joining(", "));

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

        String response = geminiApiClient.generateContent(classificationPrompt).trim();
        logger.info("Resposta da IA para classificação de tópicos: '{}'", response);

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

    private String buildPrompt(String question, List<ContentChunk> chunks) {
        StringBuilder context = new StringBuilder();
        context.append("Contexto Fornecido:\n");
        for (ContentChunk chunk : chunks) {
            context.append("- Fonte: ").append(formatChunkSource(chunk));
            if (chunk.getQuestion() != null) context.append("\n  Pergunta da Fonte: ").append(chunk.getQuestion());
            context.append("\n  Texto da Fonte: ").append(chunk.getContent()).append("\n\n");
        }

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

    private String formatChunkSource(ContentChunk chunk) {
        if ("CATECISMO".equals(chunk.getWork().getType())) {
            return String.format("%s - Pergunta %d", chunk.getWork().getTitle(), chunk.getSectionNumber());
        } else {
            return String.format("%s - Cap. %d, Seção %d", chunk.getWork().getTitle(), chunk.getChapterNumber(), chunk.getSectionNumber());
        }
    }
}