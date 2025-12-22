package br.com.fereformada.api.service;

import br.com.fereformada.api.dto.*;
import br.com.fereformada.api.model.*;
import br.com.fereformada.api.repository.*;
import br.com.fereformada.api.repository.MensagemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.pgvector.PGvector;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.cache.annotation.Cacheable;

@Service
public class QueryService {

    private static final Logger logger = LoggerFactory.getLogger(QueryService.class);

    // ===== OTIMIZAÇÃO 3: CACHE =====
    private final Map<String, QueryServiceResult> responseCache = new ConcurrentHashMap<>();
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


    private final ContentChunkRepository contentChunkRepository;
    private final StudyNoteRepository studyNoteRepository;
    private final WorkRepository workRepository;
    private final GeminiApiClient geminiApiClient;
    private final MensagemRepository mensagemRepository;
    private final QueryAnalyzer queryAnalyzer;
    private final ObjectMapper objectMapper;
    private final TheologicalSynonymRepository synonymRepository;
    private final Pattern confessionalPattern;
    private final Map<Integer, String> regexGroupToAcronymMap;
    private final Map<String, String> workLookupMap;
    private final ParameterNamesModule parameterNamesModule;
    private final ConversaRepository conversaRepository;

    public QueryService(ContentChunkRepository contentChunkRepository,
                        StudyNoteRepository studyNoteRepository,
                        WorkRepository workRepository,
                        GeminiApiClient geminiApiClient,
                        MensagemRepository mensagemRepository,
                        QueryAnalyzer queryAnalyzer,
                        ObjectMapper objectMapper,
                        TheologicalSynonymRepository synonymRepository, ParameterNamesModule parameterNamesModule, ConversaRepository conversaRepository) {

        this.contentChunkRepository = contentChunkRepository;
        this.studyNoteRepository = studyNoteRepository;
        this.workRepository = workRepository;
        this.geminiApiClient = geminiApiClient;
        this.mensagemRepository = mensagemRepository;
        this.queryAnalyzer = queryAnalyzer;
        this.objectMapper = objectMapper;
        this.synonymRepository = synonymRepository;
        this.conversaRepository = conversaRepository;

        // INÍCIO DA LÓGICA DE CONSTRUÇÃO DO REGEX DINÂMICO
        List<Work> allWorks = workRepository.findAll();
        StringBuilder regexBuilder = new StringBuilder("\\b(?:");
        this.regexGroupToAcronymMap = new HashMap<>();

        // ======================================================
        // 🚀 1. LÓGICA DO MAPA DE BUSCA (Existente)
        // ======================================================
        Map<String, String> tempLookupMap = new HashMap<>();
        int groupIndex = 1;

        for (Work work : allWorks) {
            if (work.getAcronym() == null || work.getAcronym().isBlank()) continue;
            String acronym = work.getAcronym(); // Ex: "CM"
            String title = work.getTitle();     // Ex: "Catecismo Maior de Westminster"

            // 🚀 2. POPULAR O MAPA (Lógica existente)
            tempLookupMap.put(acronym.toLowerCase(), acronym);
            if (title != null && !title.isBlank()) {
                tempLookupMap.put(title.toLowerCase(), acronym);
            }

            // ======================================================
            // 🚀 3. HEURÍSTICA DINÂMICA DE "NOME COMUM" (NOVO)
            // ======================================================
            // Removemos o "hardcode". Isto é 100% dinâmico.
            if (title != null && title.contains(" de ")) {
                // Pega a parte antes do primeiro " de " (ex: "Catecismo Maior")
                String commonName = title.split(" de ", 2)[0].trim().toLowerCase();

                // Adiciona o nome comum ao mapa se ele for útil
                if (!commonName.isEmpty() && !commonName.equals(acronym.toLowerCase())) {
                    tempLookupMap.put(commonName, acronym);
                }
            }
            // ======================================================


            // --- Lógica existente do Regex (Perfeita!) ---
            String regexFragment = Pattern.quote(acronym);
            // ... (resto da lógica de 'regexBuilder.append') ...
            this.regexGroupToAcronymMap.put(groupIndex, acronym);
            groupIndex++;
        }

        // ======================================================
        // 🚀 4. ATRIBUIR O CAMPO FINAL (Lógica existente)
        // ======================================================
        // A sua lógica de ordenação (pelo mais longo primeiro) é crucial
        // e já resolve o resto do problema.
        this.workLookupMap = tempLookupMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(String::length).reversed()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (e1, e2) -> e1, LinkedHashMap::new));

        // --- Lógica restante (perfeita) ---
        regexBuilder.append(")\\b"); // Fim dos grupos de obras
        regexBuilder.append("[\\s,]*"); // Separador
        regexBuilder.append("(?:pergunta|capitulo|cap\\.?|p\\.?\\s*)?"); // Palavra-chave opcional

        // Adiciona os grupos de captura para capítulo e seção
        regexBuilder.append("(\\d+)"); // Grupo N+1 (Capítulo)
        regexBuilder.append("(?:[:.](\\d+))?"); // Grupo N+2 (Seção)

        this.confessionalPattern = Pattern.compile(regexBuilder.toString(), Pattern.CASE_INSENSITIVE);

        logger.info("Regex de busca direta 100% dinâmico construído com {} obras.", this.regexGroupToAcronymMap.size());
        // FIM DA LÓGICA DE CONSTRUÇÃO DO REGEX
        this.parameterNamesModule = parameterNamesModule;
    }


    public QueryServiceResult query(ChatRequest request) {

        String userQuestion = request.question();
        UUID chatId = request.chatId();
        logger.info("Nova pergunta recebida: '{}' (ChatID: {})", userQuestion, chatId);

        // ======================================================
        // TAREFA 2.3: ROTEAMENTO
        // ======================================================
        QueryRouterResponse route = routeQuery(userQuestion);

        List<ContextItem> results; // Lista final de fontes
        String ragQuery; // A string de busca efetiva
        List<Mensagem> chatHistory = new ArrayList<>(); // Histórico para o prompt final
        String cacheKey = normalizeQuestion(userQuestion); // Chave de cache

        // --- CAMINHO A: Pergunta COMPLEXA (Loop de Sub-Queries) ---
        if ("complex".equals(route.type())) {
            logger.info("🧠 Roteador: Pergunta complexa detectada. Executando {} sub-queries.",
                    route.queries().size());

            List<ContextItem> allComplexResults = new ArrayList<>();

            // 1. Executa uma busca híbrida para CADA sub-query
            for (String subQuery : route.queries()) {
                logger.info("  -> Executando sub-query: '{}'", subQuery);
                allComplexResults.addAll(performHybridSearch(subQuery, new MetadataFilter(null, null, null, null)));
            }

            // 2. Remove duplicatas e limita
            results = allComplexResults.stream()
                    .collect(Collectors.toMap(
                            this::generateItemKey,
                            item -> item,
                            (item1, item2) -> item1.similarityScore() > item2.similarityScore() ? item1 : item2
                    ))
                    .values().stream()
                    .sorted(Comparator.comparing(ContextItem::similarityScore).reversed())
                    .limit(15)
                    .collect(Collectors.toList());

            ragQuery = String.join(" | ", route.queries());

        } else {
            // --- CAMINHO B: Pergunta SIMPLES ---
            logger.info("🧠 Roteador: Pergunta simples detectada.");

            // --- 1. Verificação de Referência Direta (FAST-PATH 1) ---
            // 🚨 ATUALIZAÇÃO: Passamos o chatId para salvar a mensagem imediatamente se encontrar
            Optional<QueryServiceResult> directResponse = handleDirectReferenceQuery(userQuestion, chatId);
            if (directResponse.isPresent()) {
                logger.info("✅ Resposta gerada via busca direta por referência (Regex).");
                return directResponse.get(); // O resultado já contém o messageId
            }

            // --- 2. Verificação de Cache (FAST-PATH 2) ---
            if (responseCache.containsKey(cacheKey)) {
                logger.info("✅ Cache hit para: '{}'", userQuestion);
                // Nota: Se quiser que o feedback funcione em cache hits, precisaria salvar uma nova mensagem
                // duplicando o conteúdo do cache. Por simplicidade, retornamos direto.
                return responseCache.get(cacheKey);
            }

            // --- 3. Carregar Histórico ---
            if (chatId != null) {
                chatHistory = mensagemRepository.findByConversaIdOrderByCreatedAtAsc(chatId);
                logger.info("Carregado {} mensagens do histórico do chat {}", chatHistory.size(), chatId);
            }

            // --- 4. Análise de Pergunta (Híbrida: Regex + LLM) ---
            MetadataFilter filter = null;
            String foundAcronym = null;
            String userQuestionLower = userQuestion.toLowerCase();
            String lookupKeyUsed = null;

            for (Map.Entry<String, String> entry : this.workLookupMap.entrySet()) {
                String lookupKey = entry.getKey();
                if (userQuestionLower.contains(lookupKey)) {
                    foundAcronym = entry.getValue();
                    lookupKeyUsed = lookupKey;
                    break;
                }
            }

            if (foundAcronym != null) {
                logger.info("🧠 Filtro de acrônimo extraído via Busca Rápida: {}", foundAcronym.toUpperCase());
                filter = new MetadataFilter(foundAcronym.toUpperCase(), null, null, null);
            }

            if (filter == null) {
                logger.info("Nenhum acrônimo rápido encontrado. Usando QueryAnalyzer (LLM)...");
                filter = queryAnalyzer.extractFilters(userQuestion, Collections.emptyList());
            }

            // --- 5. Lógica de Hy-DE e Limpeza de Query ---
            if (!filter.isEmpty()) {
                logger.info("🧠 Filtros de metadados extraídos: {}", filter);
                if (lookupKeyUsed != null) {
                    String cleanQuery = userQuestion.replaceAll("(?i)" + Pattern.quote(lookupKeyUsed), "").trim();
                    cleanQuery = cleanQuery.replaceAll("\\s+", " ");
                    ragQuery = cleanQuery.isEmpty() ? userQuestion : cleanQuery;
                    logger.info("🧠 Query de busca limpa (pós-filtro): '{}'", ragQuery);
                } else {
                    ragQuery = userQuestion;
                }
            } else {
                logger.info("Buscando por (busca semântica pura): '{}'. Aplicando Hy-DE...", userQuestion);
                ragQuery = generateHypotheticalDocument(userQuestion);
                if (ragQuery == null || ragQuery.isBlank()) {
                    logger.warn("⚠️ Falha ao gerar documento hipotético (Hy-DE). Usando a pergunta original.");
                    ragQuery = userQuestion;
                } else {
                    logger.info("🧠 Pergunta transformada (Hy-DE): '{}'", ragQuery.substring(0, Math.min(60, ragQuery.length())) + "...");
                }
            }

            // --- 6. Lógica de Follow-up ---
            Optional<Integer> sourceNum = extractSourceNumberFromQuestion(userQuestion);
            if (sourceNum.isPresent()) {
                logger.info("Detectada pergunta de acompanhamento para a fonte número {}", sourceNum.get());
                Optional<String> extractedSource = extractSourceFromHistory(chatHistory, sourceNum.get());

                if (extractedSource.isPresent()) {
                    String sourceName = extractedSource.get();
                    logger.info("Fonte extraída do histórico: '{}'", sourceName);

                    // 🚨 ATUALIZAÇÃO: Passamos chatId aqui também
                    Optional<QueryServiceResult> directFollowUp = handleDirectReferenceQuery(sourceName, chatId);

                    if (directFollowUp.isPresent()) {
                        logger.info("Respondendo ao acompanhamento com busca de referência direta.");
                        return directFollowUp.get();
                    } else {
                        logger.warn("Busca direta falhou para '{}', usando busca RAG padrão.", sourceName);
                        ragQuery = sourceName;
                    }
                } else {
                    logger.warn("Não foi possível extrair o nome da fonte {} do histórico.", sourceNum.get());
                }
            }

            // --- 7. Busca Híbrida (Simples) ---
            results = performHybridSearch(ragQuery, filter);
        }

        // ======================================================
        // FIM DO ROTEAMENTO
        // ======================================================

        // --- 8. Verificação de Resultados ---
        if (results.isEmpty()) {
            String msg = "Não encontrei informações relevantes nas fontes catalogadas. Tente reformular sua pergunta ou ser mais específico.";
            // 💾 SALVAMENTO: Salva a resposta de "não encontrado" e retorna o ID
            UUID msgId = saveAiMessage(chatId, msg, Collections.emptyList());
            return new QueryServiceResult(msg, Collections.emptyList(), msgId);
        }

        // --- 9. Log de Qualidade ---
        double avgScore = results.stream()
                .mapToDouble(ContextItem::similarityScore)
                .average()
                .orElse(0.0);

        if (avgScore < 0.6) {
            logger.warn("⚠️ Baixa relevância média ({}) para: '{}' (Query RAG: '{}')",
                    String.format("%.2f", avgScore), userQuestion, ragQuery);
        }

        logger.info("📊 Construindo resposta com {} fontes (relevância média: {})",
                results.size(), String.format("%.2f", avgScore));

        // --- 10. Construção do Prompt e Chamada da IA ---
        String prompt = buildOptimizedPrompt(userQuestion, results, chatHistory);
        String aiAnswer;
        try {
            aiAnswer = geminiApiClient.generateContent(prompt, chatHistory, userQuestion);
            if (aiAnswer == null || aiAnswer.trim().isEmpty()) {
                aiAnswer = "Desculpe, não consegui gerar uma resposta. Tente novamente.";
            }
        } catch (Exception e) {
            logger.error("❌ Erro API Gemini: {}", e.getMessage());
            String erroMsg = "Desculpe, ocorreu um erro ao tentar processar sua pergunta com a IA. Por favor, tente novamente mais tarde.";
            // 💾 SALVAMENTO: Salva a mensagem de erro
            UUID msgId = saveAiMessage(chatId, erroMsg, Collections.emptyList());
            return new QueryServiceResult(erroMsg, Collections.emptyList(), msgId);
        }

        // --- 11. Pós-processamento para criar referências ---
        List<SourceReference> references = new ArrayList<>();
        Map<String, Integer> sourceToNumberMap = new HashMap<>();
        int sourceCounter = 1;

        for (ContextItem item : results) {
            String fullSource = item.source();
            if (!sourceToNumberMap.containsKey(fullSource)) {
                sourceToNumberMap.put(fullSource, sourceCounter++);
            }
            int sourceNumber = sourceToNumberMap.get(fullSource);

            SourceReference ref = SourceReference.builder()
                    .number(sourceNumber)
                    .text(fullSource)
                    .preview(limitContent(item.content(), 200))
                    .sourceId(item.originalId())
                    .type(item.sourceType())
                    .label(item.referenceLabel())
                    .metadata(item.metadata())
                    .build();

            references.add(ref);
        }

        // =================================================================
        // 💾 LÓGICA DE SALVAMENTO FINAL (INTEGRADA)
        // =================================================================
        // Chamamos o método auxiliar para salvar e recuperar o ID
        UUID savedMessageId = saveAiMessage(chatId, aiAnswer, references);

        // --- 12. Construção da Resposta (COM MESSAGE ID) ---
        QueryServiceResult response = new QueryServiceResult(aiAnswer, references, savedMessageId);

        // Cache (opcional)
        if ("simple".equals(route.type()) && responseCache.size() < MAX_CACHE_SIZE) {
            responseCache.put(cacheKey, response);
        }

        return response;
    }

    /**
     * TAREFA 2.2 (Hy-DE): Gera uma "resposta hipotética" para perguntas vagas
     * para melhorar a qualidade da busca vetorial.
     *
     * @param userQuestion A pergunta vaga do usuário (ex: "O que é a graça?")
     * @return Uma resposta densa e hipotética (ex: "A graça é o favor imerecido de Deus...")
     */
    private String generateHypotheticalDocument(String userQuestion) {
        String hydePrompt = String.format("""
                Gere um parágrafo curto e denso que responda diretamente à pergunta: [%s].
                Comece a resposta diretamente, sem introduções.
                """, userQuestion);

        try {
            // Chamamos o Gemini com um prompt simples, sem histórico
            return geminiApiClient.generateContent(hydePrompt, Collections.emptyList(), userQuestion);
        } catch (Exception e) {
            logger.error("❌ Erro ao gerar Hy-DE para a pergunta '{}': {}", userQuestion, e.getMessage(), e);
            return null; // Retorna nulo para que o método 'query()' possa fazer o fallback
        }
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

    private Set<String> extractImportantKeywords(String question, Map<String, List<String>> synonymMap) {
        Set<String> keywords = new HashSet<>();
        String[] words = question.toLowerCase()
                .replaceAll("[?!.,;:'\"]", "") // 🚀 CORREÇÃO 1: Remove aspas aqui diretamente
                .split("\\s+");

        for (String word : words) {
            if (word.length() > 2 && !STOP_WORDS.contains(word)) {
                keywords.add(word);

                if (synonymMap.containsKey(word)) {
                    List<String> synonyms = synonymMap.get(word);
                    // 🚀 CORREÇÃO 2: Limpa aspas dos sinônimos também
                    keywords.addAll(synonyms.stream()
                            .map(s -> s.replaceAll("['\"]", ""))
                            .filter(s -> s.length() > 2)
                            .limit(3)
                            .collect(Collectors.toList()));
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

        logger.debug("  Boosting item: '{}' (Score Original: {})",
                item.source(),
                String.format("%.3f", item.similarityScore()));

        double finalScore = item.similarityScore();
        double additiveBoost = 0.0; // Boosts secundários
        String questionLower = question.toLowerCase();

        // --- 1. LÓGICA DE BOOST PRINCIPAL (Refatorada) ---

        // (Opção 1 que discutimos)
        if (item.isBiblicalNote()) {
            // É uma Nota de Estudo (Bíblia), aplicar boost máximo fixo.
            finalScore *= 1.30; // Boost de 30%
            logger.debug("    -> BOOST Bíblia (Fixo) aplicado");

            // --- COMENTÁRIO PARA IMPLEMENTAÇÃO FUTURA (Como solicitado) ---
            // Se um dia você quiser que as Notas de Estudo tenham prioridades dinâmicas:
            // 1. Adicione 'boost_priority' na tabela 'study_notes'.
            // 2. Adicione o dropdown no 'StudyNoteFormModal.jsx'.
            // 3. Modifique o 'ContextItem.from(StudyNote...)' para carregar 'note.getBoostPriority()'.
            // 4. Substitua o boost fixo (1.30) pela lógica de 'switch' abaixo,
            //    lendo o 'item.boostPriority()'.
            // --- FIM DO COMENTÁRIO ---

        } else {
            // (Opção 2) É um ContentChunk (Obra), ler a prioridade do banco.
            Integer priority = item.boostPriority(); // Lê o valor (ex: 2)

            if (priority != null) {
                switch (priority) {
                    case 3: // 3 = Nível Bíblia (se você definir)
                        finalScore *= 1.30;
                        logger.debug("    -> BOOST Dinâmico (Nível 3) aplicado");
                        break;
                    case 2: // 2 = Essencial (CFW, Catecismos)
                        finalScore *= 1.20; // Boost de 20%
                        logger.debug("    -> BOOST Dinâmico (Nível 2) aplicado");
                        break;
                    case 1: // 1 = Prioritário (Institutas, Teologia)
                        finalScore *= 1.10; // Boost de 10%
                        logger.debug("    -> BOOST Dinâmico (Nível 1) aplicado");
                        break;
                    case 0: // 0 = Normal (Padrão)
                    default:
                        // Sem boost multiplicativo
                        logger.debug("    -> BOOST Dinâmico (Nível 0) aplicado");
                        break;
                }
            }

            // SUPER-BOOST ADICIONAL (Mantido, mas agora dinâmico)
            // Se a *pergunta* cita o *tipo* da obra (ex: "catecismo")
            String workType = item.workType(); // Lê o tipo (ex: "CATECISMO")
            if (workType != null && !workType.isEmpty() &&
                    questionLower.contains(workType.toLowerCase().split("_")[0])) { // "NOTAS_BIBLICAS" -> "notas"

                finalScore *= 1.2; // Boost extra de 20% por citação de tipo
                logger.debug("    -> SUPER BOOST (Tipo de Obra) aplicado");
            }
        }

        // --- 2. Boosts Secundários (ADITIVOS) (Lógica mantida do seu original) ---

        // Boost ADITIVO se tem estrutura pergunta/resposta
        if (item.hasQuestion()) { // Usando o método helper do ContextItem
            additiveBoost += 0.03;
            logger.debug("    -> ADD Boost P/R");
            if (calculateSimilarity(item.question().toLowerCase(), questionLower) > 0.7) {
                additiveBoost += 0.07;
                logger.debug("    -> ADD Boost P/R Match");
            }
        }

        // Boost ADITIVO para conteúdo que cita referências bíblicas
        int biblicalReferences = countBiblicalReferences(item.content());
        if (biblicalReferences > 1) {
            additiveBoost += Math.min(biblicalReferences * 0.02, 0.1);
            logger.debug("    -> ADD Boost Refs Bíblicas ({})", biblicalReferences);
        }

        // --- 3. Penalidade (Multiplicativa) (Lógica mantida do seu original) ---
        boolean isShort = item.content().length() < 100;
        boolean isImmuneToPenalty = item.isBiblicalNote() ||
                (item.workType() != null && item.workType().contains("CATECISMO"));

        if (isShort && !isImmuneToPenalty) { // <--- Adicione esta verificação
            finalScore *= 0.9;
            logger.debug("    -> PENALTY Conteúdo Curto");
        }

        // --- 4. Aplica Boost Aditivo e Garante Limites (Lógica mantida do seu original) ---
        finalScore += additiveBoost;
        finalScore = Math.min(finalScore, 1.5); // TETO MÁXIMO
        finalScore = Math.max(finalScore, 0.0); // Chão

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
    private List<ContextItem> performVectorSearch(String userQuestion, MetadataFilter filter) {
        // Usar cache de embeddings
        PGvector questionVector = getOrComputeEmbedding(userQuestion);

        if (questionVector == null) {
            logger.warn("⚠️ Não foi possível gerar embedding para a pergunta");
            return Collections.emptyList();
        }

        // ======================================================
        // 🚀 LÓGICA DE BUSCA DUPLA
        // ======================================================

        // 1. Buscar Chunks por CONTENT vector
        List<Object[]> rawContentResults = contentChunkRepository.findSimilarChunksRaw(
                questionVector.toString(),
                5, // Aumente para 10 se quiser mais candidatos
                filter.obraAcronimo(),
                filter.capitulo(),
                filter.secaoOuVersiculo()
        );
        List<ContextItem> contentItems = convertRawChunkResultsToContextItems(rawContentResults);

        // 2. Buscar Chunks por QUESTION vector (Nova query)
        List<Object[]> rawQuestionResults = contentChunkRepository.findSimilarChunksByQuestionVector(
                questionVector.toString(),
                5, // Aumente para 10 se quiser mais candidatos
                filter.obraAcronimo(),
                filter.capitulo(),
                filter.secaoOuVersiculo()
        );
        List<ContextItem> questionItems = convertRawChunkResultsToContextItems(rawQuestionResults);

        // 3. Buscar Notas (Lógica existente)
        List<Object[]> rawNoteResults = studyNoteRepository.findSimilarNotesRaw(
                questionVector.toString(),
                5, // Aumente para 10
                filter.livroBiblico(),
                filter.capitulo(),
                filter.secaoOuVersiculo()
        );
        List<ContextItem> noteItems = convertRawNoteResultsToContextItems(rawNoteResults);

        // 4. Combinar e retornar todos
        List<ContextItem> combinedItems = new ArrayList<>();
        combinedItems.addAll(contentItems);   // Resultados do Vetor 1
        combinedItems.addAll(questionItems);  // Resultados do Vetor 2
        combinedItems.addAll(noteItems);      // Resultados das Notas
        // ======================================================

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

    private String buildOptimizedPrompt(String question, List<ContextItem> items, List<Mensagem> chatHistory) {
        StringBuilder context = new StringBuilder();

        // Set apenas para garantir que não enviamos texto duplicado para a IA ler
        Set<String> processedSources = new HashSet<>();

        context.append("### CONTEXTO (FONTES DISPONÍVEIS) ###\n\n");

        for (ContextItem item : items) {
            String fullSource = item.source();

            // Se já adicionamos este conteúdo, pula para economizar tokens
            if (processedSources.contains(fullSource)) {
                continue;
            }
            processedSources.add(fullSource);

            // Apenas jogamos o conteúdo lá, sem nos preocupar com IDs [1], [2]...
            context.append("--- Trecho de Fonte ---\n");
            if (item.question() != null && !item.question().isEmpty()) {
                context.append("Tópico: ").append(item.question()).append("\n");
            }
            context.append("Conteúdo: ").append(limitContent(item.content(), 450)).append("\n\n");
        }

        // Prompt focado em TEXTO LIMPO
        return String.format("""
            Você é um assistente de pesquisa teológica focado na Tradição Reformada (Calvinista).
            
            **OBJETIVO:** Responda a PERGUNTA DO USUÁRIO com um texto fluido, bem redigido e natural, baseando-se **ESTRITAMENTE** nas informações fornecidas no CONTEXTO.
            
            **REGRAS DE FORMATAÇÃO (CRÍTICAS):**
            1.  **SEM MARCAÇÕES:** NÃO use números sobrescritos (¹), colchetes ([1]), ou qualquer tipo de referência numérica no meio do texto.
            2.  **SEM RODAPÉ:** NÃO crie lista de fontes ou bibliografia no final.
            3.  **ESTILO:** Escreva como um artigo explicativo ou uma resposta direta de chat. O texto deve ser limpo e agradável de ler.
            
            **DIRETRIZES DE CONTEÚDO:**
            *   Use apenas o conhecimento presente no CONTEXTO abaixo.
            *   Se houver versículos bíblicos no contexto, integre-os naturalmente à explicação.
            *   Se o contexto não tiver a resposta, diga: "As fontes disponíveis não abordam este tópico específico."
            
            %s
            
            ---
            **PERGUNTA DO USUÁRIO:**
            %s
            
            **RESPOSTA:**
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
                chunk.setWork(work); // Seta a Work
                chunk.setSubsectionTitle((String) row[8]);
                chunk.setSubSubsectionTitle((String) row[9]);

                double score = ((Number) row[10]).doubleValue();
                String contextualSource = buildContextualSource(chunk);


                // Passamos o objeto 'work' para que o ContextItem
                // possa ler o .getBoostPriority() e .getType()
                items.add(ContextItem.from(chunk, score, contextualSource, work));

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
    private List<ContextItem> performHybridSearch(String userQuestion, MetadataFilter filter) {
        // 1. Busca vetorial (peso 60%) - AGORA PASSA O FILTRO
        List<ContextItem> vectorResults = performVectorSearch(userQuestion, filter);

        // 2. Busca FTS (peso 40%) - AGORA PASSA O FILTRO
        List<ContextItem> ftsResults = performKeywordSearchFTS(userQuestion, filter);

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
        // ======================================================
        // MUDANÇA PRINCIPAL AQUI
        // ======================================================
        // Usamos a referência de método 'isBiblicalNote'
        // ANTES: item.source().contains("Bíblia de Genebra")
        List<ContextItem> biblicalSources = allRankedResults.stream()
                .filter(ContextItem::isBiblicalNote) // <-- MUITO MAIS LIMPO E ROBUSTO
                .collect(Collectors.toList());

        List<ContextItem> confessionalSources = allRankedResults.stream()
                .filter(item -> !item.isBiblicalNote()) // <-- MUITO MAIS LIMPO E ROBUSTO
                .collect(Collectors.toList());
        // ======================================================

        // 2. Montar a lista final balanceada (Sua lógica original está ótima)
        List<ContextItem> balancedList = new ArrayList<>();
        Set<String> addedKeys = new HashSet<>();

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

        // 3. Reordenar a lista final pelo score
        balancedList.sort(Comparator.comparing(ContextItem::similarityScore).reversed());

        logger.info("⚖️ Fontes balanceadas: {} Bíblicas, {} Confessionais.",
                (int) balancedList.stream().filter(ContextItem::isBiblicalNote).count(),
                (int) balancedList.stream().filter(i -> !i.isBiblicalNote()).count());

        return balancedList;
    }

    // ✅ Método simplificado para 2 tipos de busca
    private List<ContextItem> combineTwoResults(
            List<ContextItem> vectorResults,
            List<ContextItem> ftsResults,
            String userQuestion) {

        Map<String, ContextItem> combined = new HashMap<>();
        // ... (Sua lógica de combinar vectorResults e ftsResults) ...
        for (ContextItem item : vectorResults) {
            String key = generateItemKey(item);
            combined.put(key, item.withAdjustedScore(item.similarityScore() * 0.6));
        }
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

        // 1. CHAMA O NOVO 'applySmartBoosts'
        List<ContextItem> allRankedResults = combined.values().stream()
                .map(item -> applySmartBoosts(item, userQuestion)) // <-- Chamada 1 (Corrigida)
                .sorted(Comparator.comparing(ContextItem::similarityScore).reversed())
                .collect(Collectors.toList());

        // 2. CHAMA O NOVO 'ensureBalancedSources'
        List<ContextItem> finalResults = ensureBalancedSources(allRankedResults); // <-- Chamada 2 (Corrigida)

        // ... (Sua lógica de Logs) ...
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
                ContentChunk chunk = new ContentChunk();
                chunk.setId(((Number) row[0]).longValue());
                chunk.setContent((String) row[1]);
                chunk.setQuestion((String) row[2]);
                chunk.setSectionTitle((String) row[3]);
                chunk.setChapterTitle((String) row[4]);
                chunk.setChapterNumber(row[5] != null ? ((Number) row[5]).intValue() : null);
                chunk.setSectionNumber(row[6] != null ? ((Number) row[6]).intValue() : null);

                Long workId = ((Number) row[7]).longValue();
                Work work = workRepository.findById(workId).orElse(null);

                if (work == null) {
                    logger.warn("Work não encontrada para ID: {} no FTS", workId);
                    continue;
                }
                chunk.setWork(work); // Seta a Work

                double ftsRank = ((Number) row[8]).doubleValue();
                double keywordScore = calculateEnhancedKeywordScore(chunk.getContent(), originalKeywords, "");
                double finalScore = (ftsRank * 0.7) + (keywordScore * 0.3);


                // Passamos o objeto 'work' para que o ContextItem
                // possa ler o .getBoostPriority() e .getType()
                items.add(ContextItem.from(chunk, finalScore, buildContextualSource(chunk), work));

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

    private List<ContextItem> performKeywordSearchFTS(String question, MetadataFilter filter) {

        Map<String, List<String>> synonymMap = getSynonymMap();

        Set<String> keywords = extractImportantKeywords(question, synonymMap); // Passa o mapa

        if (keywords.isEmpty()) {
            return Collections.emptyList();
        }

        String tsquery = buildIntelligentFTSQuery(keywords, question, synonymMap); // Passa o mapa

        if (tsquery.isEmpty()) {
            logger.debug("❌ Não foi possível construir query FTS");
            return Collections.emptyList();
        }

        List<ContextItem> results = new ArrayList<>();

        try {
            logger.debug("🔍 Executando FTS com query: '{}' E FILTRO: {}", tsquery, filter);

            // Buscar com FTS - APLICANDO FILTROS
            List<Object[]> chunkResults = contentChunkRepository.searchByKeywordsFTS(
                    tsquery,
                    5,
                    filter.obraAcronimo(),      // NOVO
                    filter.capitulo(),        // NOVO
                    filter.secaoOuVersiculo() // NOVO
            );
            List<Object[]> noteResults = studyNoteRepository.searchByKeywordsFTS(
                    tsquery,
                    5,
                    filter.livroBiblico(),    // NOVO
                    filter.capitulo(),        // NOVO
                    filter.secaoOuVersiculo() // NOVO
            );

            logger.debug("  📄 FTS Chunks encontrados: {}", chunkResults.size());
            logger.debug("  📖 FTS Notes encontradas: {}", noteResults.size());

            // Converter resultados
            results.addAll(convertFTSChunkResults(chunkResults, keywords));
            results.addAll(convertFTSNoteResults(noteResults, keywords));

            logger.debug("✅ FTS encontrou {} resultados únicos", results.size());

            // ✅ Se não encontrou nada, tentar termo principal - APLICANDO FILTROS
            if (results.isEmpty() && !keywords.isEmpty()) {
                String mainTerm = keywords.stream()
                        .filter(k -> k.length() > 4)
                        .findFirst()
                        .orElse(keywords.iterator().next());

                logger.info("🔄 Tentando FTS com termo principal: '{}' E FILTRO: {}", mainTerm, filter);

                List<Object[]> fallbackChunks = contentChunkRepository.searchByKeywordsFTS(
                        mainTerm,
                        3,
                        filter.obraAcronimo(),      // NOVO
                        filter.capitulo(),        // NOVO
                        filter.secaoOuVersiculo() // NOVO
                );
                List<Object[]> fallbackNotes = studyNoteRepository.searchByKeywordsFTS(
                        mainTerm,
                        3,
                        filter.livroBiblico(),    // NOVO
                        filter.capitulo(),        // NOVO
                        filter.secaoOuVersiculo() // NOVO
                );

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

    private String buildIntelligentFTSQuery(Set<String> keywords, String originalQuestion, Map<String, List<String>> synonymMap) {
        List<String> validKeywords = keywords.stream()
                .filter(k -> k.length() > 2)
                .filter(k -> !STOP_WORDS.contains(k))
                .filter(k -> !k.matches("\\d+"))
                .collect(Collectors.toList());

        if (validKeywords.isEmpty()) {
            return "";
        }

        List<String> searchTerms = new ArrayList<>();

        // O mapa agora vem como parâmetro
        // Map<String, List<String>> synonymMap = getSynonymMap(); // <-- LINHA REMOVIDA

        for (String keyword : validKeywords.stream().limit(5).collect(Collectors.toList())) {
            searchTerms.add(keyword);

            if (synonymMap.containsKey(keyword.toLowerCase())) { // Usa o mapa do parâmetro
                List<String> synonyms = synonymMap.get(keyword.toLowerCase());
                searchTerms.addAll(synonyms);
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

    private Optional<QueryServiceResult> handleDirectReferenceQuery(String userQuestion, UUID chatId) { // <--- RECEBE CHAT ID

        Matcher confessionalMatcher = this.confessionalPattern.matcher(userQuestion);

        // Pattern bíblico mantido
        Pattern biblicalPattern = Pattern.compile(
                "(?:\\b(BG|Bíblia de Genebra)\\s*-?\\s*)?" +
                        "((?:\\d+\\s+)?[A-Za-zÀ-ÿ]+(?:\\s+[A-Za-zÀ-ÿ]+)*)" +
                        "\\s+" +
                        "(\\d+)" +
                        "[:.](\\d+(?:-\\d+)?)",
                Pattern.CASE_INSENSITIVE
        );
        Matcher biblicalMatcher = biblicalPattern.matcher(userQuestion);

        // --- BLOCO 1: Busca Confessional ---
        if (confessionalMatcher.find()) {
            String acronym = null;
            for (int i = 1; i <= this.regexGroupToAcronymMap.size(); i++) {
                if (i <= confessionalMatcher.groupCount() && confessionalMatcher.group(i) != null) {
                    acronym = this.regexGroupToAcronymMap.get(i);
                    break;
                }
            }
            if (acronym == null) return Optional.empty();

            int chapterGroupIndex = this.regexGroupToAcronymMap.size() + 1;
            int sectionGroupIndex = this.regexGroupToAcronymMap.size() + 2;

            if (confessionalMatcher.groupCount() < chapterGroupIndex) {
                return Optional.empty();
            }

            String chapterStr = confessionalMatcher.group(chapterGroupIndex);
            if (chapterStr == null) return Optional.empty();

            int chapterOrQuestion = Integer.parseInt(chapterStr);
            Integer section = null;
            if (confessionalMatcher.groupCount() >= sectionGroupIndex && confessionalMatcher.group(sectionGroupIndex) != null) {
                section = Integer.parseInt(confessionalMatcher.group(sectionGroupIndex));
            }

            List<ChunkProjection> results = contentChunkRepository.findDirectReferenceProjection(
                    acronym, chapterOrQuestion, section
            );

            if (results.isEmpty()) return Optional.empty();

            ChunkProjection directHit = results.get(0);
            Work work = workRepository.findById(directHit.workId()).orElseThrow();

            ContentChunk chunkShell = new ContentChunk();
            chunkShell.setId(directHit.id());
            chunkShell.setContent(directHit.content());
            chunkShell.setQuestion(directHit.question());
            chunkShell.setWork(work);
            chunkShell.setChapterNumber(chapterOrQuestion);
            chunkShell.setSectionNumber(section);

            ContextItem context = ContextItem.from(chunkShell, 1.0, buildContextualSource(directHit), work);
            String referenceString = String.format("%s %d%s", acronym.toUpperCase(), chapterOrQuestion, (section != null ? "." + section : ""));

            String focusedPrompt = String.format("""
                    Você é um assistente teológico reformado... (Prompt mantido)
                    DOCUMENTO: %s
                    REFERÊNCIA: %s
                    TEXTO ENCONTRADO:
                    "%s"
                    ...
                    """, work.getTitle(), referenceString, directHit.content());

            String aiAnswer = geminiApiClient.generateContent(focusedPrompt, Collections.emptyList(), userQuestion);

            SourceReference ref = SourceReference.builder()
                    .number(1)
                    .text(context.source())
                    .preview(context.content())
                    .sourceId(context.originalId())
                    .type(context.sourceType())
                    .label(context.referenceLabel())
                    .metadata(context.metadata())
                    .build();

            // 💾 SALVA E RETORNA COM ID
            UUID messageId = saveAiMessage(chatId, aiAnswer, List.of(ref));
            return Optional.of(new QueryServiceResult(aiAnswer, List.of(ref), messageId));
        }

        // --- BLOCO 2: Busca Bíblica ---
        else if (biblicalMatcher.find()) {
            String book = normalizeBookName(biblicalMatcher.group(2).trim());
            int chapter = Integer.parseInt(biblicalMatcher.group(3));
            int verse = Integer.parseInt(biblicalMatcher.group(4).split("-")[0]);

            List<StudyNoteProjection> results = studyNoteRepository.findByBiblicalReference(book, chapter, verse);
            if (results.isEmpty()) return Optional.empty();

            StudyNoteProjection directHit = results.get(0);
            StudyNote noteShell = new StudyNote();
            noteShell.setId(directHit.id());
            noteShell.setBook(directHit.book());
            noteShell.setStartChapter(directHit.startChapter());
            noteShell.setStartVerse(directHit.startVerse());
            noteShell.setEndChapter(directHit.endChapter());
            noteShell.setEndVerse(directHit.endVerse());
            noteShell.setNoteContent(directHit.noteContent());

            ContextItem context = ContextItem.from(noteShell, 1.0);

            String focusedPrompt = String.format("""
                    Você é um assistente teológico reformado... (Prompt mantido)
                    DOCUMENTO: %s
                    REFERÊNCIA BÍBLICA: %s %d:%d
                    NOTA DE ESTUDO ENCONTRADA:
                    "%s"
                    ...
                    """, context.source(), book, chapter, verse, directHit.noteContent());

            String aiAnswer = geminiApiClient.generateContent(focusedPrompt, Collections.emptyList(), userQuestion);

            SourceReference ref = SourceReference.builder()
                    .number(1)
                    .text(context.source())
                    .preview(context.content())
                    .sourceId(context.originalId())
                    .type(context.sourceType())
                    .label(context.referenceLabel())
                    .metadata(context.metadata())
                    .build();

            // 💾 SALVA E RETORNA COM ID
            UUID messageId = saveAiMessage(chatId, aiAnswer, List.of(ref));
            return Optional.of(new QueryServiceResult(aiAnswer, List.of(ref), messageId));
        }

        return Optional.empty();
    }

    private String buildContextualSource(ChunkProjection chunk) {
        // Lógica idêntica, mas usando os métodos da projeção (ex: chunk.chapterTitle())
        StringBuilder path = new StringBuilder();

        if (chunk.chapterTitle() != null && !chunk.chapterTitle().isEmpty()) {
            path.append(chunk.chapterTitle());
        }
        if (chunk.sectionTitle() != null && !chunk.sectionTitle().isEmpty()) {
            if (!path.isEmpty()) path.append(" > ");
            path.append(chunk.sectionTitle());
        }
        if (chunk.subsectionTitle() != null && !chunk.subsectionTitle().isEmpty()) {
            if (!path.isEmpty()) path.append(" > ");
            path.append(chunk.subsectionTitle());
        }
        if (chunk.subSubsectionTitle() != null && !chunk.subSubsectionTitle().isEmpty()) {
            if (!path.isEmpty()) path.append(" > ");
            path.append(chunk.subSubsectionTitle());
        }

        if (path.isEmpty()) {
            return chunk.workTitle();
        }

        return chunk.workTitle() + " - " + path.toString();
    }

    private String normalizeBookName(String rawBookName) {
        if (rawBookName == null || rawBookName.isEmpty()) {
            return rawBookName;
        }

        // 1. Remover espaços extras
        String normalized = rawBookName.trim().replaceAll("\\s+", " ");

        // 2. Remover números duplicados no início (ex: "1 1 Coríntios" -> "1 Coríntios")
        normalized = normalized.replaceAll("^(\\d+)\\s+\\1\\s+", "$1 ");

        // 3. Corrigir casos onde o número foi separado (ex: "1Coríntios" -> "1 Coríntios")
        normalized = normalized.replaceAll("^(\\d+)([A-Za-zÀ-ÿ])", "$1 $2");

        logger.debug("📝 Normalização: '{}' -> '{}'", rawBookName, normalized);

        return normalized;
    }

    private Optional<Integer> extractSourceNumberFromQuestion(String userQuestion) {
        String qLower = userQuestion.toLowerCase();

        // Procura por "fonte 1", "número 1", "sobre a 1", "e a 1?" etc.
        // O grupo (\\d+) captura o número.
        Pattern pattern = Pattern.compile("(?:fonte|número|sobre|e a)\\s*(\\d+)");
        Matcher matcher = pattern.matcher(qLower);

        if (matcher.find()) {
            try {
                return Optional.of(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private Optional<String> extractSourceFromHistory(List<Mensagem> chatHistory, int sourceNumber) {
        if (chatHistory.size() < 2) { // Precisa de P + R
            return Optional.empty();
        }

        Mensagem lastAiResponse = null;
        // Itera para trás para encontrar a última resposta do 'assistant'
        for (int i = chatHistory.size() - 2; i >= 0; i--) {
            if (chatHistory.get(i).getRole().equals("assistant")) {
                lastAiResponse = chatHistory.get(i);
                break;
            }
        }

        if (lastAiResponse == null) {
            return Optional.empty(); // Nenhuma resposta de IA encontrada no histórico
        }

        String aiContent = lastAiResponse.getContent();
        int sourcesIndex = aiContent.indexOf("Fontes Consultadas");
        if (sourcesIndex == -1) {
            return Optional.empty(); // IA não listou fontes
        }

        String sourcesBlock = aiContent.substring(sourcesIndex);

        // --- CORREÇÃO DO REGEX ABAIXO ---

        // Mapeia os números para os caracteres sobrescritos
        char superscriptChar = ' ';
        if (sourceNumber == 1) superscriptChar = '\u00B9'; // ¹
        else if (sourceNumber == 2) superscriptChar = '\u00B2'; // ²
        else if (sourceNumber == 3) superscriptChar = '\u00B3'; // ³
        else if (sourceNumber == 4) superscriptChar = '\u2074'; // ⁴
        else if (sourceNumber == 5) superscriptChar = '\u2075'; // ⁵
        // (Adicione mais se precisar)

        // Tenta Regex 1: Procurar pelo superscript (ex: "¹ ...")
        // Removemos o ^ (início da linha) e o MULTILINE.
        Pattern patternSimple = Pattern.compile(
                Pattern.quote(String.valueOf(superscriptChar)) + "\\s+(.+)"
        );
        Matcher matcher = patternSimple.matcher(sourcesBlock);

        if (matcher.find()) {
            String sourceName = matcher.group(1).trim();
            // Pega apenas a primeira linha da correspondência
            return Optional.of(sourceName.split("\n")[0].trim());
        } else {
            // Tenta Regex 2: Procurar por número normal (ex: "1. ...")
            // Usamos \b (word boundary) para não confundir "10" com "1"
            Pattern patternComplex = Pattern.compile(
                    "\\b" + sourceNumber + "[.:]?\\s+(.+)"
            );
            Matcher matcherComplex = patternComplex.matcher(sourcesBlock);

            if (matcherComplex.find()) {
                String sourceName = matcherComplex.group(1).trim();
                // Pega apenas a primeira linha da correspondência
                return Optional.of(sourceName.split("\n")[0].trim());
            }
        }

        logger.warn("Não foi possível encontrar a fonte nº {} no bloco: {}", sourceNumber, sourcesBlock);
        return Optional.empty();
    }

    @Cacheable("synonyms")
    public Map<String, List<String>> getSynonymMap() {
        logger.info("Buscando sinônimos do banco de dados e populando o cache 'synonyms'...");
        List<TheologicalSynonym> allSynonyms = synonymRepository.findAll();

        Map<String, List<String>> synonymMap = allSynonyms.stream()
                .collect(Collectors.groupingBy(
                        synonym -> synonym.getMainTerm().toLowerCase(), // Força a chave do mapa a ser minúscula
                        Collectors.mapping(TheologicalSynonym::getSynonym, Collectors.toList())
                ));

        logger.info("Cache 'synonyms' populado com {} termos principais.", synonymMap.size());
        return synonymMap;
    }


    private QueryRouterResponse routeQuery(String userQuestion) {
        // 🚀 CORREÇÃO 1: Usar .formatted() para evitar conflito com {{ }}
        // (Isso corrige o bug 'The template string is not valid' dos logs anteriores)
        String routerPrompt = """
                A pergunta a seguir é simples ou complexa?
                - "simples": A pergunta pode ser respondida com uma única busca.
                - "complexa": A pergunta é comparativa ou requer busca por múltiplos tópicos.
                
                Se for complexa, quebre-a numa lista de sub-perguntas simples.
                Responda APENAS com um JSON.
                
                Exemplo 1 (Simples):
                Pergunta: "O que é a graça?"
                JSON:
                {{"type": "simple", "queries": ["O que é a graça?"]}}
                
                Exemplo 2 (Complexa):
                Pergunta: "Qual a diferença entre justificação e santificação na CFW?"
                JSON:
                {{"type": "complex", "queries": ["O que a CFW diz sobre justificação?", "O que a CFW diz sobre santificação?"]}}
                
                Pergunta do Usuário: "%s"
                JSON:
                """.formatted(userQuestion);

        try {
            String jsonResponse = geminiApiClient.generateContent(
                    routerPrompt,
                    Collections.emptyList(),
                    userQuestion
            );

            String cleanJson = jsonResponse.replaceAll("```json", "").replaceAll("```", "").trim();
            return objectMapper.readValue(cleanJson, QueryRouterResponse.class);

        } catch (Exception e) {
            logger.error("❌ Erro ao rotear a pergunta: {}. Assumindo 'simples'. Erro: {}",
                    userQuestion, e.getMessage(), e);

            // ======================================================
            // 🚀 CORREÇÃO 2: O fallback deve retornar um QueryRouterResponse
            // ======================================================
            // O erro que você viu foi porque eu escrevi 'new QueryServiceResult(...)'
            // o que estava errado em tipo e argumentos.
            return new QueryRouterResponse("simple", List.of(userQuestion));
            // ======================================================
        }
    }

    private UUID saveAiMessage(UUID chatId, String answer, List<SourceReference> references) {
        if (chatId == null) return null;

        try {
            Conversa conversation = conversaRepository.findById(chatId)
                    .orElseThrow(() -> new EntityNotFoundException("Conversa não encontrada para ID: " + chatId));

            Mensagem aiMsg = new Mensagem();
            aiMsg.setConversa(conversation);
            aiMsg.setRole("assistant");
            aiMsg.setContent(answer);
            aiMsg.setCreatedAt(OffsetDateTime.now());
            aiMsg.setReferences(references);

            mensagemRepository.save(aiMsg);
            logger.info("✅ Mensagem da IA salva com ID: {} e {} referências.", aiMsg.getId(), references.size());

            return aiMsg.getId(); // Agora retorna UUID corretamente
        } catch (Exception e) {
            logger.error("❌ Erro ao salvar mensagem no histórico: {}", e.getMessage());
            return null;
        }
    }
}


