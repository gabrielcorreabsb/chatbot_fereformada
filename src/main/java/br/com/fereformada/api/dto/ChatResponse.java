package br.com.fereformada.api.dto;

import java.util.UUID;
public record ChatResponse(String answer, UUID chatId) {
}
