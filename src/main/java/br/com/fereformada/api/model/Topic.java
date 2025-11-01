package br.com.fereformada.api.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;

@Entity
@Table(name = "topics")
@Getter
@Setter
public class Topic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name; // Ex: "Soteriologia"

    @Column(columnDefinition = "TEXT")
    private String description;

    @ManyToMany(mappedBy = "topics")
    @JsonBackReference
    private Set<ContentChunk> contentChunks;
}