package br.com.fereformada.api.service;

import br.com.fereformada.api.dto.MetadataFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class QueryAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(QueryAnalyzer.class);
    private final GeminiApiClient geminiClient;
    private final ObjectMapper objectMapper;

    // Prompt de sistema otimizado para o seu modelo de dados
    private static final String SYSTEM_PROMPT = """
            Você é um assistente de análise de consulta para uma API de teologia reformada.
            Seu trabalho é analisar a pergunta do usuário e extrair filtros estruturais com base nos metadados disponíveis.
            Os metadados disponíveis são: 'obra_acronimo' (CFW, CM, BC, TSB, ICR), 'livro_biblico' (Jó, Romanos, etc.), 'capitulo', 'secao_ou_versiculo'.
            
             MAPEAMENTO DE REGRAS:
            1.  'capitulo' é usado para livros da Bíblia (Jó 24) ou capítulos da CFW/Institutas (CFW 1).
            2.  'secao_ou_versiculo' é usado para versículos da Bíblia (Jó 24:18), seções da CFW (CFW 1.4) ou NÚMEROS DE PERGUNTA de catecismos (CM 1, BC 1).
            
            Responda APENAS com um objeto JSON. Se nenhum filtro for encontrado, retorne um JSON vazio ({{}}).
            Se a pergunta tiver contexto (ex: "e a seção 4?"), use o histórico.
            
            Exemplos:
            - Pergunta: O que diz na Confissão de Fé de Westminster no Capítulo 1?
            - Resposta: {{"obra_acronimo": "CFW", "capitulo": 1}}
            
            - Pergunta: e a seção 4? (Histórico: ...falava da CFW cap 1)
            - Resposta: {{"obra_acronimo": "CFW", "capitulo": 1, "secao_ou_versiculo": 4}}
            
            - Pergunta: O que diz em Jó 24:18?
            - Resposta: {{"livro_biblico": "Jó", "capitulo": 24, "secao_ou_versiculo": 18}}
            
            - Pergunta: Qual o fim principal do homem? (Catecismo Maior)
            - Resposta: {{"obra_acronimo": "CM", "secao_ou_versiculo": 1}}
            
            - Pergunta: O que diz o Catecismo Maior de Westminster, pergunta 1?
            - Resposta: {{"obra_acronimo": "CM", "secao_ou_versiculo": 1}}
            
            - Pergunta: Breve Catecismo, pergunta 10
            - Resposta: {{"obra_acronimo": "BC", "secao_ou_versiculo": 10}}
            
            - Pergunta: Qual a opinião de Calvino sobre a providência?
            - Resposta: {{}}
            """;

    public QueryAnalyzer(GeminiApiClient geminiClient, ObjectMapper objectMapper) {
        this.geminiClient = geminiClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Analisa a pergunta do usuário e extrai filtros de metadados.
     *
     * @param userQuery   A pergunta do usuário (ex: "O que diz na CFW Cap 1?")
     * @param chatHistory O histórico do chat (para contexto)
     * @return um objeto MetadataFilter preenchido.
     */
    public MetadataFilter extractFilters(String userQuery, List<br.com.fereformada.api.model.Mensagem> chatHistory) {
        try {
            // Usar o método generateContent que você já tem
            // NOTA: Talvez você precise criar um método no GeminiApiClient
            // que force a resposta em JSON, se o seu atual não fizer isso.
            // Por agora, vamos assumir que ele entende o prompt de sistema.
            String jsonResponse = geminiClient.generateContent(
                    SYSTEM_PROMPT,
                    chatHistory, // Passa o histórico
                    userQuery    // Passa a pergunta
            );

            // Limpar a resposta (LLMs às vezes adicionam ```json ... ```)
            String cleanJson = cleanGeminiResponse(jsonResponse);

            // Desserializa a resposta JSON para o nosso record
            return objectMapper.readValue(cleanJson, MetadataFilter.class);

        } catch (Exception e) {
            logger.error("❌ Falha ao analisar filtros de metadados: {}", e.getMessage(), e);
            // Em caso de falha, retorne um filtro vazio para não quebrar a busca.
            return new MetadataFilter(null, null, null, null);
        }
    }

    private String cleanGeminiResponse(String response) {
        if (response == null || response.isBlank()) {
            return "{}";
        }

        String cleaned = response.trim();

        // 1. Tenta encontrar o início do JSON
        int jsonStart = cleaned.indexOf('{');
        // 2. Tenta encontrar o fim do JSON
        int jsonEnd = cleaned.lastIndexOf('}');

        // 3. Se encontrou um '{' e um '}' válidos
        if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {

            // 4. Extrai o JSON bruto
            cleaned = cleaned.substring(jsonStart, jsonEnd + 1);

            // 5. Verifica se é um JSON válido (safety check)
            if (cleaned.startsWith("{") && cleaned.endsWith("}")) {
                logger.debug("JSON limpo e extraído com sucesso.");
                return cleaned;
            }
        }

        // 6. Se não encontrou, ou se o resultado é inválido, loga o aviso e retorna vazio
        logger.warn("Resposta do QueryAnalyzer não foi um JSON válido ou não pôde ser limpa: {}", response);
        return "{}";
    }
}