package br.com.fereformada.api.dto;

import java.util.UUID;
// chatId pode ser nulo se for uma nova conversa
public record ChatRequest(String question, UUID chatId) {
}