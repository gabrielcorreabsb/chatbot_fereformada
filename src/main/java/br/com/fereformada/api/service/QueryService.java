package br.com.fereformada.api.service;

import br.com.fereformada.api.dto.ContextItem;
import br.com.fereformada.api.dto.QueryResponse;
import br.com.fereformada.api.model.ContentChunk;
import br.com.fereformada.api.model.StudyNote;
import br.com.fereformada.api.model.Work;
import br.com.fereformada.api.repository.ContentChunkRepository;
import br.com.fereformada.api.repository.StudyNoteRepository;
import br.com.fereformada.api.repository.WorkRepository;
import com.pgvector.PGvector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class QueryService {

    private static final Logger logger = LoggerFactory.getLogger(QueryService.class);

    // ===== OTIMIZAÇÃO 3: CACHE =====
    private final Map<String, QueryResponse> responseCache = new ConcurrentHashMap<>();
    private final Map<String, PGvector> embeddingCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 100;
    private static final int MAX_EMBEDDING_CACHE_SIZE = 500;

    // ===== STOP WORDS EM PORTUGUÊS =====
    private static final Set<String> STOP_WORDS = Set.of(
            "o", "a", "os", "as", "um", "uma", "de", "da", "do", "dos", "das",
            "em", "na", "no", "nas", "nos", "por", "para", "com", "sem", "sob",
            "que", "qual", "quais", "quando", "onde", "como", "e", "ou", "mas",
            "se", "não", "sim", "é", "são", "foi", "foram", "ser", "estar",
            "ter", "haver", "fazer", "ir", "vir", "ver", "dar", "poder"
    );

    // ===== SINÔNIMOS TEOLÓGICOS =====
    private static final Map<String, List<String>> THEOLOGICAL_SYNONYMS = Map.of(
            "salvação", List.of("redenção", "justificação", "soteriologia"),
            "pecado", List.of("transgressão", "iniquidade", "queda", "depravação"),
            "deus", List.of("senhor", "criador", "pai celestial", "soberano", "yahweh"),
            "fé", List.of("crença", "confiança", "fidelidade"),
            "graça", List.of("favor", "misericórdia", "benevolência"),
            "eleição", List.of("predestinação", "escolha", "chamado"),
            "igreja", List.of("congregação", "assembleia", "corpo de cristo"),
            "escritura", List.of("bíblia", "palavra de deus", "sagradas escrituras")
    );

    private final ContentChunkRepository contentChunkRepository;
    private final StudyNoteRepository studyNoteRepository;
    private final WorkRepository workRepository;
    private final GeminiApiClient geminiApiClient;

    public QueryService(ContentChunkRepository contentChunkRepository,
                        StudyNoteRepository studyNoteRepository,
                        WorkRepository workRepository,
                        GeminiApiClient geminiApiClient) {
        this.contentChunkRepository = contentChunkRepository;
        this.studyNoteRepository = studyNoteRepository;
        this.workRepository = workRepository;
        this.geminiApiClient = geminiApiClient;
    }

    public QueryResponse query(String userQuestion) {
        logger.info("Nova pergunta recebida: '{}'", userQuestion);

        // ===== OTIMIZAÇÃO 3: VERIFICAR CACHE =====
        String cacheKey = normalizeQuestion(userQuestion);
        if (responseCache.containsKey(cacheKey)) {
            logger.info("✅ Cache hit para: '{}'", userQuestion);
            return responseCache.get(cacheKey);
        }

        // ===== OTIMIZAÇÃO 1: HYBRID SEARCH =====
        List<ContextItem> results = performHybridSearch(userQuestion);

        if (results.isEmpty()) {
            QueryResponse emptyResponse = new QueryResponse(
                    "Não encontrei informações relevantes nas fontes catalogadas. " +
                            "Tente reformular sua pergunta ou ser mais específico.",
                    Collections.emptyList()
            );

            // Não cachear respostas vazias
            return emptyResponse;
        }

        // Log de qualidade
        double avgScore = results.stream()
                .mapToDouble(ContextItem::similarityScore)
                .average()
                .orElse(0.0);

        if (avgScore < 0.6) {
            logger.warn("⚠️ Baixa relevância média ({}) para: '{}'",
                    String.format("%.2f", avgScore), userQuestion);
        }

        logger.info("📊 Construindo resposta com {} fontes (relevância média: {})",
                results.size(), String.format("%.2f", avgScore));

        String prompt = buildOptimizedPrompt(userQuestion, results);
        String aiAnswer = geminiApiClient.generateContent(prompt);
        List<String> sources = results.stream().map(ContextItem::source).toList();

        QueryResponse response = new QueryResponse(aiAnswer, sources);

        // Cachear resposta se houver espaço
        if (responseCache.size() < MAX_CACHE_SIZE) {
            responseCache.put(cacheKey, response);
        }

        return response;
    }

    // ===== OTIMIZAÇÃO 1: HYBRID SEARCH =====
    private List<ContextItem> performHybridSearch(String userQuestion) {
        // 1. Busca vetorial (peso 70%)
        List<ContextItem> vectorResults = performVectorSearch(userQuestion);

        // 2. Verificar se precisa de busca por keywords
        List<ContextItem> keywordResults = Collections.emptyList();

        if (shouldUseKeywordSearch(userQuestion, vectorResults)) {
            logger.info("🔍 Ativando busca por palavras-chave para melhorar resultados");
            keywordResults = performKeywordSearch(userQuestion);
        }

        // 3. Combinar e reranquear
        return combineAndRerankResults(vectorResults, keywordResults, userQuestion);
    }

    private boolean shouldUseKeywordSearch(String question, List<ContextItem> vectorResults) {
        // Ativar keyword search se:
        // 1. Resultados vetoriais fracos
        boolean hasWeakVectorResults = vectorResults.isEmpty() ||
                vectorResults.stream().allMatch(item -> item.similarityScore() < 0.7);

        // 2. Pergunta contém referência bíblica específica
        boolean hasBiblicalReference = question.matches(".*\\d+[:\\.]\\d+.*") ||
                question.toLowerCase().matches(".*(capítulo|versículo|verso|cap\\.|v\\.).*");

        // 3. Pergunta contém nomes específicos de documentos
        boolean hasSpecificNames = question.toLowerCase().matches(
                ".*(westminster|calvino|catecismo|confissão|institutas|genebra).*"
        );

        // 4. Pergunta muito curta (provável busca factual)
        boolean isShortQuery = question.split("\\s+").length <= 5;

        return hasWeakVectorResults || hasBiblicalReference || hasSpecificNames || isShortQuery;
    }

    private List<ContextItem> performKeywordSearch(String question) {
        Set<String> keywords = extractImportantKeywords(question);

        if (keywords.isEmpty()) {
            return Collections.emptyList();
        }

        List<ContextItem> results = new ArrayList<>();

        try {
            // Pegar a keyword mais relevante
            String mainKeyword = keywords.stream()
                    .max(Comparator.comparingInt(String::length))
                    .orElse("");

            if (mainKeyword.isEmpty()) {
                return Collections.emptyList();
            }

            logger.debug("Buscando por keyword principal: '{}'", mainKeyword);

            // *** MUDANÇA: Usar PageRequest em vez de int limit ***
            Pageable limit3 = PageRequest.of(0, 3);

            List<ContentChunk> chunks = contentChunkRepository.searchByKeywords(mainKeyword, limit3);
            List<StudyNote> notes = studyNoteRepository.searchByKeywords(mainKeyword, limit3);

            // Converter para ContextItems
            for (ContentChunk chunk : chunks) {
                double score = calculateKeywordScore(chunk.getContent(), keywords);
                results.add(ContextItem.from(chunk, score));
            }

            for (StudyNote note : notes) {
                double score = calculateKeywordScore(note.getNoteContent(), keywords);
                results.add(ContextItem.from(note, score));
            }

            logger.debug("Keyword search encontrou {} chunks e {} notas", chunks.size(), notes.size());

        } catch (Exception e) {
            logger.error("Erro na busca por keywords: {}", e.getMessage(), e);
        }

        return results;
    }

    private Set<String> extractImportantKeywords(String question) {
        Set<String> keywords = new HashSet<>();
        String[] words = question.toLowerCase()
                .replaceAll("[?!.,;:]", "")
                .split("\\s+");

        for (String word : words) {
            // Pular stop words e palavras muito curtas
            if (word.length() > 2 && !STOP_WORDS.contains(word)) {
                keywords.add(word);

                // Adicionar sinônimos teológicos se aplicável
                if (THEOLOGICAL_SYNONYMS.containsKey(word)) {
                    keywords.addAll(THEOLOGICAL_SYNONYMS.get(word));
                }
            }
        }

        // Adicionar bigrams importantes (ex: "espírito santo")
        addImportantPhrases(question.toLowerCase(), keywords);

        logger.debug("Keywords extraídas: {}", keywords);
        return keywords;
    }

    private void addImportantPhrases(String question, Set<String> keywords) {
        // Frases teológicas importantes que devem ser buscadas juntas
        Map<String, String> importantPhrases = Map.of(
                "espírito santo", "espírito & santo",
                "jesus cristo", "jesus & cristo",
                "novo testamento", "novo & testamento",
                "antigo testamento", "antigo & testamento",
                "sola scriptura", "sola & scriptura",
                "sola fide", "sola & fide",
                "sola gratia", "sola & gratia"
        );

        for (Map.Entry<String, String> entry : importantPhrases.entrySet()) {
            if (question.contains(entry.getKey())) {
                keywords.add(entry.getValue());
            }
        }
    }

    private double calculateKeywordScore(String content, Set<String> keywords) {
        if (content == null || content.isEmpty()) {
            return 0.0;
        }

        String contentLower = content.toLowerCase();
        long matchCount = keywords.stream()
                .filter(contentLower::contains)
                .count();

        // Score entre 0 e 1 baseado na proporção de keywords encontradas
        return (double) matchCount / keywords.size();
    }

    // ===== OTIMIZAÇÃO 2: RERANKING INTELIGENTE =====
    private List<ContextItem> combineAndRerankResults(
            List<ContextItem> vectorResults,
            List<ContextItem> keywordResults,
            String userQuestion) {

        Map<String, ContextItem> combined = new HashMap<>();

        // Adicionar resultados vetoriais com peso 0.7
        for (ContextItem item : vectorResults) {
            String key = generateItemKey(item);
            double adjustedScore = item.similarityScore() * 0.7;
            combined.put(key, item.withAdjustedScore(adjustedScore));
        }

        // Adicionar/mesclar resultados de keyword com peso 0.3
        for (ContextItem item : keywordResults) {
            String key = generateItemKey(item);
            if (combined.containsKey(key)) {
                ContextItem existing = combined.get(key);
                double newScore = existing.similarityScore() + (item.similarityScore() * 0.3);
                combined.put(key, existing.withAdjustedScore(newScore));
            } else {
                double adjustedScore = item.similarityScore() * 0.3;
                combined.put(key, item.withAdjustedScore(adjustedScore));
            }
        }

        // Aplicar boost por fonte e outros critérios
        List<ContextItem> reranked = combined.values().stream()
                .map(item -> applySmartBoosts(item, userQuestion))
                .sorted(Comparator.comparing(ContextItem::similarityScore).reversed())
                .limit(5)
                .collect(Collectors.toList());

        logRerankedResults(reranked);
        return reranked;
    }

    private String generateItemKey(ContextItem item) {
        // Gerar chave única para detectar duplicatas
        return item.source() + "_" + item.content().substring(0, Math.min(50, item.content().length()));
    }

    private ContextItem applySmartBoosts(ContextItem item, String question) {
        double boost = 1.0;
        String source = item.source().toLowerCase();
        String questionLower = question.toLowerCase();

        // 1. Boost por autoridade da fonte
        if (source.contains("confissão de fé")) {
            boost *= 1.3;  // Máxima autoridade doutrinária
        } else if (source.contains("catecismo maior")) {
            boost *= 1.25;
        } else if (source.contains("breve catecismo")) {
            boost *= 1.2;
        } else if (source.contains("institutas")) {
            boost *= 1.15;
        } else if (source.contains("bíblia de genebra")) {
            boost *= 1.1;
        }

        // 2. Boost se tem estrutura pergunta/resposta e a pergunta é similar
        if (item.question() != null && !item.question().isEmpty()) {
            boost *= 1.1;

            // Boost extra se a pergunta da fonte é muito similar à pergunta do usuário
            if (calculateSimilarity(item.question().toLowerCase(), questionLower) > 0.7) {
                boost *= 1.2;
            }
        }

        // 3. Boost para referências bíblicas diretas quando relevante
        if (questionLower.matches(".*\\d+[:\\.]\\d+.*") &&
                item.content().matches(".*\\d+[:\\.]\\d+.*")) {
            boost *= 1.15;
        }

        // 4. Penalidade para conteúdo muito curto
        if (item.content().length() < 100) {
            boost *= 0.8;
        }

        return item.withAdjustedScore(item.similarityScore() * boost);
    }

    private double calculateSimilarity(String text1, String text2) {
        // Similaridade simples baseada em palavras comuns
        Set<String> words1 = new HashSet<>(Arrays.asList(text1.split("\\s+")));
        Set<String> words2 = new HashSet<>(Arrays.asList(text2.split("\\s+")));

        Set<String> intersection = new HashSet<>(words1);
        intersection.retainAll(words2);

        Set<String> union = new HashSet<>(words1);
        union.addAll(words2);

        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private void logRerankedResults(List<ContextItem> results) {
        logger.info("📊 Top {} resultados após reranking:", results.size());
        for (int i = 0; i < Math.min(3, results.size()); i++) {
            ContextItem item = results.get(i);
            logger.info("  {}. [Score: {:.3f}] {}",
                    i + 1,
                    item.similarityScore(),
                    item.source()
            );
        }
    }

    // ===== BUSCA VETORIAL OTIMIZADA =====
    private List<ContextItem> performVectorSearch(String userQuestion) {
        // Usar cache de embeddings
        PGvector questionVector = getOrComputeEmbedding(userQuestion);

        if (questionVector == null) {
            logger.warn("⚠️ Não foi possível gerar embedding para a pergunta");
            return Collections.emptyList();
        }

        // Buscar mais resultados inicialmente para melhor reranking
        List<Object[]> rawChunkResults = contentChunkRepository.findSimilarChunksRaw(
                questionVector.toString(), 5
        );
        List<ContextItem> chunkItems = convertRawChunkResultsToContextItems(rawChunkResults);

        List<Object[]> rawNoteResults = studyNoteRepository.findSimilarNotesRaw(
                questionVector.toString(), 5
        );
        List<ContextItem> noteItems = convertRawNoteResultsToContextItems(rawNoteResults);

        // Combinar e retornar todos (o reranking será feito depois)
        List<ContextItem> combinedItems = new ArrayList<>();
        combinedItems.addAll(chunkItems);
        combinedItems.addAll(noteItems);

        return combinedItems;
    }

    // ===== CACHE DE EMBEDDINGS =====
    private PGvector getOrComputeEmbedding(String text) {
        // Limitar o texto para a chave do cache
        String cacheKey = text.length() > 200 ? text.substring(0, 200) : text;

        PGvector cached = embeddingCache.get(cacheKey);
        if (cached != null) {
            logger.debug("✅ Embedding encontrado no cache");
            return cached;
        }

        PGvector computed = geminiApiClient.generateEmbedding(text);

        // Adicionar ao cache se houver espaço
        if (computed != null && embeddingCache.size() < MAX_EMBEDDING_CACHE_SIZE) {
            embeddingCache.put(cacheKey, computed);
        }

        return computed;
    }

    // ===== NORMALIZAÇÃO PARA CACHE =====
    private String normalizeQuestion(String question) {
        return question.toLowerCase()
                .replaceAll("\\s+", " ")
                .replaceAll("[?!.,;:]", "")
                .trim();
    }

    // ===== PROMPT OTIMIZADO =====
    private String buildOptimizedPrompt(String question, List<ContextItem> items) {
        StringBuilder context = new StringBuilder();
        context.append("CONTEXTO RELEVANTE (ordenado por relevância):\n\n");

        int contextNumber = 1;
        for (ContextItem item : items) {
            context.append(String.format("[%d] Fonte: %s\n", contextNumber++, item.source()));

            if (item.question() != null && !item.question().isEmpty()) {
                context.append("    📝 Pergunta Original: ").append(item.question()).append("\n");
            }

            // Limitar tamanho do contexto se necessário
            String content = item.content();
            if (content.length() > 800) {
                content = content.substring(0, 800) + "...";
            }

            context.append("    📖 Conteúdo: ").append(content).append("\n");
            context.append("    🎯 Relevância: ").append(
                    String.format("%.1f%%", item.similarityScore() * 100)
            ).append("\n\n");
        }

        return String.format("""
        Você é um assistente teológico especialista na Fé Reformada, com profundo conhecimento dos documentos de Westminster e das Institutas de Calvino.
        
        INSTRUÇÕES IMPORTANTES:
        1. Responda SEMPRE baseando-se no contexto fornecido abaixo.
        2. Cite as fontes usando [número] ao referenciar informações específicas.
        3. Se o contexto não contiver informação suficiente, indique isso claramente.
        4. Use um tom professoral, mas acessível e didático.
        5. Estruture sua resposta de forma clara, com parágrafos bem definidos.
        6. Ao final, sempre inclua um breve resumo ou aplicação prática.
        
        %s
        
        PERGUNTA DO USUÁRIO:
        %s
        
        RESPOSTA:
        """, context.toString(), question);
    }

    // ===== MÉTODOS AUXILIARES EXISTENTES =====
    private List<ContextItem> convertRawChunkResultsToContextItems(List<Object[]> rawResults) {
        List<ContextItem> items = new ArrayList<>();
        for (Object[] row : rawResults) {
            try {
                Long workId = ((Number) row[7]).longValue();
                Work work = workRepository.findById(workId).orElse(null);

                ContentChunk chunk = new ContentChunk();
                chunk.setId(((Number) row[0]).longValue());
                chunk.setContent((String) row[1]);
                chunk.setQuestion((String) row[2]);
                chunk.setSectionTitle((String) row[3]);
                chunk.setChapterTitle((String) row[4]);
                chunk.setChapterNumber(row[5] != null ? ((Number) row[5]).intValue() : null);
                chunk.setSectionNumber(row[6] != null ? ((Number) row[6]).intValue() : null);
                chunk.setWork(work);

                double score = ((Number) row[8]).doubleValue();
                items.add(ContextItem.from(chunk, score));
            } catch (Exception e) {
                logger.warn("Erro ao converter resultado raw de chunk: {}", e.getMessage());
            }
        }
        return items;
    }

    private List<ContextItem> convertRawNoteResultsToContextItems(List<Object[]> rawResults) {
        List<ContextItem> items = new ArrayList<>();
        for (Object[] row : rawResults) {
            try {
                StudyNote note = new StudyNote();
                note.setId(((Number) row[0]).longValue());
                note.setBook((String) row[1]);
                note.setStartChapter(((Number) row[2]).intValue());
                note.setStartVerse(((Number) row[3]).intValue());
                note.setEndChapter(((Number) row[4]).intValue());
                note.setEndVerse(((Number) row[5]).intValue());
                note.setNoteContent((String) row[6]);

                double score = ((Number) row[7]).doubleValue();
                items.add(ContextItem.from(note, score));
            } catch (Exception e) {
                logger.warn("Erro ao converter resultado raw de note: {}", e.getMessage());
            }
        }
        return items;
    }

    // ===== MÉTODO PARA LIMPAR CACHE (útil para admin) =====
    public void clearCache() {
        responseCache.clear();
        embeddingCache.clear();
        logger.info("🧹 Cache limpo manualmente");
    }

    public Map<String, Integer> getCacheStats() {
        Map<String, Integer> stats = new HashMap<>();
        stats.put("responseCacheSize", responseCache.size());
        stats.put("embeddingCacheSize", embeddingCache.size());
        return stats;
    }
}