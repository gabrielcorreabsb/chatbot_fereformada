package br.com.fereformada.api.controller;

import br.com.fereformada.api.dto.ChunkRequestDTO;
import br.com.fereformada.api.dto.WorkDTO;
import br.com.fereformada.api.model.ContentChunk;
import br.com.fereformada.api.model.Work;
import br.com.fereformada.api.service.ContentAdminService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/admin")
// Protege TODOS os endpoints nesta classe.
// O usuário DEVE ter OU 'ROLE_ADMIN' OU 'ROLE_MODERATOR'.
// Um 'ROLE_USER' normal receberá 403 Forbidden.
@PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
public class ContentAdminController {

    private final ContentAdminService adminService;

    public ContentAdminController(ContentAdminService adminService) {
        this.adminService = adminService;
    }

    // === Endpoints de Obras (Works) ===
    // (Apenas ADMINS podem gerenciar obras)

    @GetMapping("/works")
    public ResponseEntity<Page<Work>> getAllWorks(Pageable pageable) {
        // Moderadores e Admins podem listar
        return ResponseEntity.ok(adminService.findAllWorks(pageable));
    }

    @PostMapping("/works")
    @PreAuthorize("hasRole('ADMIN')") // Sobrescreve: SÓ ADMIN
    public ResponseEntity<Work> createWork(@RequestBody WorkDTO workDto) {
        Work createdWork = adminService.createWork(workDto);
        return ResponseEntity.created(URI.create("/api/admin/works/" + createdWork.getId())).body(createdWork);
    }

    @PutMapping("/works/{workId}")
    @PreAuthorize("hasRole('ADMIN')") // Sobrescreve: SÓ ADMIN
    public ResponseEntity<Work> updateWork(@PathVariable Long workId, @RequestBody WorkDTO workDto) {
        Work updatedWork = adminService.updateWork(workId, workDto);
        return ResponseEntity.ok(updatedWork);
    }

    @DeleteMapping("/works/{workId}")
    @PreAuthorize("hasRole('ADMIN')") // Sobrescreve: SÓ ADMIN
    public ResponseEntity<Void> deleteWork(@PathVariable Long workId) {
        adminService.deleteWork(workId);
        return ResponseEntity.noContent().build();
    }

    // === Endpoints de Conteúdo (Chunks) ===
    // (Admins e Moderadores podem gerenciar chunks)

    @GetMapping("/works/{workId}/chunks")
    public ResponseEntity<Page<ContentChunk>> getChunksForWork(@PathVariable Long workId, Pageable pageable) {
        // Moderadores e Admins podem ler
        return ResponseEntity.ok(adminService.findChunksByWork(workId, pageable));
    }

    @PostMapping("/works/{workId}/chunks")
    public ResponseEntity<ContentChunk> createChunk(@PathVariable Long workId, @RequestBody ChunkRequestDTO chunkDto) {
        // Moderadores e Admins podem criar chunks
        ContentChunk createdChunk = adminService.createChunk(workId, chunkDto);
        return ResponseEntity.created(URI.create("/api/admin/chunks/" + createdChunk.getId())).body(createdChunk);
    }

    @PutMapping("/chunks/{chunkId}")
    public ResponseEntity<ContentChunk> updateChunk(@PathVariable Long chunkId, @RequestBody ChunkRequestDTO chunkDto) {
        // Moderadores e Admins podem atualizar (ex: corrigir typos)
        ContentChunk updatedChunk = adminService.updateChunk(chunkId, chunkDto);
        return ResponseEntity.ok(updatedChunk);
    }

    @DeleteMapping("/chunks/{chunkId}")
    @PreAuthorize("hasRole('ADMIN')") // Sobrescreve: SÓ ADMIN
    public ResponseEntity<Void> deleteChunk(@PathVariable Long chunkId) {
        adminService.deleteChunk(chunkId);
        return ResponseEntity.noContent().build();
    }
}