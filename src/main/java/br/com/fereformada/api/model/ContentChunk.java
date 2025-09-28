package br.com.fereformada.api.model;

import com.pgvector.PGvector;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "content_chunks")
@Getter
@Setter
public class ContentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String question;

    @Column(columnDefinition = "TEXT")
    private String sectionTitle;

    @Column(columnDefinition = "TEXT")
    private String chapterTitle;

    private Integer chapterNumber;

    private Integer sectionNumber;

    // **** CAMPO DE VETOR CORRIGIDO E MODERNO ****
    @Column(name = "content_vector", columnDefinition = "vector(768)")
    private PGvector contentVector; // <-- TIPO DE DADO CORRETO
    // ********************************************

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_id", nullable = false)
    private Work work;

    @ManyToMany(cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
            name = "chunk_topics",
            joinColumns = @JoinColumn(name = "chunk_id"),
            inverseJoinColumns = @JoinColumn(name = "topic_id")
    )
    private Set<Topic> topics = new HashSet<>();
}