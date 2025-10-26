package br.com.fereformada.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO (Data Transfer Object) que representa a estrutura de cada objeto
 * no arquivo JSON gerado a partir da Teologia Sistemática (berkhof_chunks.json).
 * Esta classe é um "molde" para que a biblioteca Jackson possa converter
 * o JSON em objetos Java de forma automática.
 */
public class ChunkData {

    /**
     * @JsonProperty é usado para mapear explicitamente o nome do campo no JSON
     * (com snake_case) para o nome da variável em Java (com camelCase).
     * Embora Jackson seja inteligente e muitas vezes consiga fazer isso sozinho,
     * ser explícito torna o código mais robusto e fácil de entender.
     */
    @JsonProperty("chapter_title")
    private String chapterTitle;

    @JsonProperty("section_title")
    private String sectionTitle;

    @JsonProperty("subsection_title")
    private String subsectionTitle;

    @JsonProperty("sub_subsection_title")
    private String subSubsectionTitle;

    @JsonProperty("content")
    private String content;

    /**
     * Um construtor vazio é necessário para que bibliotecas como Jackson
     * possam instanciar o objeto antes de preencher seus campos.
     */
    public ChunkData() {
    }

    // --- Getters e Setters ---
    // Métodos públicos para que Jackson (e outras partes do seu código)
    // possam acessar e modificar os campos privados.

    public String getChapterTitle() {
        return chapterTitle;
    }

    public void setChapterTitle(String chapterTitle) {
        this.chapterTitle = chapterTitle;
    }

    public String getSectionTitle() {
        return sectionTitle;
    }

    public void setSectionTitle(String sectionTitle) {
        this.sectionTitle = sectionTitle;
    }

    public String getSubsectionTitle() {
        return subsectionTitle;
    }

    public void setSubsectionTitle(String subsectionTitle) {
        this.subsectionTitle = subsectionTitle;
    }

    public String getSubSubsectionTitle() {
        return subSubsectionTitle;
    }

    public void setSubSubsectionTitle(String subSubsectionTitle) {
        this.subSubsectionTitle = subSubsectionTitle;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    /**
     * O método toString() é útil para debugging. Se você imprimir um objeto
     * desta classe no console, ele mostrará seu conteúdo de forma legível.
     */
    @Override
    public String toString() {
        return "ChunkData{" +
                "chapterTitle='" + chapterTitle + '\'' +
                ", sectionTitle='" + sectionTitle + '\'' +
                ", subsectionTitle='" + subsectionTitle + '\'' +
                ", subSubsectionTitle='" + subSubsectionTitle + '\'' +
                ", content='" + (content != null ? content.substring(0, Math.min(50, content.length())) : "null") + "..." + '\'' +
                '}';
    }
}