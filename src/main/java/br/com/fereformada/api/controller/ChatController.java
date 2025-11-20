package br.com.fereformada.api.controller;

import br.com.fereformada.api.dto.*;
import br.com.fereformada.api.model.Conversa;
import br.com.fereformada.api.service.QueryService;
import br.com.fereformada.api.service.HistoricoService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final QueryService queryService;
    private final HistoricoService historicoService;

    public ChatController(QueryService queryService, HistoricoService historicoService) {
        this.queryService = queryService;
        this.historicoService = historicoService;
    }

    @PostMapping
    public ChatApiResponse handleChat(@Valid @RequestBody ChatRequest request, Authentication authentication) {
        UUID userId = UUID.fromString((String) authentication.getPrincipal());

        // 1. Salva a pergunta do usuário
        Conversa conversa = historicoService.salvarMensagemUsuario(userId, request.question(), request.chatId());

        // 2. Chama o QueryService
        // (O QueryService JÁ ESTÁ SALVANDO a resposta da IA com as referências no banco)
        ChatRequest updatedRequest = new ChatRequest(request.question(), conversa.getId());
        QueryServiceResult queryResult = queryService.query(updatedRequest);

        // 🔴 REMOVIDO: historicoService.salvarMensagemIA(...)
        // Motivo: O QueryService já salvou a mensagem com as referências.
        // Se deixarmos essa linha, vai duplicar a mensagem no banco.

        // 3. Retorna a resposta
        return new ChatApiResponse(
                queryResult.answer(),
                queryResult.references(),
                conversa.getId()
        );
    }

    @GetMapping
    public ResponseEntity<List<ConversaDTO>> getHistoricoConversas(Authentication authentication) {
        UUID userId = UUID.fromString((String) authentication.getPrincipal());
        return ResponseEntity.ok(historicoService.getConversasPorUsuario(userId));
    }

    /**
     * Retorna todas as mensagens de uma conversa específica.
     */
    @GetMapping("/{chatId}")
    public ResponseEntity<List<MensagemDTO>> getChatHistory(@PathVariable UUID chatId) {
        // CORREÇÃO: Delegamos a busca para o HistoricoService (que tem acesso ao Repository)
        // Isso resolve o erro de "mensagemRepository" não encontrado aqui.
        List<MensagemDTO> history = historicoService.getMensagensPorConversa(chatId);
        return ResponseEntity.ok(history);
    }
}