package br.com.fereformada.api.dto;

import jakarta.validation.constraints.NotBlank; // Importar
import jakarta.validation.constraints.Size;    // Importar
import java.util.UUID;

public record ChatRequest(
        @NotBlank(message = "A pergunta não pode estar vazia.") // Não pode ser nula ou só espaços
        @Size(max = 2000, message = "A pergunta não pode exceder 2000 caracteres.") // Limite de tamanho
        String question,

        UUID chatId // Não precisamos validar o UUID aqui, a busca no DB já fará isso
) {}