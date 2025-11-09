package br.com.fereformada.api.controller;

import br.com.fereformada.api.dto.StudyNoteProjection;
import br.com.fereformada.api.dto.StudyNoteRequestDTO; // (Precisaremos criar este)
import br.com.fereformada.api.model.StudyNote;
import br.com.fereformada.api.service.StudyNoteAdminService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/studynotes")
@PreAuthorize("hasRole('ADMIN')") // Apenas Admins podem gerenciar notas
public class StudyNoteAdminController {

    private final StudyNoteAdminService studyNoteAdminService;

    public StudyNoteAdminController(StudyNoteAdminService studyNoteAdminService) {
        this.studyNoteAdminService = studyNoteAdminService;
    }

    /**
     * GET /api/admin/studynotes
     * Lista ou busca todas as notas com paginação.
     */
    @GetMapping
    public ResponseEntity<Page<StudyNoteProjection>> getAllNotes(
            @RequestParam(required = false) String search,
            Pageable pageable
    ) {
        Page<StudyNoteProjection> page = studyNoteAdminService.findAll(search, pageable);
        return ResponseEntity.ok(page);
    }

    /**
     * GET /api/admin/studynotes/{id}
     * Busca uma única nota (usando projeção) para o modal "Editar".
     */
    @GetMapping("/{id}")
    public ResponseEntity<StudyNoteProjection> getNoteById(@PathVariable Long id) {
        return ResponseEntity.ok(studyNoteAdminService.findById(id));
    }

    /**
     * POST /api/admin/studynotes
     * Cria uma nova nota de estudo.
     */
    @PostMapping
    public ResponseEntity<StudyNote> createNote(@RequestBody StudyNoteRequestDTO dto) {
        StudyNote createdNote = studyNoteAdminService.create(dto);
        return ResponseEntity.ok(createdNote);
    }

    /**
     * PUT /api/admin/studynotes/{id}
     * Atualiza uma nota de estudo.
     */
    @PutMapping("/{id}")
    public ResponseEntity<StudyNote> updateNote(@PathVariable Long id, @RequestBody StudyNoteRequestDTO dto) {
        StudyNote updatedNote = studyNoteAdminService.update(id, dto);
        return ResponseEntity.ok(updatedNote);
    }

    /**
     * DELETE /api/admin/studynotes/{id}
     * Deleta uma nota de estudo.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteNote(@PathVariable Long id) {
        studyNoteAdminService.delete(id);
        return ResponseEntity.noContent().build();
    }
}