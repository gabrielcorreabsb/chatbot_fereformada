package br.com.fereformada.api.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "study_notes", indexes = {
        @Index(name = "idx_studynote_source", columnList = "source"),
        @Index(name = "idx_studynote_book_chapter", columnList = "book, startChapter")
})
@Getter
@Setter
public class StudyNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String source; // Ex: "Bíblia de Genebra"

    @Column(nullable = false, length = 50)
    private String book; // Ex: "Gênesis"

    @Column(nullable = false)
    private Integer startChapter;

    @Column(nullable = false)
    private Integer startVerse;

    @Column(nullable = false)
    private Integer endChapter;

    @Column(nullable = false)
    private Integer endVerse;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String noteContent;

    // Vetor para busca semântica (opcional, mas recomendado)
    // Usando a mesma abordagem do ContentChunk
    @Column(name = "note_vector", columnDefinition = "vector(768)")
    private float[] noteVector;

}