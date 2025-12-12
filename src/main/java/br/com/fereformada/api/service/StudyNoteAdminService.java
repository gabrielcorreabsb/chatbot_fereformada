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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
// import org.springframework.orm.jpa.JpaSystemException; // <-- Não é mais necessário

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class StudyNoteAdminService {

    private static final int BATCH_SIZE = 50;

    private final StudyNoteRepository studyNoteRepository;
    private final GeminiApiClient geminiApiClient;
    private final StudyNoteBatchService studyNoteBatchService;
    private static final Logger logger = LoggerFactory.getLogger(StudyNoteAdminService.class);

    public StudyNoteAdminService(StudyNoteRepository studyNoteRepository,
                                 GeminiApiClient geminiApiClient,
                                 StudyNoteBatchService studyNoteBatchService) {
        this.studyNoteRepository = studyNoteRepository;
        this.geminiApiClient = geminiApiClient;
        this.studyNoteBatchService = studyNoteBatchService;
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

    @Async
    public void importBatchAsync(List<StudyNoteRequestDTO> dtos) {
        long startTime = System.currentTimeMillis();
        int total = dtos.size();
        logger.info("🚀 [ASYNC] Iniciando importação OTIMIZADA de {} notas.", total);

        // Listas temporárias para montar o lote
        List<StudyNote> notesBatch = new ArrayList<>(BATCH_SIZE);
        List<String> textsToEmbedBatch = new ArrayList<>(BATCH_SIZE);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < total; i++) {
            StudyNoteRequestDTO dto = dtos.get(i);

            try {
                // 1. Converte DTO para Entidade (rápido, memória apenas)
                StudyNote note = new StudyNote();
                dto.toEntity(note);

                // 2. Prepara o texto para vetorização (mas NÃO chama a API ainda)
                String textToEmbed = note.getBook() + " " +
                        note.getStartChapter() + ":" + note.getStartVerse() + "\n" +
                        note.getNoteContent();

                notesBatch.add(note);
                textsToEmbedBatch.add(textToEmbed);

                // 3. Se o lote encheu (ou é o último item), processa
                if (notesBatch.size() >= BATCH_SIZE || i == total - 1) {
                    processAndSaveBatch(notesBatch, textsToEmbedBatch, successCount, errorCount);

                    // Limpa os baldes para o próximo lote
                    notesBatch.clear();
                    textsToEmbedBatch.clear();

                    logger.info("📦 [PROGRESSO] {}/{} notas processadas...", (i + 1), total);
                }

            } catch (Exception e) {
                logger.error("❌ Erro estrutural ao preparar item {}: {}", i, e.getMessage());
                errorCount.incrementAndGet();
            }
        }

        long duration = (System.currentTimeMillis() - startTime) / 1000;
        logger.info("🏁 [FIM] Importação concluída em {}s. Sucessos: {}, Erros: {}",
                duration, successCount.get(), errorCount.get());
    }

    /**
     * Processa um lote: Envia todos os textos para o Gemini de uma vez,
     * associa os vetores retornados às notas e salva no banco.
     */
    private void processAndSaveBatch(List<StudyNote> notes,
                                     List<String> texts,
                                     AtomicInteger successCount,
                                     AtomicInteger errorCount) {
        try {
            // A. Vetorização em Lote (1 chamada de rede para N itens)
            // IMPORTANTE: Assumindo que geminiApiClient.generateEmbeddingsInBatch existe
            // e retorna a lista na MESMA ORDEM dos textos enviados.
            List<PGvector> vectors = geminiApiClient.generateEmbeddingsInBatch(texts);

            if (vectors.size() != notes.size()) {
                throw new IllegalStateException("Discrepância entre notas enviadas e vetores retornados pela API.");
            }

            // B. Atribui os vetores às entidades
            for (int k = 0; k < notes.size(); k++) {
                PGvector pgVector = vectors.get(k);
                if (pgVector != null) {
                    notes.get(k).setNoteVector(pgVector.toArray());
                }
            }

            // C. Salva no banco (Transacional via BatchService)
            studyNoteBatchService.saveBatch(notes);
            successCount.addAndGet(notes.size());

        } catch (Exception e) {
            logger.error("❌ Falha ao processar lote de vetorização/banco: {}", e.getMessage());
            // Se falhar o lote inteiro, contamos como erro
            errorCount.addAndGet(notes.size());
        }
    }
}