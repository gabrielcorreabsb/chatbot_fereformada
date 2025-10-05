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
    private static final Set<String> STOP_WORDS = java.util.Set.of(
            "o", "a", "os", "as", "um", "uma", "de", "da", "do", "dos", "das",
            "em", "na", "no", "nas", "nos", "por", "para", "com", "sem", "sob",
            "que", "qual", "quais", "quando", "onde", "como", "e", "ou", "mas",
            "se", "não", "sim", "é", "são", "foi", "foram", "ser", "estar",
            "ter", "haver", "fazer", "ir", "vir", "ver", "dar", "poder"
    );

    // ===== SINÔNIMOS TEOLÓGICOS =====
    private static final Map<String, List<String>> THEOLOGICAL_SYNONYMS = Map.ofEntries(
            Map.entry("salvação", List.of("redenção", "justificação", "soteriologia", "regeneração")),
            Map.entry("pecado", List.of("transgressão", "iniquidade", "queda", "depravação", "mal")),
            Map.entry("deus", List.of("senhor", "criador", "pai", "soberano", "yahweh", "jeová")),
            Map.entry("fé", List.of("crença", "confiança", "fidelidade", "crer")),
            Map.entry("graça", List.of("favor", "misericórdia", "benevolência", "bondade")),
            Map.entry("eleição", List.of("predestinação", "escolha", "chamado", "eleitos")),
            Map.entry("igreja", List.of("congregação", "assembleia", "corpo", "noiva")),
            Map.entry("escritura", List.of("bíblia", "palavra", "sagradas", "escrituras")),
            Map.entry("batismo", List.of("batizar", "sacramento", "imersão", "aspersão")),
            Map.entry("ceia", List.of("comunhão", "eucaristia", "santa", "sacramento")),
            Map.entry("oração", List.of("orar", "súplica", "intercessão", "petição")),
            Map.entry("santificação", List.of("santidade", "purificação", "consagração")),
            Map.entry("justificação", List.of("justificar", "declarar", "justo", "imputação")),
            Map.entry("cristo", List.of("jesus", "messias", "salvador", "redentor", "cordeiro"))
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

        // 2. NOVO: Pergunta explicitamente pede base bíblica
        boolean needsBiblicalFoundation = question.toLowerCase().matches(
                ".*(o que a bíblia|que diz a escritura|base bíblica|fundamentação|versículo|texto bíblico).*"
        );

        // 3. Pergunta contém referência bíblica específica
        boolean hasBiblicalReference = question.matches(".*\\d+[:\\.]\\d+.*") ||
                question.toLowerCase().matches(".*(capítulo|versículo|verso|cap\\.|v\\.).*");

        // 4. NOVO: Pergunta sobre livros bíblicos específicos
        boolean mentionsBiblicalBooks = question.toLowerCase().matches(
                ".*(gênesis|êxodo|levítico|números|deuteronômio|josué|juízes|rute|samuel|reis|crônicas|" +
                        "esdras|neemias|ester|jó|salmos|provérbios|eclesiastes|cantares|isaías|jeremias|" +
                        "ezequiel|daniel|oséias|joel|amós|obadias|jonas|miquéias|naum|habacuque|sofonias|" +
                        "ageu|zacarias|malaquias|mateus|marcos|lucas|joão|atos|romanos|coríntios|gálatas|" +
                        "efésios|filipenses|colossenses|tessalonicenses|timóteo|tito|filemom|hebreus|tiago|" +
                        "pedro|apocalipse).*"
        );

        // 5. Pergunta contém nomes específicos de documentos
        boolean hasSpecificNames = question.toLowerCase().matches(
                ".*(westminster|calvino|catecismo|confissão|institutas|genebra).*"
        );

        // 6. Pergunta muito curta (provável busca factual)
        boolean isShortQuery = question.split("\\s+").length <= 5;

        return hasWeakVectorResults || needsBiblicalFoundation || hasBiblicalReference ||
                mentionsBiblicalBooks || hasSpecificNames || isShortQuery;
    }

    private List<ContextItem> performKeywordSearch(String question) {
        Set<String> keywords = extractImportantKeywords(question);

        if (keywords.isEmpty()) {
            return Collections.emptyList();
        }

        List<ContextItem> results = new ArrayList<>();

        try {
            List<String> topKeywords = keywords.stream()
                    .filter(k -> k.length() > 3)
                    .filter(k -> !STOP_WORDS.contains(k))
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .limit(5)
                    .collect(Collectors.toList());

            if (topKeywords.isEmpty()) {
                logger.debug("Nenhuma keyword válida encontrada");
                return Collections.emptyList();
            }

            logger.debug("🔍 Buscando por {} keywords: {}", topKeywords.size(), topKeywords);

            Pageable limit3 = PageRequest.of(0, 3);
            Set<Long> processedChunkIds = new HashSet<>();
            Set<Long> processedNoteIds = new HashSet<>();

            for (String keyword : topKeywords) {
                try {
                    logger.debug("  → Buscando por: '{}'", keyword);

                    // ✅ NOVO: Try-catch individual para cada keyword
                    try {
                        List<ContentChunk> chunks = contentChunkRepository.searchByKeywords(keyword, limit3);
                        for (ContentChunk chunk : chunks) {
                            if (!processedChunkIds.contains(chunk.getId())) {
                                double score = calculateEnhancedKeywordScore(chunk.getContent(), keywords, keyword);
                                results.add(ContextItem.from(chunk, score));
                                processedChunkIds.add(chunk.getId());
                            }
                        }
                    } catch (Exception chunkError) {
                        logger.warn("  ⚠️ Erro ao buscar chunks para '{}': {}", keyword, chunkError.getMessage());
                    }

                    try {
                        List<StudyNote> notes = studyNoteRepository.searchByKeywords(keyword, limit3);
                        for (StudyNote note : notes) {
                            if (!processedNoteIds.contains(note.getId())) {
                                double score = calculateEnhancedKeywordScore(note.getNoteContent(), keywords, keyword);
                                results.add(ContextItem.from(note, score));
                                processedNoteIds.add(note.getId());
                            }
                        }
                    } catch (Exception noteError) {
                        logger.warn("  ⚠️ Erro ao buscar notas para '{}': {}", keyword, noteError.getMessage());
                    }

                } catch (Exception keywordError) {
                    logger.warn("  ❌ Erro geral para keyword '{}': {}", keyword, keywordError.getMessage());
                    continue; // Continua com próxima keyword
                }
            }

            logger.debug("✅ Keyword search encontrou {} itens únicos", results.size());

        } catch (Exception e) {
            logger.warn("❌ Erro na busca por keywords (usando apenas busca vetorial): {}", e.getMessage());
            // Retorna lista vazia - sistema continua só com busca vetorial
            return Collections.emptyList();
        }

        return results;
    }

    private Set<String> extractImportantKeywords(String question) {
        Set<String> keywords = new HashSet<>();
        String[] words = question.toLowerCase()
                .replaceAll("[?!.,;:]", "")
                .split("\\s+");

        // 1. Adicionar palavras principais
        for (String word : words) {
            // Pular stop words e palavras muito curtas
            if (word.length() > 2 && !STOP_WORDS.contains(word)) {
                keywords.add(word);

                // 2. Adicionar sinônimos teológicos se aplicável
                if (THEOLOGICAL_SYNONYMS.containsKey(word)) {
                    List<String> synonyms = THEOLOGICAL_SYNONYMS.get(word);
                    // Adicionar apenas os 3 sinônimos mais importantes
                    keywords.addAll(synonyms.stream().limit(3).collect(Collectors.toList()));
                }
            }
        }

        // 3. Adicionar frases importantes
        addImportantPhrases(question.toLowerCase(), keywords);

        // 4. Priorizar termos teológicos específicos
        prioritizeTheologicalTerms(keywords, question.toLowerCase());

        logger.debug("🔤 Keywords extraídas ({}): {}", keywords.size(),
                keywords.stream().limit(8).collect(Collectors.toList()));

        return keywords;
    }

    /**
     * Dá prioridade a termos teológicos importantes
     */
    private void prioritizeTheologicalTerms(Set<String> keywords, String question) {
        // Termos que sempre devem ser incluídos se aparecerem na pergunta
        String[] priorityTerms = {
                "deus", "cristo", "jesus", "espírito", "santo", "bíblia", "escritura",
                "salvação", "graça", "fé", "pecado", "justificação", "santificação",
                "eleição", "predestinação", "batismo", "ceia", "igreja", "oração"
        };

        for (String term : priorityTerms) {
            if (question.contains(term) && !keywords.contains(term)) {
                keywords.add(term);
            }
        }
    }

    private void addImportantPhrases(String question, Set<String> keywords) {
        // ===== FRASES IMPORTANTES =====
        Map<String, List<String>> importantPhrases = Map.ofEntries(
                Map.entry("espírito santo", List.of("espírito", "santo")),
                Map.entry("jesus cristo", List.of("jesus", "cristo")),
                Map.entry("palavra de deus", List.of("palavra", "deus")),
                Map.entry("reino de deus", List.of("reino", "deus")),
                Map.entry("filho de deus", List.of("filho", "deus")),
                Map.entry("corpo de cristo", List.of("corpo", "cristo")),
                Map.entry("novo testamento", List.of("novo", "testamento")),
                Map.entry("antigo testamento", List.of("antigo", "testamento")),
                Map.entry("sola scriptura", List.of("sola", "scriptura", "escritura")),
                Map.entry("sola fide", List.of("sola", "fide", "fé"))
        );

        String questionLower = question.toLowerCase();
        for (Map.Entry<String, List<String>> entry : importantPhrases.entrySet()) {
            if (questionLower.contains(entry.getKey())) {
                keywords.addAll(entry.getValue());
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
        String content = item.content().toLowerCase();

        // ===== NOVO: PRIORIDADE MÁXIMA PARA ESCRITURA =====
        // 1. BOOST SUPREMO para notas bíblicas (Sola Scriptura!)
        if (source.contains("bíblia de genebra")) {
            boost *= 1.4;  // MAIOR que qualquer documento confessional

            // Boost extra se contém referência bíblica direta
            if (content.matches(".*\\d+[:\\.]\\d+.*")) {
                boost *= 1.2;  // Total: 1.68x
            }

            // Boost extra para temas doutrinários que precisam de base bíblica
            if (isDoctrinalQuestion(questionLower)) {
                boost *= 1.15; // Ainda mais boost para doutrina
            }
        }

        // 2. Documentos confessionais (subordinados à Escritura)
        else if (source.contains("confissão de fé")) {
            boost *= 1.3;
        } else if (source.contains("catecismo maior")) {
            boost *= 1.25;
        } else if (source.contains("breve catecismo")) {
            boost *= 1.2;
        } else if (source.contains("institutas")) {
            boost *= 1.15;
        }

        // 3. Boost se tem estrutura pergunta/resposta
        if (item.question() != null && !item.question().isEmpty()) {
            boost *= 1.1;

            if (calculateSimilarity(item.question().toLowerCase(), questionLower) > 0.7) {
                boost *= 1.2;
            }
        }

        // 4. NOVO: Boost para conteúdo que cita muitas referências bíblicas
        int biblicalReferences = countBiblicalReferences(item.content());
        if (biblicalReferences > 2) {
            boost *= 1.1 + (biblicalReferences * 0.05); // Mais referências = mais boost
        }

        // 5. Penalidade para conteúdo muito curto
        if (item.content().length() < 100) {
            boost *= 0.8;
        }

        return item.withAdjustedScore(item.similarityScore() * boost);
    }

    // ===== NOVOS MÉTODOS AUXILIARES =====
    private boolean isDoctrinalQuestion(String question) {
        // Detectar perguntas que precisam de fundamentação bíblica sólida
        String[] doctrinalKeywords = {
                "doutrina", "ensina", "bíblia", "escritura", "palavra", "deus",
                "salvação", "pecado", "graça", "fé", "justificação", "santificação",
                "eleição", "predestinação", "trindade", "cristo", "espírito",
                "igreja", "sacramento", "batismo", "ceia", "oração", "lei"
        };

        return Arrays.stream(doctrinalKeywords)
                .anyMatch(question::contains);
    }

    private int countBiblicalReferences(String content) {
        // Contar referências bíblicas no formato "Livro X:Y" ou "X:Y"
        String pattern = "\\b\\d+[:\\.]\\d+(-\\d+)?\\b";
        return (int) content.split(pattern).length - 1;
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

        // Contar por tipo de fonte
        long biblicalNotes = results.stream().filter(ContextItem::isBiblicalNote).count();
        long confessional = results.stream().filter(item -> !item.isBiblicalNote()).count();

        logger.info("  📖 Fontes bíblicas: {}, ⛪ Fontes confessionais: {}", biblicalNotes, confessional);

        // Mostrar top 3 com mais detalhes
        for (int i = 0; i < Math.min(3, results.size()); i++) {
            ContextItem item = results.get(i);
            String type = item.isBiblicalNote() ? "📖" : "⛪";
            logger.info("  {}. {} [Score: {:.3f}] {}",
                    i + 1, type, item.similarityScore(), item.source());
        }

        // Log da qualidade geral
        double avgScore = results.stream()
                .mapToDouble(ContextItem::similarityScore)
                .average()
                .orElse(0.0);

        String qualityEmoji = avgScore > 0.8 ? "🔥" : avgScore > 0.6 ? "✅" : "⚠️";
        logger.info("  {} Qualidade média: {:.1f}%", qualityEmoji, avgScore * 100);
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

        // ===== NOVO: SEPARAR FONTES BÍBLICAS DAS CONFESSIONAIS =====
        List<ContextItem> biblicalSources = items.stream()
                .filter(item -> item.source().contains("Bíblia de Genebra"))
                .collect(Collectors.toList());

        List<ContextItem> confessionalSources = items.stream()
                .filter(item -> !item.source().contains("Bíblia de Genebra"))
                .collect(Collectors.toList());

        // Mostrar fontes bíblicas primeiro
        if (!biblicalSources.isEmpty()) {
            context.append("📖 FUNDAMENTAÇÃO BÍBLICA (Sola Scriptura):\n\n");
            int biblicalNumber = 1;
            for (ContextItem item : biblicalSources) {
                context.append(String.format("[B%d] %s\n", biblicalNumber++, item.source()));
                context.append("    📜 Texto: ").append(limitContent(item.content(), 600)).append("\n\n");
            }
        }

        // Depois mostrar fontes confessionais
        if (!confessionalSources.isEmpty()) {
            context.append("⛪ DOCUMENTOS CONFESSIONAIS (subordinados à Escritura):\n\n");
            int confessionalNumber = 1;
            for (ContextItem item : confessionalSources) {
                context.append(String.format("[C%d] %s\n", confessionalNumber++, item.source()));

                if (item.question() != null && !item.question().isEmpty()) {
                    context.append("    📝 Pergunta: ").append(item.question()).append("\n");
                }

                context.append("    📖 Conteúdo: ").append(limitContent(item.content(), 500)).append("\n");
                context.append("    🎯 Relevância: ").append(
                        String.format("%.1f%%", item.similarityScore() * 100)
                ).append("\n\n");
            }
        }

        return String.format("""
                Você é um assistente teológico reformado que segue rigorosamente o princípio SOLA SCRIPTURA.
                
                PRINCÍPIOS FUNDAMENTAIS:
                1. A Escritura é a autoridade suprema e infalível em questões de fé e prática.
                2. Os documentos confessionais (Westminster, Calvino) são subordinados à Escritura.
                3. SEMPRE priorize e cite primeiro as referências bíblicas [B1, B2, etc.].
                4. Use os documentos confessionais [C1, C2, etc.] para explicar e sistematizar o ensino bíblico.
                5. Se houver conflito, a Escritura prevalece sobre qualquer documento humano.
                
                INSTRUÇÕES ESPECÍFICAS:
                - Comece sua resposta com a base bíblica quando disponível
                - Cite as fontes usando [B1] para bíblicas e [C1] para confessionais
                - Explique como os documentos confessionais confirmam/sistematizam o ensino bíblico
                - Use tom professoral, mas sempre reverente à Palavra de Deus
                - Termine com aplicação prática baseada na Escritura
                
                %s
                
                PERGUNTA DO USUÁRIO:
                %s
                
                RESPOSTA (priorizando Sola Scriptura):
                """, context.toString(), question);
    }

    private String limitContent(String content, int maxLength) {
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...";
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

    private double calculateEnhancedKeywordScore(String content, Set<String> allKeywords, String triggerKeyword) {
        if (content == null || content.isEmpty()) {
            return 0.0;
        }

        String contentLower = content.toLowerCase();

        // 1. Contar matches únicos
        long uniqueMatches = allKeywords.stream()
                .filter(contentLower::contains)
                .count();

        // 2. Score base (proporção de keywords encontradas)
        double baseScore = (double) uniqueMatches / allKeywords.size();

        // 3. Boost para keyword que triggou a busca
        double triggerBoost = contentLower.contains(triggerKeyword.toLowerCase()) ? 1.2 : 1.0;

        // 4. Boost para densidade de keywords (quantas vezes aparecem)
        long totalMatches = allKeywords.stream()
                .mapToLong(keyword -> countOccurrences(contentLower, keyword.toLowerCase()))
                .sum();

        double densityBoost = 1.0 + (totalMatches * 0.05); // 5% boost por ocorrência extra
        densityBoost = Math.min(densityBoost, 2.0); // Máximo 2x boost

        // 5. Score final
        double finalScore = baseScore * triggerBoost * densityBoost;

        logger.debug("    📊 Score para '{}': base={:.2f}, trigger={:.2f}, density={:.2f}, final={:.2f}",
                triggerKeyword, baseScore, triggerBoost, densityBoost, finalScore);

        return Math.min(finalScore, 1.0); // Máximo 1.0
    }

    /**
     * Conta quantas vezes uma palavra aparece no texto
     */
    private long countOccurrences(String text, String word) {
        if (text == null || word == null || word.isEmpty()) {
            return 0;
        }

        int count = 0;
        int index = 0;

        while ((index = text.indexOf(word, index)) != -1) {
            count++;
            index += word.length();
        }

        return count;
    }
}