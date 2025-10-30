package br.com.fereformada.api.controller;

// Imports dos DTOs
import br.com.fereformada.api.dto.*; // Importa todos os DTOs

// Imports dos Serviços
import br.com.fereformada.api.service.QueryService;
import br.com.fereformada.api.service.HistoricoService;

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

    /**
     * Ponto de entrada principal para uma nova mensagem de chat.
     */
    @PostMapping
    // 👇 O tipo de retorno agora é o nosso novo DTO
    public ChatApiResponse handleChat(
            @Valid @RequestBody ChatRequest request,
            Authentication authentication
    ) {
        UUID userId = UUID.fromString((String) authentication.getPrincipal());

        // 1. Salva a pergunta do usuário e obtém a conversa (nova ou existente)
        Conversa conversa = historicoService.salvarMensagemUsuario(
                userId,
                request.question(),
                request.chatId()
        );

        // 2. Cria um novo ChatRequest ATUALIZADO com o ID da conversa
        ChatRequest updatedRequest = new ChatRequest(request.question(), conversa.getId());

        // 3. Chama o QueryService (que agora retorna QueryServiceResult)
        // (Este é o seu próximo passo de refatoração no QueryService)
        QueryServiceResult queryResult = queryService.query(updatedRequest);

        // 4. Salva a resposta da IA na mesma conversa
        historicoService.salvarMensagemIA(conversa, queryResult.answer());

        // 5. Monta e retorna a resposta final completa para o front-end
        return new ChatApiResponse(
                queryResult.answer(),      // A resposta da IA
                queryResult.references(),  // A lista de fontes clicáveis
                conversa.getId()           // O ID do chat
        );
    }

    /**
     * Retorna a lista de conversas do usuário (para a sidebar).
     */
    @GetMapping
    public ResponseEntity<List<ConversaDTO>> getHistoricoConversas(Authentication authentication) {
        UUID userId = UUID.fromString((String) authentication.getPrincipal());
        List<ConversaDTO> conversas = historicoService.getConversasPorUsuario(userId);
        return ResponseEntity.ok(conversas);
    }

    /**
     * Retorna todas as mensagens de uma conversa específica.
     */
    @GetMapping("/{chatId}")
    public ResponseEntity<List<MensagemDTO>> getMensagensDaConversa(
            @PathVariable UUID chatId,
            Authentication authentication) {

        UUID userId = UUID.fromString((String) authentication.getPrincipal());

        // (A lógica de segurança e busca já está no serviço)
        List<MensagemDTO> mensagens = historicoService.getMensagensPorConversa(chatId, userId);
        return ResponseEntity.ok(mensagens);
    }
}