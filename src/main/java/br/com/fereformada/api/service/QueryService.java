package br.com.fereformada.api.service;

import br.com.fereformada.api.dto.ContextItem; // Importe o novo DTO
import br.com.fereformada.api.dto.QueryResponse;
import br.com.fereformada.api.model.ContentChunk;
import br.com.fereformada.api.model.StudyNote; // Importe StudyNote
import br.com.fereformada.api.model.Work;
import br.com.fereformada.api.repository.ContentChunkRepository;
import br.com.fereformada.api.repository.StudyNoteRepository; // Importe StudyNoteRepository
import br.com.fereformada.api.repository.WorkRepository;
import com.pgvector.PGvector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class QueryService {

    private static final Logger logger = LoggerFactory.getLogger(QueryService.class);
    private final ContentChunkRepository contentChunkRepository;
    private final StudyNoteRepository studyNoteRepository; // MUDANÇA: Adicionado
    private final WorkRepository workRepository;
    private final GeminiApiClient geminiApiClient;

    public QueryService(ContentChunkRepository contentChunkRepository,
                        StudyNoteRepository studyNoteRepository, // MUDANÇA: Adicionado
                        WorkRepository workRepository,
                        GeminiApiClient geminiApiClient) {
        this.contentChunkRepository = contentChunkRepository;
        this.studyNoteRepository = studyNoteRepository; // MUDANÇA: Adicionado
        this.workRepository = workRepository;
        this.geminiApiClient = geminiApiClient;
    }

    public QueryResponse query(String userQuestion) {
        logger.info("Nova pergunta recebida: '{}'", userQuestion);

        // MUDANÇA: BUSCA VETORIAL UNIFICADA
        List<ContextItem> vectorResults = performVectorSearch(userQuestion);

        if (vectorResults.isEmpty()) {
            // Poderíamos adicionar fallback de keyword search aqui se necessário
            return new QueryResponse("Não consegui localizar um contexto relevante para responder.", Collections.emptyList());
        }

        // Os resultados já vêm ordenados por relevância do banco de dados
        logger.info("🎯 Busca vetorial unificada encontrou {} itens relevantes", vectorResults.size());

        String prompt = buildPrompt(userQuestion, vectorResults);
        String aiAnswer = geminiApiClient.generateContent(prompt);
        List<String> sources = vectorResults.stream().map(ContextItem::source).toList();

        return new QueryResponse(aiAnswer, sources);
    }

    /**
     * MUDANÇA: Busca em ambas as tabelas (ContentChunk e StudyNote) e combina os resultados.
     */
    private List<ContextItem> performVectorSearch(String userQuestion) {
        PGvector questionVector = geminiApiClient.generateEmbedding(userQuestion);
        if (questionVector == null) {
            logger.warn("⚠️ Não foi possível gerar embedding para a pergunta");
            return Collections.emptyList();
        }

        // 1. Buscar em ContentChunks
        List<Object[]> rawChunkResults = contentChunkRepository.findSimilarChunksRaw(questionVector.toString(), 3);
        List<ContextItem> chunkItems = convertRawChunkResultsToContextItems(rawChunkResults);

        // 2. Buscar em StudyNotes
        List<Object[]> rawNoteResults = studyNoteRepository.findSimilarNotesRaw(questionVector.toString(), 3);
        List<ContextItem> noteItems = convertRawNoteResultsToContextItems(rawNoteResults);

        // 3. Combinar, ordenar por relevância e pegar os melhores
        List<ContextItem> combinedItems = new ArrayList<>();
        combinedItems.addAll(chunkItems);
        combinedItems.addAll(noteItems);

        // Ordena a lista combinada pela pontuação de similaridade (maior primeiro)
        combinedItems.sort(Comparator.comparing(ContextItem::similarityScore).reversed());

        // Limita ao top 5 resultados gerais
        return combinedItems.stream().limit(5).collect(Collectors.toList());
    }

    /**
     * MUDANÇA: Converte resultados de ContentChunk para ContextItem
     */
    private List<ContextItem> convertRawChunkResultsToContextItems(List<Object[]> rawResults) {
        List<ContextItem> items = new ArrayList<>();
        for (Object[] row : rawResults) {
            try {
                // ... (lógica de conversão igual à sua, mas no final cria um ContextItem)
                Long workId = ((Number) row[7]).longValue();
                Work work = workRepository.findById(workId).orElse(null);

                // Temp object to pass to factory method
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

    /**
     * MUDANÇA: Novo método para converter resultados de StudyNote para ContextItem
     */
    private List<ContextItem> convertRawNoteResultsToContextItems(List<Object[]> rawResults) {
        List<ContextItem> items = new ArrayList<>();
        for (Object[] row : rawResults) {
            try {
                // Temp object to pass to factory method
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

    /**
     * MUDANÇA: Agora recebe List<ContextItem>
     */
    private String buildPrompt(String question, List<ContextItem> items) {
        StringBuilder context = new StringBuilder();
        context.append("Contexto Fornecido:\n");
        for (ContextItem item : items) {
            context.append("- Fonte: ").append(item.source());
            if (item.question() != null) {
                context.append("\n  Pergunta da Fonte: ").append(item.question());
            }
            context.append("\n  Texto da Fonte: ").append(item.content()).append("\n\n");
        }

        // O resto do prompt continua igual
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

}