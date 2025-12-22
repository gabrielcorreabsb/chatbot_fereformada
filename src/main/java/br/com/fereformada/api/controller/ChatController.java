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
@RequestMapping("/api/chat")
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
        ChatRequest updatedRequest = new ChatRequest(request.question(), conversa.getId());
        QueryServiceResult queryResult = queryService.query(updatedRequest);

        // 3. Retorna a resposta COM O ID DA MENSAGEM
        return new ChatApiResponse(
                queryResult.answer(),
                queryResult.references(),
                conversa.getId(),
                queryResult.messageId()
        );
    }

    @GetMapping
    public ResponseEntity<List<ConversaDTO>> getHistoricoConversas(Authentication authentication) {
        UUID userId = UUID.fromString((String) authentication.getPrincipal());
        return ResponseEntity.ok(historicoService.getConversasPorUsuario(userId));
    }

    @GetMapping("/{chatId}")
    public ResponseEntity<List<MensagemDTO>> getChatHistory(@PathVariable UUID chatId) {
        List<MensagemDTO> history = historicoService.getMensagensPorConversa(chatId);
        return ResponseEntity.ok(history);
    }
}