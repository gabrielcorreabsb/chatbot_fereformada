package br.com.fereformada.api.model.enums;

public enum TaskStatus {
    PENDING,    // 0: Tarefa criada, aguardando início
    PROCESSING, // 1: Em execução (ex: "Processando lote 2 de 5")
    COMPLETED,  // 2: Concluída com sucesso
    FAILED      // 3: Falha (ex: erro de API do Gemini, erro de banco)
}