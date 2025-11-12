package br.com.fereformada.api.controller;

import br.com.fereformada.api.dto.*;
import br.com.fereformada.api.model.Author;
import br.com.fereformada.api.model.Topic;
import br.com.fereformada.api.service.AsyncImportService;
import br.com.fereformada.api.service.ContentAdminService; // Removido Repos
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
public class ContentAdminController {

    private final ContentAdminService adminService;
    private static final Logger logger = LoggerFactory.getLogger(ContentAdminController.class);
    // Removemos os repositórios, o Serviço agora lida com tudo

    private final AsyncImportService asyncImportService;

    public ContentAdminController(ContentAdminService adminService, AsyncImportService asyncImportService) {
        this.adminService = adminService;
        this.asyncImportService = asyncImportService;
    }

    // === Endpoints de Obras (Works) ===

    @GetMapping("/works")
    public ResponseEntity<Page<WorkResponseDTO>> getAllWorks(Pageable pageable) {
        return ResponseEntity.ok(adminService.findAllWorks(pageable));
    }

    // ======================================================
    // ESTE É UM DOS MÉTODOS QUE ESTAVA FALTANDO (PROVAVELMENTE)
    // ======================================================
    @GetMapping("/works/{workId}")
    public ResponseEntity<WorkResponseDTO> getWorkById(@PathVariable Long workId) {
        // Você precisará adicionar o método 'findWorkById' ao seu AdminService
        // Por agora, vamos assumir que o React só precisa do 'findAllWorks'
        // Mas se o 'ChunkManagement' busca o 'work', ele precisa disso.
        // Vamos adicionar o 'findWorkById' ao serviço.
        return ResponseEntity.ok(adminService.findWorkById(workId));
    }

    @PostMapping("/works")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<WorkResponseDTO> createWork(@RequestBody WorkDTO workDto) {
        WorkResponseDTO createdWork = adminService.createWork(workDto);
        return ResponseEntity.created(URI.create("/api/admin/works/" + createdWork.id())).body(createdWork);
    }

    @PutMapping("/works/{workId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<WorkResponseDTO> updateWork(@PathVariable Long workId, @RequestBody WorkDTO workDto) {
        WorkResponseDTO updatedWork = adminService.updateWork(workId, workDto);
        return ResponseEntity.ok(updatedWork);
    }

    @DeleteMapping("/works/{workId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteWork(@PathVariable Long workId) {
        adminService.deleteWork(workId);
        return ResponseEntity.noContent().build();
    }

    // === Endpoints de Conteúdo (Chunks) ===

    // ======================================================
    // ESTE É O MÉTODO QUE ESTAVA FALTANDO (PROVAVELMENTE)
    // ======================================================
    @GetMapping("/works/{workId}/chunks")
    @PreAuthorize("hasRole('MODERATOR') or hasRole('ADMIN')")
    public ResponseEntity<Page<ChunkResponseDTO>> getChunksForWork(
            @PathVariable Long workId,
            Pageable pageable, // <-- Isto já cuida da página E da classificação
            @RequestParam(required = false) String search // <-- 1. NOSSO NOVO PARÂMETRO
    ) {
        Page<ChunkResponseDTO> page = adminService.findChunksByWork(
                workId,
                pageable,
                search // <-- 2. Passamos para o serviço
        );
        return ResponseEntity.ok(page);
    }

    @PostMapping("/works/{workId}/chunks")
    public ResponseEntity<ChunkResponseDTO> createChunk(@PathVariable Long workId, @RequestBody ChunkRequestDTO chunkDto) {
        ChunkResponseDTO createdChunk = adminService.createChunk(workId, chunkDto);
        return ResponseEntity.created(URI.create("/api/admin/chunks/" + createdChunk.getId())).body(createdChunk);
    }

    @PutMapping("/chunks/{chunkId}")
    public ResponseEntity<ChunkResponseDTO> updateChunk(@PathVariable Long chunkId, @RequestBody ChunkRequestDTO chunkDto) {
        ChunkResponseDTO updatedChunk = adminService.updateChunk(chunkId, chunkDto);
        return ResponseEntity.ok(updatedChunk);
    }

    @DeleteMapping("/chunks/{chunkId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteChunk(@PathVariable Long chunkId) {
        adminService.deleteChunk(chunkId);
        return ResponseEntity.noContent().build();
    }

    // === Endpoints de Autores (Authors) ===

    @GetMapping("/authors")
    public ResponseEntity<Page<AuthorDTO>> getAllAuthors(Pageable pageable) {
        return ResponseEntity.ok(adminService.findAllAuthors(pageable));
    }

    @GetMapping("/authors/all")
    public ResponseEntity<List<AuthorDTO>> getAllAuthorsList() {
        List<AuthorDTO> authors = adminService.findAllAuthorsList();
        return ResponseEntity.ok(authors);
    }

    @PostMapping("/authors")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AuthorDTO> createAuthor(@RequestBody AuthorDTO authorDto) {
        AuthorDTO createdAuthor = adminService.createAuthor(authorDto);
        return ResponseEntity.created(URI.create("/api/admin/authors/" + createdAuthor.id())).body(createdAuthor);
    }

    @PutMapping("/authors/{authorId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AuthorDTO> updateAuthor(@PathVariable Long authorId, @RequestBody AuthorDTO authorDto) {
        AuthorDTO updatedAuthor = adminService.updateAuthor(authorId, authorDto);
        return ResponseEntity.ok(updatedAuthor);
    }

    @DeleteMapping("/authors/{authorId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteAuthor(@PathVariable Long authorId) {
        adminService.deleteAuthor(authorId);
        return ResponseEntity.noContent().build();
    }

    // === Endpoints de Tópicos (Topics) ===

    @GetMapping("/topics")
    public ResponseEntity<Page<TopicDTO>> getAllTopics(Pageable pageable) {
        return ResponseEntity.ok(adminService.findAllTopics(pageable));
    }

    @GetMapping("/topics/all")
    public ResponseEntity<List<TopicDTO>> getAllTopicsList() {
        List<TopicDTO> topics = adminService.findAllTopicsList();
        return ResponseEntity.ok(topics);
    }

    @PostMapping("/topics")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TopicDTO> createTopic(@RequestBody TopicDTO topicDto) {
        TopicDTO createdTopic = adminService.createTopic(topicDto);
        return ResponseEntity.created(URI.create("/api/admin/topics/" + createdTopic.id())).body(createdTopic);
    }

    @PutMapping("/topics/{topicId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TopicDTO> updateTopic(@PathVariable Long topicId, @RequestBody TopicDTO topicDto) {
        TopicDTO updatedTopic = adminService.updateTopic(topicId, topicDto);
        return ResponseEntity.ok(updatedTopic);
    }

    @DeleteMapping("/topics/{topicId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteTopic(@PathVariable Long topicId) {
        adminService.deleteTopic(topicId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/chunks/bulk-import")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ImportTaskDTO> bulkImportChunks(@RequestBody List<ChunkImportDTO> dtoList) {
        try {
            // 3. ETAPA 1: Criar a tarefa (Isso é uma transação completa que faz commit)
            ImportTaskDTO taskDTO = adminService.createImportTask(dtoList);

            // 4. ETAPA 2: Chamar o método @Async (AGORA a tarefa já existe no DB)
            asyncImportService.processImport(taskDTO.id(), dtoList);

            // 5. Retornar HTTP 202 (Accepted) com o ID da tarefa
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(taskDTO);

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(null); // Simplificado
        }
    }

    @GetMapping("/chunks/{chunkId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<ChunkResponseDTO> getChunkById(@PathVariable Long chunkId) {
        // Este método 'findChunkById' JÁ EXISTE no seu ContentAdminService
        ChunkResponseDTO chunk = adminService.findChunkById(chunkId);
        return ResponseEntity.ok(chunk);
    }

    @GetMapping("/import-tasks/{taskId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ImportTaskDTO> getImportTaskStatus(@PathVariable Long taskId) {
        try {
            ImportTaskDTO taskDTO = adminService.getTaskStatus(taskId);
            return ResponseEntity.ok(taskDTO);
        } catch (EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/chunks/bulk-delete")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> bulkDeleteChunks(@RequestBody IdListDTO idListDTO) {
        adminService.bulkDeleteChunks(idListDTO.ids());
        return ResponseEntity.noContent().build();
    }

    /**
     * Endpoint para ADICIONAR Tópicos em massa.
     * Recebe nosso novo DTO.
     */
    @PostMapping("/chunks/bulk-add-topics")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> bulkAddTopicsToChunks(@RequestBody BulkTopicDTO bulkTopicDTO) {
        adminService.bulkAddTopics(bulkTopicDTO);
        return ResponseEntity.noContent().build();
    }

    record IdListDTO(List<Long> ids) {}

    @GetMapping("/synonyms")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<TheologicalSynonymDTO>> getAllSynonyms() {
        return ResponseEntity.ok(adminService.findAllSynonyms());
    }

    @PostMapping("/synonyms")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TheologicalSynonymDTO> createSynonym(@Valid @RequestBody TheologicalSynonymDTO dto) {
        TheologicalSynonymDTO createdSynonym = adminService.createSynonym(dto);
        return ResponseEntity.created(URI.create("/api/admin/synonyms/" + createdSynonym.id())).body(createdSynonym);
    }

    @DeleteMapping("/synonyms/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteSynonym(@PathVariable Long id) {
        adminService.deleteSynonym(id);
        return ResponseEntity.noContent().build();
    }
}