package br.com.fereformada.api.model;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "conversas")
@Getter
@Setter
public class Conversa {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    // O ID do usuário do Supabase (sem foreign key)
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = true)
    private String title;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @JsonManagedReference
    // Mapeia o relacionamento: Uma Conversa tem muitas Mensagens
    @OneToMany(mappedBy = "conversa", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Mensagem> mensagens;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}