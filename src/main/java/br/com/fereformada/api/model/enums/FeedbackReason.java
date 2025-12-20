package br.com.fereformada.api.model.enums;

public enum FeedbackReason {
    HALLUCINATION,      // IA inventou
    THEOLOGICAL_ERROR,  // Erro doutrinário
    OUT_OF_CONTEXT,     // Correto mas irrelevante
    OTHER
}