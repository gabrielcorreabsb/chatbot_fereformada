package br.com.fereformada.api.controller;

import br.com.fereformada.api.dto.*;
import br.com.fereformada.api.service.QueryService;
import com.pgvector.PGvector;
import br.com.fereformada.api.service.GeminiApiClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

        // 1. Cria o ChatRequest (como você já fez)
        ChatRequest chatRequest = new ChatRequest(request.question(), null);

        // 2. Chama o QueryService, que retorna o NOVO DTO
        QueryServiceResult result = queryService.query(chatRequest);

        // 3. Converte a lista de SourceReference (objetos) em uma lista de String (nomes)
        List<String> sourceNames = result.references().stream()
                .map(SourceReference::sourceName) // Pega apenas o nome de cada fonte
                .collect(Collectors.toList());

        // 4. Retorna o DTO ANTIGO (QueryResponse), como o método promete
        return new QueryResponse(result.answer(), sourceNames);
    }

}