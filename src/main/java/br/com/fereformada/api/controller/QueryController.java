package br.com.fereformada.api.controller;

import br.com.fereformada.api.dto.QueryRequest;
import br.com.fereformada.api.dto.QueryResponse;
import br.com.fereformada.api.service.QueryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping
    public QueryResponse askQuestion(@RequestBody QueryRequest request) {
        return queryService.query(request.question());
    }
}