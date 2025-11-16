package br.com.fereformada.api.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import io.hypersistence.utils.hibernate.type.array.FloatArrayType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import io.hypersistence.utils.hibernate.type.array.FloatArrayType;
import org.hibernate.annotations.Type;

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

    @Column(columnDefinition = "TEXT")
    private String sectionTitle;

    @Column(columnDefinition = "TEXT")
    private String chapterTitle;

    private Integer chapterNumber;

    private Integer sectionNumber;

    @Column(name = "subsection_title", columnDefinition = "TEXT")
    private String subsectionTitle;

    @Column(name = "sub_subsection_title", columnDefinition = "TEXT")
    private String subSubsectionTitle;

    // ✅ CORREÇÃO AQUI: Adicionar @JdbcTypeCode
    @Column(name = "content_vector", columnDefinition = "vector(768)")
    @Basic(fetch = FetchType.LAZY)
    private float[] contentVector;

    @Column(name = "question_vector", columnDefinition = "vector(768)") // Mesma dimensão
    @Basic(fetch = FetchType.LAZY)
    private float[] questionVector;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_id", nullable = false)
    @JsonBackReference("work-chunks")
    private Work work;

    @ManyToMany(cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
            name = "chunk_topics",
            joinColumns = @JoinColumn(name = "chunk_id"),
            inverseJoinColumns = @JoinColumn(name = "topic_id")
    )
    @JsonManagedReference
    private Set<Topic> topics = new HashSet<>();
}