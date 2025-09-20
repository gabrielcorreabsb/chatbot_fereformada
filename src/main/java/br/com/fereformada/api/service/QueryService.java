package br.com.fereformada.api.service;

import br.com.fereformada.api.dto.QueryResponse;
import br.com.fereformada.api.model.ContentChunk;
import br.com.fereformada.api.model.Topic;
import br.com.fereformada.api.repository.ContentChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class QueryService {

    private static final Logger logger = LoggerFactory.getLogger(QueryService.class);
    private final ContentChunkRepository contentChunkRepository;
    private final TaggingService taggingService;
    private final GeminiApiClient geminiApiClient; // <-- NOVA DEPENDÊNCIA

    public QueryService(ContentChunkRepository contentChunkRepository, TaggingService taggingService,
                        GeminiApiClient geminiApiClient) { // <-- ADICIONAR AO CONSTRUTOR
        this.contentChunkRepository = contentChunkRepository;
        this.taggingService = taggingService;
        this.geminiApiClient = geminiApiClient; // <-- NOVA ATRIBUIÇÃO
    }

    public QueryResponse query(String userQuestion) {
        // ... (Passo 1 e 2: Lógica de busca de tópicos e chunks - sem alterações) ...
        Set<Topic> topics = taggingService.getTagsFor(userQuestion);
        if (topics.isEmpty()) {
            return new QueryResponse("Desculpe, não encontrei informações sobre este tópico.", Collections.emptyList());
        }
        System.out.println("Tópicos encontrados: " + topics.stream().map(Topic::getName).collect(Collectors.joining(", ")));
        String workFilter = extractWorkFilter(userQuestion);
        List<ContentChunk> relevantChunks = contentChunkRepository.findRelevantChunks(topics, workFilter);
        if (relevantChunks.isEmpty() && !workFilter.isEmpty()) {
            logger.warn("Nenhum chunk encontrado com o filtro '{}'. Tentando busca geral.", workFilter);
            relevantChunks = contentChunkRepository.findFirst5ByTopicsIn(topics);
        }
        if (relevantChunks.isEmpty()) {
            return new QueryResponse("Não consegui localizar um contexto específico para responder.", Collections.emptyList());
        }

        // Limita o número de chunks para não sobrecarregar a IA
        List<ContentChunk> limitedChunks = relevantChunks.stream().limit(5).toList();

        // Passo 3: Montar o prompt
        String prompt = buildPrompt(userQuestion, limitedChunks);
        System.out.println("\n--- PROMPT ENVIADO PARA A IA ---\n" + prompt);

        // --- PASSO 4: CHAMAR A IA E RETORNAR A RESPOSTA REAL ---
        String aiAnswer = geminiApiClient.generateContent(prompt);

        List<String> sources = limitedChunks.stream()
                .map(this::formatChunkSource)
                .toList();

        return new QueryResponse(aiAnswer, sources);
    }

    // Método auxiliar com filtros mais específicos
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
        context.append("Contexto:\n");
        for (ContentChunk chunk : chunks) {
            context.append("- Fonte: ").append(formatChunkSource(chunk));
            if (chunk.getQuestion() != null) context.append("\n  Pergunta: ").append(chunk.getQuestion());
            context.append("\n  Texto: ").append(chunk.getContent()).append("\n\n");
        }

        return String.format("""
                Você é um assistente teológico especialista na Fé Reformada.
                Responda à pergunta do usuário baseando-se EXCLUSIVAMENTE no contexto fornecido.
                Seja claro, direto e, se possível, cite a fonte mencionada no contexto.

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