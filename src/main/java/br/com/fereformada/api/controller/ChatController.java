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
            @RequestBody ChatRequest request,
            Authentication authentication // Spring Security injeta o usuário autenticado
    ) {


        // 1. Pegar o ID do usuário como String
        String userIdString = (String) authentication.getPrincipal();

        // 2. Converter a String para UUID
        UUID userId = UUID.fromString(userIdString);
        // --- FIM DA CORREÇÃO ---

        // 3. Salvar a pergunta do usuário no banco local
        Conversa conversa = historicoService.salvarMensagemUsuario(
                userId,
                request.question(),
                request.chatId()
        );

        // 4. Chamar seu QueryService (que espera uma String)
        QueryResponse queryResponse = queryService.query(request.question());

        // 5. Extrair a resposta de texto
        // !! CONFIRME AQUI !!
        // Estou assumindo que seu DTO 'QueryResponse' tem um método 'answer()'
        String respostaIA = queryResponse.answer();

        // 6. Salvar a resposta da IA no banco local
        historicoService.salvarMensagemIA(conversa, respostaIA);

        // 7. Retornar a resposta e o ID da conversa para o front-end
        return new ChatResponse(respostaIA, conversa.getId());
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