package br.com.fereformada.api.controller;

import br.com.fereformada.api.dto.ChatRequest; // <-- 1. ADICIONE ESTE IMPORT
import br.com.fereformada.api.dto.QueryRequest;
import br.com.fereformada.api.dto.QueryResponse;
import br.com.fereformada.api.service.QueryService;
import com.pgvector.PGvector;
import br.com.fereformada.api.service.GeminiApiClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final QueryService queryService;
    private final GeminiApiClient geminiApiClient; // (Este GeminiApiClient provavelmente não é mais necessário aqui se o QueryService o gerencia)

    public QueryController(QueryService queryService, GeminiApiClient geminiApiClient) {
        this.queryService = queryService;
        this.geminiApiClient = geminiApiClient;
    }

    @PostMapping
    public QueryResponse askQuestion(@RequestBody QueryRequest request) {


        ChatRequest chatRequest = new ChatRequest(request.question(), null);

        return queryService.query(chatRequest);
    }

}