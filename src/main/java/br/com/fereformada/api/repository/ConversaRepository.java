package br.com.fereformada.api.repository;

import br.com.fereformada.api.model.Conversa; // Importe sua entidade Conversa
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ConversaRepository extends JpaRepository<Conversa, UUID> {
}