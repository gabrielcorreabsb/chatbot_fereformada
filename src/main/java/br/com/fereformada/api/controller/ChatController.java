package br.com.fereformada.api.controller;


// Imports dos DTOs

import br.com.fereformada.api.dto.*;

// Imports dos Serviços
import br.com.fereformada.api.model.Mensagem;
import br.com.fereformada.api.repository.ConversaRepository;
import br.com.fereformada.api.repository.MensagemRepository;
import br.com.fereformada.api.service.QueryService;      // Seu serviço de RAG
import br.com.fereformada.api.service.HistoricoService; // O novo serviço de DB

// Import do Modelo
import br.com.fereformada.api.model.Conversa;

// Imports do Spring e Segurança
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chat") // Rota protegida pelo Spring Security
public class ChatController {

    private final QueryService queryService;
    private final HistoricoService historicoService;


    // Injetamos os dois serviços
    public ChatController(QueryService queryService, HistoricoService historicoService) {
        this.queryService = queryService;
        this.historicoService = historicoService;

    }

    @PostMapping
    public ChatResponse handleChat(
            @Valid @RequestBody ChatRequest request,
            Authentication authentication
    ) {
        UUID userId = UUID.fromString((String) authentication.getPrincipal());

        // 1. Salva a pergunta do usuário e obtém a conversa (nova ou existente)
        //    (Assumindo que salvarMensagemUsuario retorna a Conversa)
        Conversa conversa = historicoService.salvarMensagemUsuario(
                userId,
                request.question(),
                request.chatId()
        );

        // 2. CRIA UM NOVO ChatRequest ATUALIZADO com o ID da conversa
        //    Isso garante que o QueryService sempre saiba em qual chat está.
        ChatRequest updatedRequest = new ChatRequest(request.question(), conversa.getId());

        // 3. Chama o QueryService com o request ATUALIZADO
        QueryResponse queryResponse = queryService.query(updatedRequest);

        // 4. Salva a resposta da IA na mesma conversa
        historicoService.salvarMensagemIA(conversa, queryResponse.answer());

        // 5. Retorna a resposta e o ID da conversa
        return new ChatResponse(queryResponse.answer(), conversa.getId());
    }

    @GetMapping
    public ResponseEntity<List<ConversaDTO>> getHistoricoConversas(Authentication authentication) {
        UUID userId = UUID.fromString((String) authentication.getPrincipal());
        List<ConversaDTO> conversas = historicoService.getConversasPorUsuario(userId);
        return ResponseEntity.ok(conversas);
    }

    // --- MÉTODO GET 2 (Atualizado) ---
    @GetMapping("/{chatId}")
    public ResponseEntity<List<MensagemDTO>> getMensagensDaConversa(
            @PathVariable UUID chatId,
            Authentication authentication) {

        UUID userId = UUID.fromString((String) authentication.getPrincipal());

        // Toda a lógica de validação e busca está no serviço agora
        // (Vamos adicionar um try/catch depois)
        List<MensagemDTO> mensagens = historicoService.getMensagensPorConversa(chatId, userId);
        return ResponseEntity.ok(mensagens);
    }
}