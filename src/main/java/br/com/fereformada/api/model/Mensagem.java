package br.com.fereformada.api.model;

import br.com.fereformada.api.dto.SourceReference;
import br.com.fereformada.api.util.SourceReferenceConverter;
import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "mensagens")
@Getter
@Setter
public class Mensagem {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @JsonBackReference
    // Mapeia o relacionamento: Muitas Mensagens pertencem a uma Conversa
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_id", nullable = false)
    private Conversa conversa;

    @Column(nullable = false)
    private String role; // "user" ou "assistant"

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }

    @Column(name = "source_references", columnDefinition = "TEXT")
    @Convert(converter = SourceReferenceConverter.class)
    private List<SourceReference> references;

}