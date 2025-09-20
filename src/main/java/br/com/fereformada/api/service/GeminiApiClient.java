package br.com.fereformada.api.service;

import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.ResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class GeminiApiClient {

    private static final Logger logger = LoggerFactory.getLogger(GeminiApiClient.class);

    private final String projectId;
    private final String location = "us-central1";
    private final String modelName = "gemini-2.5-flash"; // Usando o modelo Gemini 1.5 Pro

    public GeminiApiClient(@Value("${google.project.id}") String projectId) {
        this.projectId = projectId;
    }

    public String generateContent(String prompt) {
        logger.info("Enviando prompt para o modelo {}...", modelName);
        try (VertexAI vertexAI = new VertexAI(projectId, location)) {

            GenerativeModel model = new GenerativeModel(modelName, vertexAI);
            GenerateContentResponse response = model.generateContent(prompt);
            String textResponse = ResponseHandler.getText(response);
            logger.info("Resposta recebida da IA.");
            return textResponse;

        } catch (IOException e) {
            logger.error("Erro ao chamar a API do Gemini: {}", e.getMessage());
            e.printStackTrace();
            return "Ocorreu um erro ao tentar gerar a resposta. Verifique as credenciais e a conexão.";
        }
    }
}