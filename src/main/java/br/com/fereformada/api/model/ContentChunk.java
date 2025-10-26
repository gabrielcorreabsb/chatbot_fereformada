package br.com.fereformada.api.model;

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

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String question;

    @Column(columnDefinition = "TEXT") // <-- CORREÇÃO AQUI
    private String sectionTitle;

    @Column(columnDefinition = "TEXT") // <-- CORREÇÃO AQUI
    private String chapterTitle;

    private Integer chapterNumber;

    private Integer sectionNumber;

    @Column(name = "subsection_title", columnDefinition = "TEXT")
    private String subsectionTitle;

    @Column(name = "sub_subsection_title", columnDefinition = "TEXT")
    private String subSubsectionTitle;

    @Column(name = "content_vector", columnDefinition = "vector(768)")
    private float[] contentVector;

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