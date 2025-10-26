package br.com.fereformada.api.controller;


// Imports dos DTOs

import br.com.fereformada.api.dto.ChatRequest;
import br.com.fereformada.api.dto.ChatResponse;
import br.com.fereformada.api.dto.QueryResponse; // Import do seu DTO existente

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

    private final ConversaRepository conversaRepository;
    private final MensagemRepository mensagemRepository;

    // Injetamos os dois serviços
    public ChatController(QueryService queryService, HistoricoService historicoService, ConversaRepository conversaRepository, // NOVO
                          MensagemRepository mensagemRepository) {
        this.queryService = queryService;
        this.historicoService = historicoService;
        this.conversaRepository = conversaRepository;
        this.mensagemRepository = mensagemRepository;
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
    public ResponseEntity<List<Conversa>> getHistoricoConversas(Authentication authentication) {
        // 1. Pega o ID do usuário autenticado
        UUID userId = UUID.fromString((String) authentication.getPrincipal());

        // 2. Busca no repositório
        List<Conversa> conversas = conversaRepository.findByUserIdOrderByCreatedAtDesc(userId);

        // 3. Retorna a lista (mais tarde, podemos usar DTOs aqui)
        return ResponseEntity.ok(conversas);
    }

    @GetMapping("/{chatId}")
    public ResponseEntity<List<Mensagem>> getMensagensDaConversa(
            @PathVariable UUID chatId,
            Authentication authentication) {

        UUID userId = UUID.fromString((String) authentication.getPrincipal());

        // 1. Verificação de Segurança: O usuário é dono desta conversa?
        Conversa conversa = conversaRepository.findById(chatId)
                .orElseThrow(() -> new RuntimeException("Conversa não encontrada")); // Lançar exceção customizada depois

        if (!conversa.getUserId().equals(userId)) {
            // Se o ID do dono da conversa não for o mesmo do usuário logado, negue.
            return ResponseEntity.status(403).build(); // 403 Forbidden
        }

        // 2. Se for o dono, busque as mensagens
        List<Mensagem> mensagens = mensagemRepository.findByConversaIdOrderByCreatedAtAsc(chatId);
        return ResponseEntity.ok(mensagens);
    }
}