package br.com.fereformada.api.service;

import br.com.fereformada.api.repository.ChunkBackfillProjection;
import br.com.fereformada.api.repository.ContentChunkRepository;
import com.pgvector.PGvector;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AsyncBackfillWorker {

    private final ContentChunkRepository contentChunkRepository;

    public AsyncBackfillWorker(ContentChunkRepository contentChunkRepository) {
        this.contentChunkRepository = contentChunkRepository;
    }

    /**
     * Este método agora vive em sua própria classe e será
     * chamado externamente, garantindo que a anotação @Transactional
     * seja lida e uma nova transação seja criada.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateBatchInTransaction(List<ChunkBackfillProjection> chunkBatch, List<PGvector> vectorBatch) {
        for (int j = 0; j < chunkBatch.size(); j++) {
            ChunkBackfillProjection projection = chunkBatch.get(j);
            PGvector vector = vectorBatch.get(j);

            if (vector != null) {
                // Chama o novo método do repositório
                contentChunkRepository.setQuestionVector(projection.getId(), vector.toString());
            }
        }
    }
}