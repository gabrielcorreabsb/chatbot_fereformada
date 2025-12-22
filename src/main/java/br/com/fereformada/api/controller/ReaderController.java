package br.com.fereformada.api.controller;

import br.com.fereformada.api.dto.ReaderChunkDTO;
import br.com.fereformada.api.dto.ReaderNoteDTO;
import br.com.fereformada.api.repository.ContentChunkRepository;
import br.com.fereformada.api.repository.StudyNoteRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/leitor")
public class ReaderController {

    private final ContentChunkRepository contentChunkRepository;
    private final StudyNoteRepository studyNoteRepository;

    public ReaderController(ContentChunkRepository contentChunkRepository,
                            StudyNoteRepository studyNoteRepository) {
        this.contentChunkRepository = contentChunkRepository;
        this.studyNoteRepository = studyNoteRepository;
    }

    @GetMapping("/obras/{acronym}/{chapter}")
    public List<ReaderChunkDTO> getWorkChapter(@PathVariable String acronym,
                                               @PathVariable Integer chapter) {
        return contentChunkRepository.findContentForReader(acronym, chapter);
    }

    @GetMapping("/biblia/{book}/{chapter}")
    public List<ReaderNoteDTO> getBibleChapter(@PathVariable String book,
                                               @PathVariable Integer chapter) {
        // Normaliza o nome do livro (remove espaços extras e decode URL)
        return studyNoteRepository.findNotesForReader(book.trim(), chapter);
    }
}