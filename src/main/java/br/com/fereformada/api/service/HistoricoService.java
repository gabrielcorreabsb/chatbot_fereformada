package br.com.fereformada.api.service;

import br.com.fereformada.api.dto.ConversaDTO;
import br.com.fereformada.api.dto.MensagemDTO;
import br.com.fereformada.api.exception.ForbiddenException;
import br.com.fereformada.api.exception.ResourceNotFoundException;
import br.com.fereformada.api.model.Conversa;
import br.com.fereformada.api.model.Mensagem;
import br.com.fereformada.api.repository.ConversaRepository;
import br.com.fereformada.api.repository.MensagemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class HistoricoService {

    private final ConversaRepository conversaRepository;
    private final MensagemRepository mensagemRepository;

    public HistoricoService(ConversaRepository conversaRepository, MensagemRepository mensagemRepository) {
        this.conversaRepository = conversaRepository;
        this.mensagemRepository = mensagemRepository;
    }

    @Transactional
    public Conversa salvarMensagemUsuario(UUID userId, String texto, UUID chatId) {
        // 1. Encontra a conversa ou cria uma nova
        Conversa conversa;
        if (chatId != null) {
            // 👇 VERIFICA PROPRIEDADE SE chatId EXISTIR 👇
            conversa = findAndVerifyOwnership(chatId, userId);
        } else {
            conversa = criarNovaConversa(userId, texto);
        }

        Mensagem msgUsuario = new Mensagem();
        msgUsuario.setConversa(conversa);
        msgUsuario.setRole("user");
        msgUsuario.setContent(texto);
        mensagemRepository.save(msgUsuario);

        return conversa; // Retorna a conversa (nova ou existente)
    }

    @Transactional
    public void salvarMensagemIA(Conversa conversa, String texto) {
        // 1. Cria e salva a mensagem da IA, associada à mesma conversa
        Mensagem msgIA = new Mensagem();
        msgIA.setConversa(conversa);
        msgIA.setRole("assistant");
        msgIA.setContent(texto);
        mensagemRepository.save(msgIA);
    }

    // Método privado para criar uma nova conversa
    private Conversa criarNovaConversa(UUID userId, String primeiroPrompt) {
        Conversa novaConversa = new Conversa();
        novaConversa.setUserId(userId);

        // Título simples (primeiros 50 caracteres do prompt)
        String title = (primeiroPrompt.length() > 50)
                ? primeiroPrompt.substring(0, 50) + "..."
                : primeiroPrompt;
        novaConversa.setTitle(title);

        return conversaRepository.save(novaConversa);
    }

    // --- NOVO MÉTODO 1: Listar conversas como DTOs ---
    public List<ConversaDTO> getConversasPorUsuario(UUID userId) {
        return conversaRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(conversa -> new ConversaDTO(conversa.getId(), conversa.getTitle()))
                .collect(Collectors.toList());
    }

    // --- NOVO MÉTODO 2: Listar mensagens como DTOs ---
    public List<MensagemDTO> getMensagensPorConversa(UUID chatId, UUID userId) {
        // Este método já faz o findById E a verificação de userId,
        // lançando ResourceNotFoundException ou ForbiddenException se necessário.
        Conversa conversa = findAndVerifyOwnership(chatId, userId);

        // Se chegou aqui, o usuário é o dono. Busca as mensagens.
        return mensagemRepository.findByConversaIdOrderByCreatedAtAsc(chatId)
                .stream()
                .map(msg -> new MensagemDTO(msg.getId(), msg.getRole(), msg.getContent()))
                .collect(Collectors.toList());
    }

    private Conversa findAndVerifyOwnership(UUID chatId, UUID userId) {
        Conversa conversa = conversaRepository.findById(chatId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversa não encontrada: " + chatId));

        if (!conversa.getUserId().equals(userId)) {
            throw new ForbiddenException("Acesso negado à conversa: " + chatId);
        }
        return conversa;
    }
}