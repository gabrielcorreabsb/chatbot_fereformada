package br.com.fereformada.api.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * Entidade que representa um par de sinônimos teológicos.
 * Substitui o mapa 'THEOLOGICAL_SYNONYMS' estático.
 */
@Entity
@Table(name = "theological_synonyms",
        // Adiciona uma restrição única para evitar pares duplicados (ex: "fé", "crença")
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"main_term", "synonym"})
        }
)
@Getter
@Setter
public class TheologicalSynonym {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * O termo principal (a "chave" do mapa). Ex: "salvação"
     */
    @Column(name = "main_term", nullable = false)
    private String mainTerm;

    /**
     * O sinônimo associado (o "valor" da lista). Ex: "redenção"
     */
    @Column(nullable = false)
    private String synonym;

}