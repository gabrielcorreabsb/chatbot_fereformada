package br.com.fereformada.api.service;

import br.com.fereformada.api.dto.StudyNoteProjection;
import br.com.fereformada.api.dto.StudyNoteRequestDTO;
import br.com.fereformada.api.dto.StudyNoteSourceDTO;
import br.com.fereformada.api.model.StudyNote;
import br.com.fereformada.api.repository.StudyNoteRepository;
import com.pgvector.PGvector;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
// import org.springframework.orm.jpa.JpaSystemException; // <-- Não é mais necessário

import java.util.List;

@Service
public class StudyNoteAdminService {

    private final StudyNoteRepository studyNoteRepository;
    private final GeminiApiClient geminiApiClient;
    private static final Logger logger = LoggerFactory.getLogger(StudyNoteAdminService.class);

    public StudyNoteAdminService(StudyNoteRepository studyNoteRepository, GeminiApiClient geminiApiClient) {
        this.studyNoteRepository = studyNoteRepository;
        this.geminiApiClient = geminiApiClient;
    }

    /**
     * Lida com busca, fonte, ambos ou nenhum.
     * (Este método está correto)
     */
    @Transactional(readOnly = true)
    public Page<StudyNoteProjection> findAll(String search, String source, Pageable pageable) {
        boolean hasSearch = search != null && !search.isBlank();
        boolean hasSource = source != null && !source.isBlank();

        if (hasSearch && hasSource) {
            return studyNoteRepository.searchAllProjectionBySource(search, source, pageable);
        } else if (hasSearch) {
            return studyNoteRepository.searchAllProjection(search, pageable);
        } else if (hasSource) {
            return studyNoteRepository.findAllProjectionBySource(source, pageable);
        } else {
            return studyNoteRepository.findAllProjection(pageable);
        }
    }

    /**
     * Retorna a lista de fontes com suas contagens.
     * (Este método está correto)
     */
    @Transactional(readOnly = true)
    public List<StudyNoteSourceDTO> findStudyNoteCountsBySource() {
        return studyNoteRepository.getStudyNoteCountsBySource();
    }

    /**
     * Busca uma única nota por ID (para o modal "Editar").
     * (Este método está correto)
     */
    @Transactional(readOnly = true)
    public StudyNoteProjection findById(Long id) {
        return studyNoteRepository.findProjectionById(id)
                .orElseThrow(() -> new EntityNotFoundException("Nota de Estudo não encontrada: " + id));
    }

    /**
     * ==================================================================
     * MÉTODO 'CREATE' REFATORADO
     * Retorna uma Projeção segura em vez da entidade completa,
     * evitando o bug de serialização caso a vetorização falhe.
     * ==================================================================
     */
    @Transactional
    public StudyNoteProjection create(StudyNoteRequestDTO dto) {
        StudyNote note = new StudyNote();
        dto.toEntity(note);
        vectorizeStudyNote(note); // Tenta vetorizar

        StudyNote savedNote = studyNoteRepository.save(note); // Salva

        // Retorna a projeção segura, que não inclui o vetor
        return studyNoteRepository.findProjectionById(savedNote.getId())
                .orElseThrow(() -> new IllegalStateException("Falha ao buscar projeção da nota recém-criada."));
    }

    /**
     * ==================================================================
     * MÉTODO 'UPDATE' REFATORADO (Padrão "Chunk")
     * Esta versão NUNCA carrega a entidade completa.
     * Ela usa projeções para ler e @Modifying para escrever.
     * ==================================================================
     */
    @Transactional
    public StudyNoteProjection update(Long id, StudyNoteRequestDTO dto) {

        // 1. Busca a projeção antiga (SEGURO)
        StudyNoteProjection oldProjection = studyNoteRepository.findProjectionById(id)
                .orElseThrow(() -> new EntityNotFoundException("Nota de Estudo não encontrada: " + id));

        // 2. Compara o conteúdo antigo (da projeção) com o novo (do DTO)
        boolean contentChanged = !oldProjection.noteContent().equals(dto.noteContent());

        if (contentChanged) {
            // 3. CAMINHO A: O conteúdo mudou.
            logger.info("Conteúdo da nota {} mudou. (Re)vetorizando e atualizando tudo...", id);

            // Gera um novo vetor
            float[] newVector = getVectorFromDto(dto); // (Helper que já criamos)

            // Usa a query que atualiza TUDO (incluindo o vetor)
            studyNoteRepository.updateNoteBypassingLoad(
                    id,
                    dto.book(),
                    dto.startChapter(),
                    dto.startVerse(),
                    dto.endChapter(),
                    dto.endVerse(),
                    dto.noteContent(),
                    dto.source(),
                    newVector
            );
        } else {
            // 4. CAMINHO B: O conteúdo NÃO mudou.
            logger.info("Conteúdo da nota {} não mudou. Atualizando apenas metadados...", id);

            // Usa a nova query que atualiza tudo, EXCETO o vetor
            studyNoteRepository.updateNoteMetadataBypassingLoad(
                    id,
                    dto.book(),
                    dto.startChapter(),
                    dto.startVerse(),
                    dto.endChapter(),
                    dto.endVerse(),
                    dto.noteContent(),
                    dto.source()
            );
        }

        // 5. Busca a projeção ATUALIZADA (SEGURO) para retornar ao frontend
        return studyNoteRepository.findProjectionById(id)
                .orElseThrow(() -> new IllegalStateException("Falha ao buscar projeção pós-update da nota: " + id));
    }


    /**
     * ==================================================================
     * MÉTODO 'DELETE' REFATORADO
     * Usa uma query @Modifying para deletar sem carregar a entidade,
     * evitando o bug "load-on-delete".
     * ==================================================================
     */
    @Transactional
    public void delete(Long id) {
        if (!studyNoteRepository.existsById(id)) { // existsById é seguro
            throw new EntityNotFoundException("Nota de Estudo não encontrada: " + id);
        }

        // Chama a query de delete customizada (assumindo que você a adicionou ao repo)
        studyNoteRepository.deleteNoteByIdBypassingLoad(id);
    }

    // ==================================================================
    // HELPERS DE VETORIZAÇÃO (Mantidos como estavam)
    // ==================================================================

    /**
     * Helper para o 'Caminho Triste' (catch): Vetoriza direto do DTO.
     */
    private float[] getVectorFromDto(StudyNoteRequestDTO dto) {
        String textToEmbed = dto.book() + " " +
                dto.startChapter() + ":" + dto.startVerse() + "\n" +
                dto.noteContent();
        try {
            PGvector vector = geminiApiClient.generateEmbedding(textToEmbed);
            return vector.toArray();
        } catch (Exception e) {
            logger.error("Falha ao vetorizar DTO para Nota de Estudo: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Helper de vetorização para a entidade (usado no 'create').
     */
    private void vectorizeStudyNote(StudyNote note) {
        String textToEmbed = note.getBook() + " " +
                note.getStartChapter() + ":" + note.getStartVerse() + "\n" +
                note.getNoteContent();

        try {
            PGvector vector = geminiApiClient.generateEmbedding(textToEmbed);
            note.setNoteVector(vector.toArray());
        } catch (Exception e) {
            logger.error("Falha ao vetorizar Nota de Estudo {}: {}", note.getId(), e.getMessage());
            note.setNoteVector(null);
        }
    }
}