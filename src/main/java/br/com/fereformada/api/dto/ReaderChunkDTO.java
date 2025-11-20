package br.com.fereformada.api.dto;

public record ReaderChunkDTO(
        Long id,
        String content,
        Integer sectionNumber,
        Integer chapterNumber
) {}