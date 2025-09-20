package br.com.fereformada.api.dto;

import java.util.List;

public record QueryResponse(String answer, List<String> sources) {
}