package br.com.fereformada.api.dto;

import java.util.List;

public record QueryServiceResult(
        String answer, // A resposta da IA (com ¹, ²)
        List<SourceReference> references // A lista de fontes para o rodapé
) {}
