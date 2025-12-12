package br.com.fereformada.api.controller;

import br.com.fereformada.api.dto.StudyNoteProjection;
import br.com.fereformada.api.dto.StudyNoteRequestDTO;
import br.com.fereformada.api.dto.StudyNoteSourceDTO;
// import br.com.fereformada.api.model.StudyNote; // <-- Não é mais necessário para o 'create'
import br.com.fereformada.api.service.StudyNoteAdminService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/studynotes")
@PreAuthorize("hasRole('ADMIN')")
public class StudyNoteAdminController {

    private final StudyNoteAdminService studyNoteAdminService;

    public StudyNoteAdminController(StudyNoteAdminService studyNoteAdminService) {
        this.studyNoteAdminService = studyNoteAdminService;
    }

    /**
     * GET /api/admin/studynotes
     * (Este método está correto)
     */
    @GetMapping
    public ResponseEntity<Page<StudyNoteProjection>> getAllNotes(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String source,
            Pageable pageable
    ) {
        Page<StudyNoteProjection> page = studyNoteAdminService.findAll(search, source, pageable);
        return ResponseEntity.ok(page);
    }

    /**
     * GET /api/admin/studynotes/sources
     * (Este método está correto)
     */
    @GetMapping("/sources")
    public ResponseEntity<List<StudyNoteSourceDTO>> getSources() {
        return ResponseEntity.ok(studyNoteAdminService.findStudyNoteCountsBySource());
    }

    /**
     * GET /api/admin/studynotes/{id}
     * (Este método está correto)
     */
    @GetMapping("/{id}")
    public ResponseEntity<StudyNoteProjection> getNoteById(@PathVariable Long id) {
        return ResponseEntity.ok(studyNoteAdminService.findById(id));
    }

    /**
     * ==================================================================
     * MÉTODO 'CREATE' REFATORADO
     * Retorna StudyNoteProjection para evitar o bug de serialização.
     * ==================================================================
     */
    @PostMapping
    public ResponseEntity<StudyNoteProjection> createNote(@RequestBody StudyNoteRequestDTO dto) {
        // O service.create() agora (corretamente) retorna uma projeção
        StudyNoteProjection createdProjection = studyNoteAdminService.create(dto);
        // Retornamos a projeção segura
        return ResponseEntity.ok(createdProjection);
    }

    /**
     * PUT /api/admin/studynotes/{id}
     * (Este método já está correto)
     */
    @PutMapping("/{id}")
    public ResponseEntity<StudyNoteProjection> updateNote(@PathVariable Long id, @RequestBody StudyNoteRequestDTO dto) {
        StudyNoteProjection updatedProjection = studyNoteAdminService.update(id, dto);
        return ResponseEntity.ok(updatedProjection);
    }

    /**
     * DELETE /api/admin/studynotes/{id}
     * (Este método está correto)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteNote(@PathVariable Long id) {
        studyNoteAdminService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/batch")
    public ResponseEntity<String> importBatchNotes(@RequestBody List<StudyNoteRequestDTO> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            return ResponseEntity.badRequest().body("Lista vazia.");
        }

        // Dispara o processo em outra thread (Fire-and-forget)
        studyNoteAdminService.importBatchAsync(dtos);

        // Responde na hora para o usuário não ficar esperando
        return ResponseEntity.accepted()
                .body("Importação iniciada em segundo plano! " +
                        "São " + dtos.size() + " itens. " +
                        "Acompanhe os logs do servidor para ver o progresso.");
    }
}