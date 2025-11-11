package br.com.fereformada.api.model;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Formula;

import java.util.List;

@Entity
@Table(name = "works")
@Getter
@Setter
public class Work {

    @Column(nullable = false, unique = true) // Adicione esta linha
    private String acronym;                  // Adicione esta linha

    // Adicione getters e setters para 'acronym'
    public String getAcronym() {
        return acronym;
    }

    public void setAcronym(String acronym) {
        this.acronym = acronym;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    private Integer publicationYear;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(nullable = false)
    private String type; // Ex: "LIVRO", "CONFISSAO"

    @Column(name = "boost_priority")
    private Integer boostPriority;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    @JsonManagedReference
    private Author author;

    @OneToMany(mappedBy = "work", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<ContentChunk> contentChunks;

    @Formula("(SELECT COUNT(c.id) FROM content_chunks c WHERE c.work_id = id)")
    private long chunkCount;
}