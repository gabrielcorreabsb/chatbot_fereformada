package br.com.fereformada.api.service;

import br.com.fereformada.api.model.Mensagem;
import com.pgvector.PGvector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel; // Injeção correta
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class GeminiApiClient {

    private static final Logger logger = LoggerFactory.getLogger(GeminiApiClient.class);

    private final ChatModel chatModel; // Você está injetando o Model
    private final EmbeddingModel embeddingModel;

    @Autowired
    public GeminiApiClient(ChatModel chatModel, EmbeddingModel embeddingModel) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
    }

    public String generateContent(String systemPrompt, List<Mensagem> chatHistory, String userQuestion) {

        logger.info("Enviando prompt de sistema e histórico de {} mensagens para o modelo.", chatHistory.size());

        List<Message> messages = new ArrayList<>();

        // 1. Adicionar o "System Prompt" (nosso prompt de RAG e regras)
        messages.add(new SystemPromptTemplate(systemPrompt).createMessage());

        // 2. Converter nosso histórico do banco (Mensagem) para o formato do Spring AI
        for (Mensagem historyMsg : chatHistory) {
            if (historyMsg.getRole().equals("user")) {
                messages.add(new UserMessage(historyMsg.getContent()));
            } else if (historyMsg.getRole().equals("assistant")) {
                messages.add(new AssistantMessage(historyMsg.getContent()));
            }
        }

        // 3. (A CORREÇÃO CRÍTICA)
        // Adiciona a pergunta atual do usuário, mas APENAS se ela ainda não estiver no histórico.
        // Isso corrige o erro "contents can't be null or empty" (na primeira pergunta)
        // E também evita duplicatas em perguntas de acompanhamento.
        boolean userQuestionInHistory = chatHistory.stream()
                .anyMatch(m -> m.getRole().equals("user") && m.getContent().equals(userQuestion));

        if (!userQuestionInHistory) {
            messages.add(new UserMessage(userQuestion));
        }

        // 4. Criar o Prompt final com a conversa completa
        Prompt prompt = new Prompt(messages);

        try {
            // 5. Chamar a API com a conversa completa
            ChatResponse response = chatModel.call(prompt);
            return response.getResult().getOutput().getContent();

        } catch (Exception e) {
            logger.error("Erro ao chamar a API para gerar conteúdo.", e);
            throw new RuntimeException("Falha ao gerar conteúdo: " + e.getMessage(), e);
        }
    }

    public PGvector generateEmbedding(String text) {
        if (text == null || text.trim().length() < 10) {
            return null;
        }

        logger.debug("Gerando embedding para o texto: '{}...'", text.substring(0, Math.min(text.length(), 100)));
        try {

            // O método embed(String) é o correto.
            float[] embeddingArray = embeddingModel.embed(text);

            return new PGvector(embeddingArray);

        } catch (Exception e) {
            logger.error("Erro ao chamar a API para gerar embedding para o texto: '{}...'", text.substring(0, Math.min(text.length(), 100)), e);
            return null;
        }
    }

    public List<PGvector> generateEmbeddingsInBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        logger.info("Gerando embeddings em lote para {} textos...", texts.size());
        try {

            List<float[]> batchEmbeddings = embeddingModel.embed(texts);

            // Convertemos cada float[] em um objeto PGvector
            return batchEmbeddings.stream()
                    .map(PGvector::new) // Referência de método para new PGvector(floatArray)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Erro ao chamar a API para gerar embeddings em lote.", e);
            return List.of();
        }
    }
}