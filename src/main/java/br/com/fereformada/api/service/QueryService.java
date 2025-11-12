package br.com.fereformada.api.service;

import br.com.fereformada.api.dto.*;
import br.com.fereformada.api.model.*;
import br.com.fereformada.api.repository.*;
import br.com.fereformada.api.repository.MensagemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import jakarta.persistence.EntityNotFoundException;
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

    public QueryService(ContentChunkRepository contentChunkRepository,
                        StudyNoteRepository studyNoteRepository,
                        WorkRepository workRepository, // Já está injetado
                        GeminiApiClient geminiApiClient,
                        MensagemRepository mensagemRepository,
                        QueryAnalyzer queryAnalyzer,
                        ObjectMapper objectMapper,
                        TheologicalSynonymRepository synonymRepository) {

        this.contentChunkRepository = contentChunkRepository;
        this.studyNoteRepository = studyNoteRepository;
        this.workRepository = workRepository;
        this.geminiApiClient = geminiApiClient;
        this.mensagemRepository = mensagemRepository;
        this.queryAnalyzer = queryAnalyzer;
        this.objectMapper = objectMapper;
        this.synonymRepository = synonymRepository;

        // INÍCIO DA LÓGICA DE CONSTRUÇÃO DO REGEX DINÂMICO
        List<Work> allWorks = workRepository.findAll();
        StringBuilder regexBuilder = new StringBuilder("\\b(?:");
        this.regexGroupToAcronymMap = new HashMap<>();
        int groupIndex = 1; // O índice do grupo de captura do Regex começa em 1

        for (Work work : allWorks) {
            if (work.getAcronym() == null || work.getAcronym().isBlank()) continue;

            String acronym = work.getAcronym();

            // --- A LÓGICA DE TÍTULO "HARDCODED" FOI REMOVIDA (Conforme sua solicitação) ---
            String regexFragment = Pattern.quote(acronym); // Ex: "CFW", "HC", "TSB"

            if (groupIndex > 1) {
                regexBuilder.append("|");
            }

            // O grupo de captura é *apenas* o acrónimo
            regexBuilder.append("(").append(regexFragment).append(")");

            // Mapeia o índice do grupo (1, 2, 3...) ao acrónimo ("CFW", "CM", "HC"...)
            this.regexGroupToAcronymMap.put(groupIndex, acronym);
            groupIndex++;
        }

        regexBuilder.append(")\\b"); // Fim dos grupos de obras
        regexBuilder.append("[\\s,]*"); // Separador
        regexBuilder.append("(?:pergunta|capitulo|cap\\.?|p\\.?\\s*)?"); // Palavra-chave opcional

        // Adiciona os grupos de captura para capítulo e seção
        regexBuilder.append("(\\d+)"); // Grupo N+1 (Capítulo)
        regexBuilder.append("(?:[:.](\\d+))?"); // Grupo N+2 (Seção)

        this.confessionalPattern = Pattern.compile(regexBuilder.toString(), Pattern.CASE_INSENSITIVE);

        logger.info("Regex de busca direta 100% dinâmico construído com {} obras.", this.regexGroupToAcronymMap.size());
        // FIM DA LÓGICA DE CONSTRUÇÃO DO REGEX

    }


    public QueryServiceResult query(ChatRequest request) {

        String userQuestion = request.question();
        UUID chatId = request.chatId();

        // --- 1. Verificação de Referência Direta (FAST-PATH) ---
        Optional<QueryServiceResult> directResponse = handleDirectReferenceQuery(userQuestion);
        if (directResponse.isPresent()) {
            logger.info("✅ Resposta gerada via busca direta por referência (Regex).");
            return directResponse.get();
        }

        logger.info("Nova pergunta recebida: '{}' (ChatID: {})", userQuestion, chatId);

        // --- 2. Verificação de Cache ---
        String cacheKey = normalizeQuestion(userQuestion);
        if (responseCache.containsKey(cacheKey)) {
            logger.info("✅ Cache hit para: '{}'", userQuestion);
            return responseCache.get(cacheKey);
        }

        // --- 3. Carregar Histórico ---
        List<Mensagem> chatHistory = new ArrayList<>();
        if (chatId != null) {
            chatHistory = mensagemRepository.findByConversaIdOrderByCreatedAtAsc(chatId);
            logger.info("Carregado {} mensagens do histórico do chat {}", chatHistory.size(), chatId);
        }

        // --- 4. NOVO: Análise da Pergunta (SLOW-PATH) ---
        MetadataFilter filter = queryAnalyzer.extractFilters(userQuestion, chatHistory);
        if (!filter.isEmpty()) {
            logger.info("🧠 Filtros de metadados extraídos via LLM: {}", filter);
        } else {
            logger.info("Buscando por (busca semântica pura): '{}'", userQuestion);
        }

        // --- 5. LÓGICA DE RAG CONVERSACIONAL (Mantido e Corrigido) ---
        // Define a query base. 'ragQuery' será a string usada para a busca semântica.
        String ragQuery = userQuestion;

        Optional<Integer> sourceNum = extractSourceNumberFromQuestion(userQuestion);
        if (sourceNum.isPresent()) {
            logger.info("Detectada pergunta de acompanhamento para a fonte número {}", sourceNum.get());
            Optional<String> extractedSource = extractSourceFromHistory(chatHistory, sourceNum.get());

            if (extractedSource.isPresent()) {
                String sourceName = extractedSource.get();
                logger.info("Fonte extraída do histórico: '{}'", sourceName);

                // Tenta a busca direta (regex) com a fonte extraída (ex: "CFW 1.1")
                Optional<QueryServiceResult> directFollowUp = handleDirectReferenceQuery(sourceName);
                if (directFollowUp.isPresent()) {
                    logger.info("Respondendo ao acompanhamento com busca de referência direta.");
                    return directFollowUp.get();
                } else {
                    // Se falhar, usa o nome da fonte como a query RAG
                    logger.warn("Busca direta falhou para '{}', usando busca RAG padrão.", sourceName);
                    ragQuery = sourceName; // Sobrescreve a query semântica
                }
            } else {
                logger.warn("Não foi possível extrair o nome da fonte {} do histórico.", sourceNum.get());
            }
        }
        // Ao final, 'ragQuery' é ou a 'userQuestion' ou o nome da fonte extraída.

        // --- 6. Busca Híbrida (RAG) (MODIFICADA) ---
        // Passamos o 'ragQuery' (para busca semântica) e o 'filter' (para busca estrutural)
        List<ContextItem> results = performHybridSearch(ragQuery, filter);

        // --- 7. Verificação de Resultados ---
        if (results.isEmpty()) {
            // 👇 TIPO DE RETORNO ATUALIZADO
            return new QueryServiceResult(
                    "Não encontrei informações relevantes nas fontes catalogadas. " +
                            "Tente reformular sua pergunta ou ser mais específico.",
                    Collections.emptyList()
            );
        }

        // --- 8. Log de Qualidade ---

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

        // --- 9. Construção do Prompt e Chamada da IA ---
        String prompt = buildOptimizedPrompt(userQuestion, results, chatHistory);
        String aiAnswer;
        try {
            aiAnswer = geminiApiClient.generateContent(prompt, chatHistory, userQuestion);
            if (aiAnswer == null || aiAnswer.trim().isEmpty()) {
                aiAnswer = "Desculpe, não consegui gerar uma resposta. Tente novamente.";
            }
        } catch (Exception e) {
            logger.error("❌ Erro ao chamar a API do Gemini para a pergunta: '{}'. Erro: {}", userQuestion, e.getMessage(), e);
            aiAnswer = "Desculpe, ocorreu um erro ao tentar processar sua pergunta com a IA. Por favor, tente novamente mais tarde.";
            // 👇 TIPO DE RETORNO ATUALIZADO
            return new QueryServiceResult(aiAnswer, Collections.emptyList());
        }

        // --- 10. PÓS-PROCESSAMENTO PARA CRIAR REFERÊNCIAS ---
        List<SourceReference> references = new ArrayList<>();
        Map<String, Integer> sourceToNumberMap = new HashMap<>();
        int sourceCounter = 1;

        // Itera sobre os resultados da busca RAG para construir a lista de fontes
        for (ContextItem item : results) {
            String fullSource = item.source();
            if (!sourceToNumberMap.containsKey(fullSource)) {
                sourceToNumberMap.put(fullSource, sourceCounter++);
            }
            int sourceNumber = sourceToNumberMap.get(fullSource);

            // Adiciona a fonte à lista (o front-end usará isso)
            references.add(new SourceReference(
                    sourceNumber,
                    fullSource,
                    item.content() // O TEXTO COMPLETO
            ));
        }

        // --- 11. Resposta e Cache ---
        // 👇 TIPO DE RETORNO ATUALIZADO
        QueryServiceResult response = new QueryServiceResult(aiAnswer, references);

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

    private Set<String> extractImportantKeywords(String question, Map<String, List<String>> synonymMap) {
        Set<String> keywords = new HashSet<>();
        String[] words = question.toLowerCase()
                .replaceAll("[?!.,;:]", "")
                .split("\\s+");

        // O mapa agora vem como parâmetro
        // Map<String, List<String>> synonymMap = getSynonymMap(); // <-- LINHA REMOVIDA

        for (String word : words) {
            if (word.length() > 2 && !STOP_WORDS.contains(word)) {
                keywords.add(word);

                if (synonymMap.containsKey(word)) { // Usa o mapa do parâmetro
                    List<String> synonyms = synonymMap.get(word);
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
        if (item.content().length() < 100) {
            finalScore *= 0.9; // Penalidade de 10%
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

        // Buscar mais resultados inicialmente para melhor reranking
        // APLICANDO FILTROS:
        List<Object[]> rawChunkResults = contentChunkRepository.findSimilarChunksRaw(
                questionVector.toString(),
                5,
                filter.obraAcronimo(),      // NOVO (pode ser null)
                filter.capitulo(),        // NOVO (pode ser null)
                filter.secaoOuVersiculo() // NOVO (pode ser null)
        );
        List<ContextItem> chunkItems = convertRawChunkResultsToContextItems(rawChunkResults);

        // APLICANDO FILTROS:
        List<Object[]> rawNoteResults = studyNoteRepository.findSimilarNotesRaw(
                questionVector.toString(),
                5,
                filter.livroBiblico(),    // NOVO (pode ser null)
                filter.capitulo(),        // NOVO (pode ser null)
                filter.secaoOuVersiculo() // NOVO (pode ser null)
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

    private String buildOptimizedPrompt(String question, List<ContextItem> items, List<Mensagem> chatHistory) {
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

    private Optional<QueryServiceResult> handleDirectReferenceQuery(String userQuestion) {

        // --- MUDANÇA 1: O PATTERN VEM DA VARIÁVEL DA CLASSE ---
        Matcher confessionalMatcher = this.confessionalPattern.matcher(userQuestion);

        // O padrão da Bíblia (Bloco 2) pode continuar estático
        Pattern biblicalPattern = Pattern.compile(
                "(?:\\b(BG|Bíblia de Genebra)\\s*-?\\s*)?" +
                        "((?:\\d+\\s+)?[A-Za-zÀ-ÿ]+(?:\\s+[A-Za-zÀ-ÿ]+)*)" +
                        "\\s+" +
                        "(\\d+)" +
                        "[:.](\\d+(?:-\\d+)?)",
                Pattern.CASE_INSENSITIVE
        );
        Matcher biblicalMatcher = biblicalPattern.matcher(userQuestion);

        // --- BLOCO 1: Busca Confessional (LÓGICA ATUALIZADA) ---
        if (confessionalMatcher.find()) {

            // --- MUDANÇA 2: LÓGICA DE EXTRAÇÃO DINÂMICA ---
            String acronym = null;
            // Itera sobre o nosso mapa (ex: 1->CFW, 2->CM, ..., 6->"HC")
            for (int i = 1; i <= this.regexGroupToAcronymMap.size(); i++) {
                if (confessionalMatcher.group(i) != null) {
                    acronym = this.regexGroupToAcronymMap.get(i);
                    break;
                }
            }

            if (acronym == null) return Optional.empty(); // Segurança

            // --- MUDANÇA 3: ÍNDICES DOS GRUPOS CORRIGIDOS ---
            // Os grupos de capítulo/seção agora vêm *depois* dos N grupos de obras
            int chapterGroupIndex = this.regexGroupToAcronymMap.size() + 1;
            int sectionGroupIndex = this.regexGroupToAcronymMap.size() + 2;

            int chapterOrQuestion = Integer.parseInt(confessionalMatcher.group(chapterGroupIndex));
            Integer section = confessionalMatcher.group(sectionGroupIndex) != null ?
                    Integer.parseInt(confessionalMatcher.group(sectionGroupIndex)) : null;

            logger.info("🔍 Referência direta CONFESSIONAL detectada: {} {}{}",
                    acronym.toUpperCase(), chapterOrQuestion,
                    (section != null ? "." + section : ""));

            // O resto da sua lógica está PERFEITA
            List<ChunkProjection> results = contentChunkRepository.findDirectReferenceProjection(
                    acronym, chapterOrQuestion, section
            );

            if (results.isEmpty()) {
                logger.warn("⚠️ Referência confessional {} não encontrada.", acronym.toUpperCase());
                return Optional.empty();
            }

            ChunkProjection directHit = results.get(0);
            Work work = workRepository.findById(directHit.workId())
                    .orElseThrow(() -> new EntityNotFoundException("Work não encontrada para o chunk direto: " + directHit.id()));

            // Criamos uma entidade 'ContentChunk' "falsa" (leve)
            // apenas para o construtor do ContextItem.
            ContentChunk chunkShell = new ContentChunk();
            chunkShell.setId(directHit.id());
            chunkShell.setContent(directHit.content());
            chunkShell.setQuestion(directHit.question());
            chunkShell.setWork(work); // Passamos a Work completa
            // ======================================================

            // ======================================================
            // MUDANÇA 3 de 3: Usar o 'chunkShell'
            // ======================================================
            // A lógica do ContextItem.from() já estava correta
            ContextItem context = ContextItem.from(chunkShell, 1.0, buildContextualSource(directHit), work);
            String referenceString = String.format("%s %d%s",
                    acronym.toUpperCase(), chapterOrQuestion, (section != null ? "." + section : "")
            );

            String focusedPrompt = String.format("""
                        Você é um assistente teológico reformado. O usuário solicitou uma consulta direta a um documento confessional.
                        Sua tarefa é explicar o texto fornecido de forma clara e objetiva.
                        
                        DOCUMENTO: %s
                        REFERÊNCIA: %s
                        TEXTO ENCONTRADO:
                        "%s"
                        
                        INSTRUÇÕES:
                        1.  Comece confirmando a referência (Ex: "Sobre %s, o texto diz...").
                        2.  Explique o significado teológico do texto em suas próprias palavras.
                        3.  Seja direto e focado exclusivamente no texto fornecido.
                        
                        EXPLICAÇÃO:
                        """,
                    work.getTitle(),      // %s (Documento)
                    referenceString,      // %s (Referência)
                    directHit.content(),  // %s (Texto Encontrado)
                    referenceString       // %s (Instrução 1)
            );

            String aiAnswer = geminiApiClient.generateContent(
                    focusedPrompt, Collections.emptyList(), userQuestion
            );

            SourceReference ref = new SourceReference(1, context.source(), context.content());
            QueryServiceResult response = new QueryServiceResult(aiAnswer, List.of(ref));

            return Optional.of(response);
        }

        // --- BLOCO 2: Busca Bíblica (Sem alterações) ---
        else if (biblicalMatcher.find()) {

            // O seu código aqui está 100% correto
            String book = biblicalMatcher.group(2).trim();
            int chapter = Integer.parseInt(biblicalMatcher.group(3));
            String verseGroup = biblicalMatcher.group(4);
            int verse = Integer.parseInt(verseGroup.split("-")[0]);

            logger.info("🔍 Referência direta BÍBLICA detectada: {} {}:{}", book, chapter, verse);
            book = normalizeBookName(book);
            logger.info("📖 Livro normalizado: '{}'", book);

            List<StudyNote> results = studyNoteRepository.findByBiblicalReference(
                    book, chapter, verse
            );

            if (results.isEmpty()) {
                logger.warn("⚠️ Referência bíblica direta {}:{}:{} não encontrada.",
                        book, chapter, verse);
                return Optional.empty();
            }

            StudyNote directHit = results.get(0);
            ContextItem context = ContextItem.from(directHit, 1.0); // Correto

            String focusedPrompt = String.format("""
                            Você é um assistente teológico reformado. O usuário solicitou uma consulta direta a uma nota de estudo bíblica.
                            Sua tarefa é explicar o texto da nota de estudo fornecida de forma clara e objetiva.
                            
                            DOCUMENTO: %s
                            REFERÊNCIA BÍBLICA: %s %d:%d
                            NOTA DE ESTUDO ENCONTRADA:
                            "%s"
                            
                            INSTRUÇÕES:
                            1.  Confirme a referência bíblica (Ex: "Para %s %d:%d, a nota de estudo da Bíblia de Genebra explica que...").
                            2.  Explique o significado teológico da nota de estudo fornecida.
                            3.  Seja direto e focado exclusivamente no texto da nota.
                            
                            EXPLICAÇÃO:
                            """,
                    context.source(),
                    book, chapter, verse,
                    directHit.getNoteContent(),
                    book, chapter, verse
            );

            String aiAnswer = geminiApiClient.generateContent(
                    focusedPrompt, Collections.emptyList(), userQuestion
            );

            SourceReference ref = new SourceReference(1, context.source(), context.content());
            QueryServiceResult response = new QueryServiceResult(aiAnswer, List.of(ref));

            return Optional.of(response);
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

}

