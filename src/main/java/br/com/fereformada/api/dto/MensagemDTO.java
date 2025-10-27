package br.com.fereformada.api.dto;

import java.util.UUID;

public record MensagemDTO(UUID id, String role, String content) {
}