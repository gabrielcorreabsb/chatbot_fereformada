package br.com.fereformada.api.dto;

public record ReaderNoteDTO(
        Long id,
        String noteContent,
        Integer startVerse,
        Integer endVerse
) {}