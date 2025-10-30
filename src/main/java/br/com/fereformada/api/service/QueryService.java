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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        Optional<QueryResponse> directResponse = handleDirectReferenceQuery(userQuestion);
        if (directResponse.isPresent()) {
            logger.info("✅ Resposta gerada via busca direta por referência.");
            return directResponse.get();
        }
        // =======================================================

        logger.info("Nova pergunta recebida (busca híbrida): '{}'", userQuestion);

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
        String aiAnswer;
        try {
            // Tenta gerar a resposta com o Gemini
            aiAnswer = geminiApiClient.generateContent(prompt);

            // Verificação adicional: Se a resposta vier vazia (raro, mas possível)
            if (aiAnswer == null || aiAnswer.trim().isEmpty()) {
                logger.warn("⚠️ A API do Gemini retornou uma resposta vazia para a pergunta: '{}'", userQuestion);
                aiAnswer = "Desculpe, não consegui gerar uma resposta no momento. Por favor, tente reformular sua pergunta.";
            }

        } catch (Exception e) {
            // Captura QUALQUER erro durante a chamada à API do Gemini
            logger.error("❌ Erro ao chamar a API do Gemini para a pergunta: '{}'. Erro: {}", userQuestion, e.getMessage(), e);
            // ^ Loga o erro completo no servidor para depuração

            // Define uma mensagem de erro padrão para o usuário
            aiAnswer = "Desculpe, ocorreu um erro ao tentar processar sua pergunta com a IA. Por favor, tente novamente mais tarde.";

            // IMPORTANTE: Decide se você quer retornar aqui ou continuar
            // Se retornar aqui, as fontes não serão incluídas na resposta de erro.
            List<String> sourcesOnError = Collections.emptyList(); // Ou talvez as fontes encontradas? results.stream().map(ContextItem::source).toList();
            return new QueryResponse(aiAnswer, sourcesOnError);

            // Se NÃO retornar aqui, a resposta de erro será cacheada (o que pode não ser ideal)
            // e as fontes serão incluídas. Vamos retornar logo.
        }
        List<String> sources = results.stream().map(ContextItem::source).toList();

        QueryResponse response = new QueryResponse(aiAnswer, sources);

        // Cachear resposta se houver espaço
        if (responseCache.size() < MAX_CACHE_SIZE) {
            responseCache.put(cacheKey, response);
        }

        return response;
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
        // 👇 LOG DO SCORE ORIGINAL 👇
        logger.debug("  Boosting item: '{}' (Score Original: {})",
                item.source(),
                String.format("%.3f", item.similarityScore()));

        double finalScore = item.similarityScore(); // Começa com o score original
        double additiveBoost = 0.0; // Usaremos adição para boosts secundários

        String source = item.source().toLowerCase();
        String questionLower = question.toLowerCase();
        String content = item.content().toLowerCase();

        // --- Boosts Principais (Multiplicativos, mas com cuidado) ---

        // SUPER-BOOST para documento citado diretamente (MANTÉM MULTIPLICATIVO ALTO)
        if ((questionLower.contains("catecismo") && source.contains("catecismo")) ||
                (questionLower.contains("confissão") && source.contains("confissão de fé")) ||
                (questionLower.contains("institutas") && source.contains("institutas")) ||
                ((questionLower.contains("teologia sistemática") || questionLower.contains("berkhof")) && source.contains("teologia sistemática"))) {
            finalScore *= 1.5; // Reduzido de 2.0 para 1.5 (Boost de 50%)
            logger.debug("    -> SUPER BOOST aplicado");
        }
        // BOOST PRIORITÁRIO para ESCRITURA (MANTÉM MULTIPLICATIVO)
        else if (source.contains("bíblia de genebra")) {
            finalScore *= 1.2; // Reduzido de 1.4 para 1.2 (Boost de 20%)
            logger.debug("    -> BOOST Bíblia aplicado");

            // Boost ADITIVO se contém referência
            if (content.matches(".*\\d+[:\\.]\\d+.*")) {
                additiveBoost += 0.1; // Adiciona 0.1
                logger.debug("    -> ADD Boost Ref. Direta");
            }
            // Boost ADITIVO se pergunta doutrinária
            if (isDoctrinalQuestion(questionLower)) {
                additiveBoost += 0.05; // Adiciona 0.05
                logger.debug("    -> ADD Boost Doutrina");
            }
        }
        // Boosts MULTIPLICATIVOS MENORES para documentos confessionais importantes
        else if (source.contains("confissão de fé")) {
            finalScore *= 1.1; // Boost de 10%
            logger.debug("    -> BOOST Confissão aplicado");
        } else if (source.contains("catecismo maior")) {
            finalScore *= 1.08; // Boost de 8%
            logger.debug("    -> BOOST C. Maior aplicado");
        } else if (source.contains("breve catecismo")) {
            finalScore *= 1.06; // Boost de 6%
            logger.debug("    -> BOOST C. Breve aplicado");
        } else if (source.contains("institutas") || source.contains("teologia sistemática")) {
            finalScore *= 1.05; // Boost de 5% (já coberto pelo SUPER BOOST se citado)
            logger.debug("    -> BOOST Institutas/TS aplicado");
        }

        // --- Boosts Secundários (ADITIVOS) ---

        // Boost ADITIVO se tem estrutura pergunta/resposta
        if (item.question() != null && !item.question().isEmpty()) {
            additiveBoost += 0.03; // Adiciona 0.03
            logger.debug("    -> ADD Boost P/R");
            // Boost ADITIVO extra se a pergunta bate
            if (calculateSimilarity(item.question().toLowerCase(), questionLower) > 0.7) {
                additiveBoost += 0.07; // Adiciona +0.07 (Total 0.1)
                logger.debug("    -> ADD Boost P/R Match");
            }
        }

        // Boost ADITIVO para conteúdo que cita referências bíblicas
        int biblicalReferences = countBiblicalReferences(item.content());
        if (biblicalReferences > 1) { // A partir de 2 refs
            additiveBoost += Math.min(biblicalReferences * 0.02, 0.1); // Adiciona 0.02 por ref, max 0.1
            logger.debug("    -> ADD Boost Refs Bíblicas ({})", biblicalReferences);
        }

        // --- Penalidade (Multiplicativa) ---
        if (item.content().length() < 100) {
            finalScore *= 0.9; // Penalidade de 10%
            logger.debug("    -> PENALTY Conteúdo Curto");
        }

        // --- Aplica Boost Aditivo e Garante Limites ---
        finalScore += additiveBoost;
        finalScore = Math.min(finalScore, 1.5); // COLOCA UM TETO MÁXIMO (ex: 1.5)
        finalScore = Math.max(finalScore, 0.0); // Garante que não seja negativo

        logger.debug("    -> Score Final: {}", String.format("%.3f", finalScore));

        return item.withAdjustedScore(finalScore);
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
            logger.info("  {}. {} [Score: {}] {}",
                    i + 1, type, String.format("%.3f", item.similarityScore()), item.source());
        }

        // Log da qualidade geral
        double avgScore = results.stream()
                .mapToDouble(ContextItem::similarityScore)
                .average()
                .orElse(0.0);

        String qualityEmoji = avgScore > 0.8 ? "🔥" : avgScore > 0.6 ? "✅" : "⚠️";
        logger.info("  {} Qualidade média: {}%", qualityEmoji, String.format("%.1f", avgScore * 100));
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

                // Aqui chamamos o método 'from' específico para StudyNote, que também vamos criar.
                items.add(ContextItem.from(note, score));
            } catch (Exception e) {
                logger.warn("Erro ao converter resultado raw de note: {}", e.getMessage(), e);
            }
        }
        return items;
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

    private String buildOptimizedPrompt(String question, List<ContextItem> items) {
        StringBuilder context = new StringBuilder();
        StringBuilder sourceMapping = new StringBuilder(); // Este será o nosso "mapa de rodapé"

        // Mapa para rastrear fontes únicas e atribuir um número a elas
        Map<String, Integer> sourceToNumberMap = new HashMap<>();
        int sourceCounter = 1;

        context.append("FONTES DISPONÍVEIS PARA CONSULTA:\n\n");

        // 1. Constrói o contexto e o mapa de fontes
        for (ContextItem item : items) {
            String fullSource = item.source(); // A FONTE INTACTA

            // Verifica se já vimos esta fonte
            if (!sourceToNumberMap.containsKey(fullSource)) {
                sourceToNumberMap.put(fullSource, sourceCounter);
                sourceCounter++;
            }

            int sourceNumber = sourceToNumberMap.get(fullSource);
            String sourceId = String.format("[%d]", sourceNumber); // [1], [2], etc.

            // Adiciona ao contexto que a IA vai ler
            context.append(String.format("%s\n", sourceId)); // [1]
            if (item.question() != null && !item.question().isEmpty()) {
                context.append("    Pergunta Relacionada: ").append(item.question()).append("\n");
            }
            context.append("    Conteúdo: ").append(limitContent(item.content(), 450)).append("\n\n");
        }

        // 2. Constrói o mapa de referência para o prompt
        sourceMapping.append("MAPA DE FONTES (Use isto para o rodapé):\n");
        for (Map.Entry<String, Integer> entry : sourceToNumberMap.entrySet()) {
            // Ex: [1]: Bíblia de Genebra - Romanos 8:29
            sourceMapping.append(String.format("[%d]: %s\n", entry.getValue(), entry.getKey()));
        }

        // 3. Constrói o prompt final
        return String.format("""
                Você é um assistente de pesquisa teológica focado na Tradição Reformada (Calvinista). Sua função é ajudar os usuários a encontrar informações **detalhadas e precisas** baseadas em fontes confiáveis.
                
                **TAREFA:** Responda a PERGUNTA DO USUÁRIO de forma clara, **completa**, objetiva e prestativa, baseando-se **ESTRITAMENTE** nas FONTES DISPONÍVEIS PARA CONSULTA fornecidas ([1], [2], etc.).
                
                **PRINCÍPIOS OBRIGATÓRIOS:**
                1.  **Fidelidade Absoluta às Fontes:** Sua resposta deve refletir **APENAS** o que está escrito nas fontes. Não adicione interpretações ou informações externas.
                2.  **Prioridade da Escritura:** Se as fontes bíblicas estiverem disponíveis, comece a resposta com a informação delas.
                3.  **Clareza e Profundidade:** Seja direto, use linguagem acessível, mas **não simplifique excessivamente**.
                
                **REGRAS E RESTRIÇÕES ESTRITAS:**
                * **NÃO use conhecimento externo.**
                * **NÃO dê opiniões pessoais.**
                * **NÃO seja vago.** Use os detalhes específicos das fontes.
                * **NÃO use um tom professoral.** Seja um assistente prestativo e informativo.
                
                **INSTRUÇÕES DE ESTILO E CITAÇÃO (FORMATO DE NOTAS DE RODAPÉ):**
                * **Tom:** Prestativo, informativo e preciso. Organize a resposta em parágrafos lógicos.
                * **Citação no Texto:** Ao apresentar uma informação **chave** extraída de uma fonte, adicione um **número sobrescrito** (superscript) no final da frase ou trecho, começando com ¹, depois ², ³ (ex: "A justificação é um ato da livre graça de Deus¹.").
                * **Mapeamento:** O número sobrescrito (ex: ¹) DEVE CORRESPONDER ao número da fonte no bloco "FONTES DISPONÍVEIS" (ex: [1]).
                * **Reutilização de Fontes:** Se você usar a mesma fonte (ex: [1]) várias vezes, **use o mesmo número sobrescrito** (ex: ¹) todas as vezes.
                * **Seção "Fontes Consultadas":** Ao final da sua resposta principal, adicione uma seção `---` e depois `### Fontes Consultadas`. Nesta seção, liste **cada número sobrescrito** usado no texto, seguido pela **FONTE INTACTA (COMPLETA)**, que você deve extrair do "MAPA DE FONTES".
                    * Exemplo de Rodapé:
                        ```
                        ---
                        ### Fontes Consultadas
                        ¹ Bíblia de Genebra - Romanos 8:29
                        ² Teologia Sistemática - D. As Partes da Predestinação.
                        ```
                
                **SE O CONTEXTO FOR INSUFICIENTE:**
                * Se as fontes ([1], [2]...) não responderem **diretamente**, informe isso claramente. Diga: "As fontes consultadas não fornecem uma resposta direta sobre [tópico]." Se elas abordarem um tópico *relacionado*, mencione-o brevemente, **citando as fontes com números sobrescritos** e listando-as no rodapé.
                
                ---
                FONTES DISPONÍVEIS PARA CONSULTA:
                %s
                ---
                %s
                ---
                
                **PERGUNTA DO USUÁRIOS:**
                %s
                
                **RESPOSTA:**
                (Elabore sua resposta. Adicione números sobrescritos ¹, ², ³... após as informações chave. No final, crie a seção "Fontes Consultadas" listando cada número e sua FONTE INTACTA correspondente do "MAPA DE FONTES".)
                """, context.toString(), sourceMapping.toString(), question);
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
                Work work = workRepository.findById(((Number) row[7]).longValue()).orElse(null);
                if (work == null) continue;

                ContentChunk chunk = new ContentChunk();
                chunk.setId(((Number) row[0]).longValue());
                chunk.setContent((String) row[1]);
                chunk.setQuestion((String) row[2]);
                chunk.setSectionTitle((String) row[3]);
                chunk.setChapterTitle((String) row[4]);
                chunk.setChapterNumber(row[5] != null ? ((Number) row[5]).intValue() : null);
                chunk.setSectionNumber(row[6] != null ? ((Number) row[6]).intValue() : null);
                chunk.setWork(work);

                // 👇 CORREÇÃO DOS ÍNDICES AQUI 👇
                // Agora que a query foi corrigida, os índices mudaram:
                chunk.setSubsectionTitle((String) row[8]);      // subsection_title está no índice 8
                chunk.setSubSubsectionTitle((String) row[9]);   // sub_subsection_title está no índice 9

                // O score agora está no final, no índice 10
                double score = ((Number) row[10]).doubleValue();

                String contextualSource = buildContextualSource(chunk);

                items.add(ContextItem.from(chunk, score, contextualSource));

            } catch (Exception e) {
                logger.warn("Erro ao converter resultado raw de chunk: {}", e.getMessage(), e);
            }
        }
        return items;
    }

    private String buildContextualSource(ContentChunk chunk) {
        // Usa um StringBuilder para eficiência
        StringBuilder path = new StringBuilder();

        if (chunk.getChapterTitle() != null && !chunk.getChapterTitle().isEmpty()) {
            path.append(chunk.getChapterTitle());
        }
        if (chunk.getSectionTitle() != null && !chunk.getSectionTitle().isEmpty()) {
            if (!path.isEmpty()) path.append(" > ");
            path.append(chunk.getSectionTitle());
        }
        if (chunk.getSubsectionTitle() != null && !chunk.getSubsectionTitle().isEmpty()) {
            if (!path.isEmpty()) path.append(" > ");
            path.append(chunk.getSubsectionTitle());
        }
        if (chunk.getSubSubsectionTitle() != null && !chunk.getSubSubsectionTitle().isEmpty()) {
            if (!path.isEmpty()) path.append(" > ");
            path.append(chunk.getSubSubsectionTitle());
        }

        // Se por algum motivo nenhum título foi encontrado, retorna apenas o nome da obra
        if (path.isEmpty()) {
            return chunk.getWork().getTitle();
        }

        // Retorna o nome da obra + o caminho construído
        return chunk.getWork().getTitle() + " - " + path.toString();
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

        // 3. Boost para densidade de keywords
        long totalMatches = allKeywords.stream()
                .mapToLong(keyword -> countOccurrences(contentLower, keyword.toLowerCase()))
                .sum();

        double densityBoost = 1.0 + (totalMatches * 0.05); // 5% boost por ocorrência extra
        densityBoost = Math.min(densityBoost, 2.0); // Máximo 2x boost

        // 4. Score final
        double finalScore = baseScore * densityBoost;

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

    // ===== NOVO: HYBRID SEARCH COM FTS =====
    private List<ContextItem> performHybridSearch(String userQuestion) {
        // 1. Busca vetorial (peso 60%)
        List<ContextItem> vectorResults = performVectorSearch(userQuestion);

        // 2. Busca FTS (peso 40%)
        List<ContextItem> ftsResults = performKeywordSearchFTS(userQuestion);

        // 3. ✅ DESABILITAR JPQL (está com erro PostgreSQL)
        List<ContextItem> jpqlResults = Collections.emptyList();

        // 4. Combinar Vector + FTS (perfeito!)
        return combineTwoResults(vectorResults, ftsResults, userQuestion);
    }

    /**
     * Garante que a lista final de contextos tenha uma mistura saudável de fontes
     * bíblicas e confessionais, evitando que o boosting excessivo elimine
     * documentos importantes.
     *
     * @param allRankedResults Lista de todos os resultados, já com boosts aplicados e ordenada.
     * @return Uma lista final com no máximo 5 itens, balanceada.
     */
    private List<ContextItem> ensureBalancedSources(List<ContextItem> allRankedResults) {
        // 1. Separar os resultados por tipo
        List<ContextItem> biblicalSources = allRankedResults.stream()
                .filter(item -> item.source().contains("Bíblia de Genebra"))
                .collect(Collectors.toList());

        List<ContextItem> confessionalSources = allRankedResults.stream()
                .filter(item -> !item.source().contains("Bíblia de Genebra"))
                .collect(Collectors.toList());

        // 2. Montar a lista final balanceada
        // Estratégia: 3 fontes bíblicas + 2 confessionais (se disponíveis)
        List<ContextItem> balancedList = new ArrayList<>();
        Set<String> addedKeys = new HashSet<>(); // Para evitar duplicatas

        // Adicionar as 3 melhores fontes bíblicas
        for (int i = 0; i < Math.min(3, biblicalSources.size()); i++) {
            ContextItem item = biblicalSources.get(i);
            String key = generateItemKey(item);
            if (!addedKeys.contains(key)) {
                balancedList.add(item);
                addedKeys.add(key);
            }
        }

        // Adicionar as 2 melhores fontes confessionais
        for (int i = 0; i < Math.min(2, confessionalSources.size()); i++) {
            ContextItem item = confessionalSources.get(i);
            String key = generateItemKey(item);
            if (!addedKeys.contains(key)) {
                balancedList.add(item);
                addedKeys.add(key);
            }
        }

        // 3. Reordenar a lista final pelo score para manter a relevância
        balancedList.sort(Comparator.comparing(ContextItem::similarityScore).reversed());

        logger.info("⚖️ Fontes balanceadas: {} Bíblicas, {} Confessionais.",
                (int) balancedList.stream().filter(i -> i.source().contains("Bíblia de Genebra")).count(),
                (int) balancedList.stream().filter(i -> !i.source().contains("Bíblia de Genebra")).count());

        return balancedList;
    }

    // ✅ Método simplificado para 2 tipos de busca
    private List<ContextItem> combineTwoResults(
            List<ContextItem> vectorResults,
            List<ContextItem> ftsResults,
            String userQuestion) {
        Map<String, ContextItem> combined = new HashMap<>();
        // Vector: peso 0.6
        for (ContextItem item : vectorResults) {
            String key = generateItemKey(item);
            combined.put(key, item.withAdjustedScore(item.similarityScore() * 0.6));
        }
        // FTS: peso 0.4
        for (ContextItem item : ftsResults) {
            String key = generateItemKey(item);
            if (combined.containsKey(key)) {
                ContextItem existing = combined.get(key);
                combined.put(key, existing.withAdjustedScore(
                        existing.similarityScore() + (item.similarityScore() * 0.4)
                ));
            } else {
                combined.put(key, item.withAdjustedScore(item.similarityScore() * 0.4));
            }
        }

        // Aplicar boosts Sola Scriptura
        List<ContextItem> allRankedResults = combined.values().stream()
                .map(item -> applySmartBoosts(item, userQuestion))
                .sorted(Comparator.comparing(ContextItem::similarityScore).reversed())
                .collect(Collectors.toList());

        // ===== NOVA LÓGICA DE SELEÇÃO BALANCEADA =====
        List<ContextItem> finalResults = ensureBalancedSources(allRankedResults);

        // Log otimizado
        logger.info("🔍 Resultados da busca híbrida:");
        logger.info("  🧠 Vector: {} resultados", vectorResults.size());
        logger.info("  🔤 FTS: {} resultados", ftsResults.size());
        logger.info("  🎯 Final (Balanceado): {} resultados únicos", finalResults.size());
        logger.info("--- [DEBUG] Verificando Fontes Finais ---");
        for (ContextItem item : finalResults) {
            logger.info("  -> Fonte: {}, Score: {}",
                    item.source(),
                    String.format("%.3f", item.similarityScore())
            );
        }
        logger.info("-----------------------------------------");
// ========================================================================

        return finalResults;
    }

    // ===== MÉTODOS AUXILIARES =====
    private boolean isTheologicalTerm(String term) {
        String[] theologicalTerms = {
                "deus", "cristo", "jesus", "espírito", "santo", "salvação", "graça", "fé",
                "pecado", "justificação", "santificação", "eleição", "predestinação",
                "batismo", "ceia", "igreja", "oração", "bíblia", "escritura", "trindade",
                "redenção", "regeneração", "conversão", "arrependimento", "perdão"
        };

        return Arrays.stream(theologicalTerms).anyMatch(t -> t.equals(term.toLowerCase()));
    }

    private boolean isBiblicalBook(String term) {
        String[] books = {
                "gênesis", "êxodo", "levítico", "números", "deuteronômio", "josué", "juízes", "rute",
                "samuel", "reis", "crônicas", "esdras", "neemias", "ester", "jó", "salmos", "provérbios",
                "eclesiastes", "cantares", "isaías", "jeremias", "ezequiel", "daniel", "oséias", "joel",
                "amós", "obadias", "jonas", "miquéias", "naum", "habacuque", "sofonias",
                "ageu", "zacarias", "malaquias", "mateus", "marcos", "lucas", "joão", "atos", "romanos",
                "coríntios", "gálatas", "efésios", "filipenses", "colossenses", "tessalonicenses",
                "timóteo", "tito", "filemom", "hebreus", "tiago", "pedro", "apocalipse"
        };
        return Arrays.stream(books).anyMatch(b -> term.toLowerCase().contains(b));
    }

    // ===== CONVERSORES FTS =====
    private List<ContextItem> convertFTSChunkResults(List<Object[]> results, Set<String> originalKeywords) {
        List<ContextItem> items = new ArrayList<>();

        for (Object[] row : results) {
            try {
                // Mapear campos da query FTS
                ContentChunk chunk = new ContentChunk();
                chunk.setId(((Number) row[0]).longValue());
                chunk.setContent((String) row[1]);
                chunk.setQuestion((String) row[2]);
                chunk.setSectionTitle((String) row[3]);
                chunk.setChapterTitle((String) row[4]);
                chunk.setChapterNumber(row[5] != null ? ((Number) row[5]).intValue() : null);
                chunk.setSectionNumber(row[6] != null ? ((Number) row[6]).intValue() : null);

                // Buscar Work por ID
                Long workId = ((Number) row[7]).longValue();
                Work work = workRepository.findById(workId).orElse(null);
                chunk.setWork(work);

                // Usar FTS rank como score base
                double ftsRank = ((Number) row[8]).doubleValue();

                // Combinar com nosso score de keywords
                double keywordScore = calculateEnhancedKeywordScore(chunk.getContent(), originalKeywords, "");

                // Score final: 70% FTS + 30% keyword
                double finalScore = (ftsRank * 0.7) + (keywordScore * 0.3);

                items.add(ContextItem.from(chunk, finalScore));

                logger.debug("  📄 Chunk {}: FTS={}, Keyword={}, Final={}",
                        chunk.getId(),
                        String.format("%.3f", ftsRank),
                        String.format("%.3f", keywordScore),
                        String.format("%.3f", finalScore)
                );

            } catch (Exception e) {
                logger.warn("❌ Erro ao converter resultado FTS chunk: {}", e.getMessage());
            }
        }

        return items;
    }

    private List<ContextItem> convertFTSNoteResults(List<Object[]> results, Set<String> originalKeywords) {
        List<ContextItem> items = new ArrayList<>();

        for (Object[] row : results) {
            try {
                StudyNote note = new StudyNote();
                note.setId(((Number) row[0]).longValue());
                note.setBook((String) row[1]);
                note.setStartChapter(((Number) row[2]).intValue());
                note.setStartVerse(((Number) row[3]).intValue());
                note.setEndChapter(((Number) row[4]).intValue());
                note.setEndVerse(((Number) row[5]).intValue());
                note.setNoteContent((String) row[6]);

                // FTS rank
                double ftsRank = ((Number) row[7]).doubleValue();
                double keywordScore = calculateEnhancedKeywordScore(note.getNoteContent(), originalKeywords, "");
                double finalScore = (ftsRank * 0.7) + (keywordScore * 0.3);

                items.add(ContextItem.from(note, finalScore));

                logger.debug("  📖 Nota {}: FTS={}, Keyword={}, Final={}",
                        note.getId(),
                        String.format("%.3f", ftsRank),
                        String.format("%.3f", keywordScore),
                        String.format("%.3f", finalScore)
                );

            } catch (Exception e) {
                logger.warn("❌ Erro ao converter resultado FTS note: {}", e.getMessage());
            }
        }

        return items;
    }

    // ===== COMBINADOR TRIPLO =====
    private List<ContextItem> combineTripleResults(
            List<ContextItem> vectorResults,
            List<ContextItem> ftsResults,
            List<ContextItem> jpqlResults,
            String userQuestion) {

        Map<String, ContextItem> combined = new HashMap<>();

        // Vector: peso 0.5
        for (ContextItem item : vectorResults) {
            String key = generateItemKey(item);
            combined.put(key, item.withAdjustedScore(item.similarityScore() * 0.5));
        }

        // FTS: peso 0.35
        for (ContextItem item : ftsResults) {
            String key = generateItemKey(item);
            if (combined.containsKey(key)) {
                ContextItem existing = combined.get(key);
                combined.put(key, existing.withAdjustedScore(
                        existing.similarityScore() + (item.similarityScore() * 0.35)
                ));
            } else {
                combined.put(key, item.withAdjustedScore(item.similarityScore() * 0.35));
            }
        }

        // JPQL: peso 0.15
        for (ContextItem item : jpqlResults) {
            String key = generateItemKey(item);
            if (combined.containsKey(key)) {
                ContextItem existing = combined.get(key);
                combined.put(key, existing.withAdjustedScore(
                        existing.similarityScore() + (item.similarityScore() * 0.15)
                ));
            } else {
                combined.put(key, item.withAdjustedScore(item.similarityScore() * 0.15));
            }
        }

        // Aplicar boosts e retornar top 5
        List<ContextItem> finalResults = combined.values().stream()
                .map(item -> applySmartBoosts(item, userQuestion))
                .sorted(Comparator.comparing(ContextItem::similarityScore).reversed())
                .limit(5)
                .collect(Collectors.toList());

        // Log detalhado
        logTripleSearchResults(vectorResults, ftsResults, jpqlResults, finalResults);

        return finalResults;
    }

    // ===== LOGGING MELHORADO =====
    private void logTripleSearchResults(
            List<ContextItem> vectorResults,
            List<ContextItem> ftsResults,
            List<ContextItem> jpqlResults,
            List<ContextItem> finalResults) {

        logger.info("🔍 Resultados da busca híbrida:");
        logger.info("  🧠 Vector: {} resultados", vectorResults.size());
        logger.info("  🔤 FTS: {} resultados", ftsResults.size());
        logger.info("  📝 JPQL: {} resultados", jpqlResults.size());
        logger.info("  🎯 Final: {} resultados únicos", finalResults.size());

        // Mostrar distribuição de fontes
        long biblicalNotes = finalResults.stream().filter(ContextItem::isBiblicalNote).count();
        long confessional = finalResults.stream().filter(item -> !item.isBiblicalNote()).count();

        logger.info("  📖 Fontes bíblicas: {}, ⛪ Fontes confessionais: {}", biblicalNotes, confessional);

        // Top 3 com detalhes
        for (int i = 0; i < Math.min(3, finalResults.size()); i++) {
            ContextItem item = finalResults.get(i);
            String type = item.isBiblicalNote() ? "📖" : "⛪";
            logger.info("  {}. {} [Score: {}] {}",
                    i + 1, type, String.format("%.3f", item.similarityScore()), item.source());
        }

        // Qualidade geral
        double avgScore = finalResults.stream()
                .mapToDouble(ContextItem::similarityScore)
                .average()
                .orElse(0.0);

        String qualityEmoji = avgScore > 0.8 ? "🔥" : avgScore > 0.6 ? "✅" : "⚠️";
        logger.info("  {} Qualidade média: {}%", qualityEmoji, String.format("%.1f", avgScore * 100));
    }

    private List<ContextItem> performKeywordSearchFTS(String question) {
        Set<String> keywords = extractImportantKeywords(question);

        if (keywords.isEmpty()) {
            return Collections.emptyList();
        }

        // Construir query FTS flexível
        String tsquery = buildIntelligentFTSQuery(keywords, question);

        if (tsquery.isEmpty()) {
            logger.debug("❌ Não foi possível construir query FTS");
            return Collections.emptyList();
        }

        List<ContextItem> results = new ArrayList<>();

        try {
            logger.debug("🔍 Executando FTS com query: '{}'", tsquery);

            // Buscar com FTS
            List<Object[]> chunkResults = contentChunkRepository.searchByKeywordsFTS(tsquery, 5);
            List<Object[]> noteResults = studyNoteRepository.searchByKeywordsFTS(tsquery, 5);

            logger.debug("  📄 FTS Chunks encontrados: {}", chunkResults.size());
            logger.debug("  📖 FTS Notes encontradas: {}", noteResults.size());

            // Converter resultados
            results.addAll(convertFTSChunkResults(chunkResults, keywords));
            results.addAll(convertFTSNoteResults(noteResults, keywords));

            logger.debug("✅ FTS encontrou {} resultados únicos", results.size());

            // ✅ Se não encontrou nada, tentar termo principal
            if (results.isEmpty() && !keywords.isEmpty()) {
                String mainTerm = keywords.stream()
                        .filter(k -> k.length() > 4)
                        .findFirst()
                        .orElse(keywords.iterator().next());

                logger.info("🔄 Tentando FTS com termo principal: '{}'", mainTerm);

                List<Object[]> fallbackChunks = contentChunkRepository.searchByKeywordsFTS(mainTerm, 3);
                List<Object[]> fallbackNotes = studyNoteRepository.searchByKeywordsFTS(mainTerm, 3);

                results.addAll(convertFTSChunkResults(fallbackChunks, keywords));
                results.addAll(convertFTSNoteResults(fallbackNotes, keywords));

                logger.debug("✅ Fallback FTS encontrou {} resultados", results.size());
            }

        } catch (Exception e) {
            logger.warn("❌ FTS falhou: {}", e.getMessage());
            return Collections.emptyList();
        }

        return results;
    }

    private String buildIntelligentFTSQuery(Set<String> keywords, String originalQuestion) {
        List<String> validKeywords = keywords.stream()
                .filter(k -> k.length() > 2)
                .filter(k -> !STOP_WORDS.contains(k))
                .filter(k -> !k.matches("\\d+"))
                .collect(Collectors.toList());

        if (validKeywords.isEmpty()) {
            return "";
        }

        // ✅ ESTRATÉGIA: Sempre usar OR para máxima cobertura
        List<String> searchTerms = new ArrayList<>();

        for (String keyword : validKeywords.stream().limit(5).collect(Collectors.toList())) {
            searchTerms.add(keyword);

            // Adicionar variações para termos importantes
            switch (keyword.toLowerCase()) {
                case "bíblia":
                    searchTerms.add("escritura");
                    searchTerms.add("palavra");
                    break;
                case "batismo":
                    searchTerms.add("batizar");
                    searchTerms.add("batismal");
                    break;
                case "infantil":
                    searchTerms.add("criança");
                    searchTerms.add("bebê");
                    searchTerms.add("infante");
                    break;
                case "salvação":
                    searchTerms.add("redenção");
                    searchTerms.add("justificação");
                    break;
                case "graça":
                    searchTerms.add("favor");
                    searchTerms.add("misericórdia");
                    break;
                case "fé":
                    searchTerms.add("crença");
                    searchTerms.add("confiança");
                    break;
                case "pecado":
                    searchTerms.add("transgressão");
                    searchTerms.add("iniquidade");
                    break;
            }
        }

        // ✅ Usar OR para encontrar qualquer conteúdo relevante
        String query = String.join(" | ", searchTerms.stream()
                .distinct()
                .limit(8)
                .collect(Collectors.toList()));

        logger.debug("  🔍 Query FTS flexível (OR): {}", query);
        return query;
    }

    private Optional<QueryResponse> handleDirectReferenceQuery(String userQuestion) {
        // Regex para detectar padrões como: CFW 21.1, CM 98, BC 1 (case-insensitive)
        Pattern pattern = Pattern.compile("\\b(CFW|CM|BC|TSB)\\s*(\\d+)(?:[:.](\\d+))?\\b", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(userQuestion);

        if (matcher.find()) {
            String acronym = matcher.group(1);
            int chapterOrQuestion = Integer.parseInt(matcher.group(2));
            // O parágrafo/seção é opcional
            Integer section = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : null;

            logger.info("🔍 Referência direta detectada: {} {}{}",
                    acronym.toUpperCase(), chapterOrQuestion, (section != null ? "." + section : ""));

            List<ContentChunk> results = contentChunkRepository.findDirectReference(acronym, chapterOrQuestion, section);

            if (results.isEmpty()) {
                logger.warn("⚠️ Referência direta {} não encontrada no banco de dados.", acronym.toUpperCase());
                return Optional.empty(); // Deixa a busca híbrida continuar
            }

            ContentChunk directHit = results.get(0);
            ContextItem context = ContextItem.from(directHit, 1.0); // Score máximo

            // Criamos um prompt específico para explicar APENAS este trecho
            String focusedPrompt = String.format("""
                            Você é um assistente teológico reformado. O usuário solicitou uma consulta direta a um documento confessional.
                            Sua tarefa é explicar o texto fornecido de forma clara e objetiva.
                            
                            DOCUMENTO: %s
                            REFERÊNCIA: %s %d%s
                            TEXTO ENCONTRADO:
                            "%s"
                            
                            INSTRUÇÕES:
                            1.  Comece confirmando a referência (Ex: "A Confissão de Fé de Westminster, no capítulo %d, parágrafo %d, afirma que...").
                            2.  Explique o significado teológico do texto em suas próprias palavras.
                            3.  Se aplicável, mencione brevemente a importância prática ou doutrinária deste ponto.
                            4.  Seja direto e focado exclusivamente no texto fornecido.
                            
                            EXPLICAÇÃO:
                            """,
                    directHit.getWork().getTitle(),
                    acronym.toUpperCase(), chapterOrQuestion, (section != null ? "." + section : ""),
                    directHit.getContent(),
                    chapterOrQuestion, (section != null ? section : 1)
            );

            String aiAnswer = geminiApiClient.generateContent(focusedPrompt);
            QueryResponse response = new QueryResponse(aiAnswer, List.of(context.source()));

            return Optional.of(response);
        }

        return Optional.empty(); // Nenhuma referência direta encontrada
    }
}

