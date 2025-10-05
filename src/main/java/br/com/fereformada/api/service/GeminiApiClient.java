package br.com.fereformada.api.service;

import com.pgvector.PGvector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class GeminiApiClient {

    private static final Logger logger = LoggerFactory.getLogger(GeminiApiClient.class);

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;

    @Autowired
    public GeminiApiClient(ChatModel chatModel, EmbeddingModel embeddingModel) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
    }

    public String generateContent(String prompt) {
        logger.info("Enviando prompt para o modelo generativo: '{}...'", prompt.substring(0, Math.min(prompt.length(), 100)));
        try {
            return chatModel.call(new Prompt(prompt)).getResult().getOutput().getContent();
        } catch (Exception e) {
            logger.error("Erro ao chamar a API para gerar conteúdo.", e);
            return "Ocorreu um erro ao tentar gerar a resposta.";
        }
    }

    public PGvector generateEmbedding(String text) {
        if (text == null || text.trim().length() < 10) {
            return null;
        }

        logger.debug("Gerando embedding para o texto: '{}...'", text.substring(0, Math.min(text.length(), 100)));
        try {
            // O método embed retorna diretamente float[]
            float[] embeddingArray = embeddingModel.embed(text);

            // O PGvector aceita diretamente float[]
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
            // 1. O Spring AI chama a API e retorna uma lista de vetores (List<float[]>)
            List<float[]> batchEmbeddings = embeddingModel.embed(texts);

            // 2. Convertemos cada float[] em um objeto PGvector
            return batchEmbeddings.stream()
                    .map(PGvector::new) // Referência de método para new PGvector(floatArray)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Erro ao chamar a API para gerar embeddings em lote.", e);
            return List.of();
        }
    }
}