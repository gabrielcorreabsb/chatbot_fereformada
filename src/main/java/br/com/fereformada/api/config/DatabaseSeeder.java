
package br.com.fereformada.api.config;

import br.com.fereformada.api.model.Author;
import br.com.fereformada.api.model.ContentChunk;
import br.com.fereformada.api.model.Topic;
import br.com.fereformada.api.model.Work;
import br.com.fereformada.api.repository.AuthorRepository;
import br.com.fereformada.api.repository.ContentChunkRepository;
import br.com.fereformada.api.repository.TopicRepository;
import br.com.fereformada.api.repository.WorkRepository;
import br.com.fereformada.api.service.ChunkingService;
import br.com.fereformada.api.service.TaggingService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class DatabaseSeeder implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseSeeder.class);

    private final AuthorRepository authorRepository;
    private final WorkRepository workRepository;
    private final TopicRepository topicRepository;
    private final ContentChunkRepository contentChunkRepository;
    private final ResourceLoader resourceLoader;
    private final ChunkingService chunkingService;
    private final TaggingService taggingService;

    public DatabaseSeeder(AuthorRepository authorRepository, WorkRepository workRepository,
                          TopicRepository topicRepository, ContentChunkRepository contentChunkRepository,
                          ResourceLoader resourceLoader, ChunkingService chunkingService,
                          TaggingService taggingService) {
        this.authorRepository = authorRepository;
        this.workRepository = workRepository;
        this.topicRepository = topicRepository;
        this.contentChunkRepository = contentChunkRepository;
        this.resourceLoader = resourceLoader;
        this.chunkingService = chunkingService;
        this.taggingService = taggingService;
    }

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        logger.info("Limpando o banco de dados para uma nova carga...");
        contentChunkRepository.deleteAll();
        workRepository.deleteAll();
        topicRepository.deleteAll();
        authorRepository.deleteAll();

        logger.info("Iniciando a carga de dados completa via Seeder...");

        // --- DEFINIÇÃO DE TÓPICOS ---
        Topic scripturesTopic = createTopic("Sagradas Escrituras", "A doutrina sobre a revelação de Deus na Bíblia.");
        Topic godAndTrinityTopic = createTopic("Deus e a Santíssima Trindade", "A doutrina sobre o ser, os atributos de Deus e a Trindade.");
        Topic decreesTopic = createTopic("Decretos de Deus", "A doutrina sobre os decretos eternos de Deus, incluindo a predestinação.");
        Topic creationTopic = createTopic("Criação", "A doutrina sobre a criação do mundo e do homem por Deus.");
        Topic providenceTopic = createTopic("Providência", "A doutrina sobre o sustento e governo de Deus sobre todas as coisas.");
        Topic fallAndSinTopic = createTopic("A Queda e o Pecado", "A doutrina sobre a queda do homem, o pecado original e atual.");
        Topic covenantTopic = createTopic("Pacto de Deus", "A doutrina sobre os pactos de Deus com o homem (obras e graça).");
        Topic christMediatorTopic = createTopic("Cristo, o Mediador", "A doutrina sobre a pessoa e a obra de Jesus Cristo como mediador.");
        Topic freeWillTopic = createTopic("Livre-Arbítrio", "A doutrina sobre a vontade do homem em seus diferentes estados (inocência, pecado, graça, glória).");
        Topic effectualCallingTopic = createTopic("Vocação Eficaz", "A doutrina sobre o chamado eficaz de Deus aos eleitos.");
        Topic justificationTopic = createTopic("Justificação pela Fé", "A doutrina de como o pecador é declarado justo diante de Deus.");
        Topic lawOfGodTopic = createTopic("A Lei de Deus", "Os mandamentos e estatutos divinos revelados nas Escrituras.");

        List<Topic> allTopics = List.of(
                scripturesTopic, godAndTrinityTopic, decreesTopic, creationTopic, providenceTopic,
                fallAndSinTopic, covenantTopic, christMediatorTopic, freeWillTopic,
                effectualCallingTopic, justificationTopic, lawOfGodTopic
        );

        taggingService.initializeRules(allTopics);

        Author westminsterAssembly = createAuthor("Assembleia de Westminster", "Puritanos", "1643-01-01", "1653-01-01");
        Author calvin = createAuthor("João Calvino", "Reforma", "1509-07-10", "1564-05-27");


        loadWestminsterConfession(westminsterAssembly, allTopics);
        loadWestminsterCatechism(westminsterAssembly, allTopics);
        loadShorterCatechism(westminsterAssembly, allTopics);
        loadCalvinInstitutes(calvin, allTopics);

        logger.info("Carga de dados finalizada com sucesso.");
    }

    private void loadWestminsterConfession(Author author, List<Topic> availableTopics) throws IOException {
        final String WORK_TITLE = "Confissão de Fé de Westminster";
        if (workRepository.findByTitle(WORK_TITLE).isPresent()) { return; }
        logger.info("Carregando e catalogando '{}'...", WORK_TITLE);
        Work confession = createWork(WORK_TITLE, author, 1646, "CONFISSAO");
        String rawText = extractTextFromPdf("classpath:data-content/pdf/confissao_westminster.pdf");

        List<ChunkingService.ParsedChunk> parsedChunks = chunkingService.parseWestminsterConfession(rawText);
        logger.info("Encontrados e catalogados {} chunks (seções) na Confissão.", parsedChunks.size());

        for (var parsedChunk : parsedChunks) {
            String cleanedContent = cleanChunkText(parsedChunk.content());
            if (cleanedContent.isBlank() || cleanedContent.length() < 20) continue;

            ContentChunk chunk = new ContentChunk();
            chunk.setChapterNumber(parsedChunk.chapterNumber());
            chunk.setChapterTitle(parsedChunk.chapterTitle());
            chunk.setSectionNumber(parsedChunk.sectionNumber());
            chunk.setContent(cleanedContent);
            chunk.setWork(confession);
            chunk.setTopics(taggingService.getTagsFor(parsedChunk.chapterTitle(), cleanedContent));
            contentChunkRepository.save(chunk);
        }
    }
    private void loadWestminsterCatechism(Author author, List<Topic> availableTopics) throws IOException {
        final String WORK_TITLE = "Catecismo Maior de Westminster";
        if (workRepository.findByTitle(WORK_TITLE).isPresent()) {
            return;
        }
        logger.info("Carregando e catalogando '{}'...", WORK_TITLE);

        Work catechism = createWork(WORK_TITLE, author, 1648, "CATECISMO");
        String rawText = extractTextFromPdf("classpath:data-content/pdf/catecismo_maior_westminster.pdf");

        List<ChunkingService.ParsedQuestionChunk> parsedChunks = chunkingService.parseWestminsterLargerCatechism(rawText);

        logger.info("Encontrados e catalogados {} chunks no Catecismo Maior.", parsedChunks.size());

        for (var parsedChunk : parsedChunks) {
            String cleanedAnswer = cleanChunkText(parsedChunk.answer());
            if (cleanedAnswer.isBlank() || cleanedAnswer.length() < 10) continue;

            ContentChunk chunk = new ContentChunk();
            chunk.setQuestion(parsedChunk.question());
            chunk.setContent(cleanedAnswer);
            chunk.setSectionNumber(parsedChunk.questionNumber());
            chunk.setChapterTitle("Catecismo Maior");
            chunk.setWork(catechism);
            chunk.setTopics(taggingService.getTagsFor(parsedChunk.question(), cleanedAnswer));
            contentChunkRepository.save(chunk);
        }
    }

    private void loadShorterCatechism(Author author, List<Topic> availableTopics) throws IOException {
        final String WORK_TITLE = "Breve Catecismo de Westminster";
        if (workRepository.findByTitle(WORK_TITLE).isPresent()) {
            return;
        }
        logger.info("Carregando e catalogando '{}'...", WORK_TITLE);

        Work catechism = createWork(WORK_TITLE, author, 1647, "CATECISMO");
        String rawText = extractTextFromPdf("classpath:data-content/pdf/breve_catecismo_westminster.pdf");

        List<ChunkingService.ParsedQuestionChunk> parsedChunks = chunkingService.parseWestminsterShorterCatechism(rawText);

        logger.info("Encontrados e catalogados {} chunks no Breve Catecismo.", parsedChunks.size());

        for (var parsedChunk : parsedChunks) {
            String cleanedAnswer = cleanChunkText(parsedChunk.answer());
            if (cleanedAnswer.isBlank() || cleanedAnswer.length() < 10) continue;

            ContentChunk chunk = new ContentChunk();
            chunk.setQuestion(parsedChunk.question());
            chunk.setContent(cleanedAnswer);
            chunk.setSectionNumber(parsedChunk.questionNumber());
            chunk.setChapterTitle("Breve Catecismo");
            chunk.setWork(catechism);
            chunk.setTopics(taggingService.getTagsFor(parsedChunk.question(), cleanedAnswer));
            contentChunkRepository.save(chunk);
        }
    }

    private void loadCalvinInstitutes(Author author, List<Topic> availableTopics) throws IOException {
        final String WORK_TITLE = "Institutas da Religião Cristã";
        if (workRepository.findByTitle(WORK_TITLE).isPresent()) {
            logger.info("'{}' já está no banco. Pulando.", WORK_TITLE);
            return;
        }

        logger.info("Carregando e catalogando '{}'...", WORK_TITLE);

        Work institutes = createWork(WORK_TITLE, author, 1536, "LIVRO");
        String rawText = extractTextFromPdf("classpath:data-content/pdf/Institutas da Religiao Crista - Joao Calvino.pdf");

        // A chamada agora é para o método novo e específico, sem passar regex
        List<ChunkingService.ParsedChunk> parsedChunks = chunkingService.parseCalvinInstitutes(rawText);

        logger.info("Encontrados e catalogados {} chunks (seções) nas Institutas.", parsedChunks.size());

        for (var parsedChunk : parsedChunks) {
            String cleanedContent = cleanChunkText(parsedChunk.content());
            if (cleanedContent.isBlank() || cleanedContent.length() < 100) continue;

            ContentChunk chunk = new ContentChunk();
            chunk.setChapterNumber(parsedChunk.chapterNumber());
            chunk.setChapterTitle(parsedChunk.chapterTitle());
            chunk.setSectionNumber(parsedChunk.sectionNumber());
            chunk.setContent(cleanedContent);
            chunk.setWork(institutes);

            chunk.setTopics(taggingService.getTagsFor(parsedChunk.chapterTitle(), cleanedContent));

            contentChunkRepository.save(chunk);
        }
        logger.info("'{}' carregado e salvo no banco.", WORK_TITLE);
    }

    private Work createWork(String title, Author author, int year, String type) {
        Work work = new Work();
        work.setTitle(title);
        work.setAuthor(author);
        work.setPublicationYear(year);
        work.setType(type);
        return workRepository.save(work);
    }

    private Topic createTopic(String name, String description) {
        return topicRepository.findByName(name)
                .orElseGet(() -> {
                    Topic newTopic = new Topic();
                    newTopic.setName(name);
                    newTopic.setDescription(description);
                    return topicRepository.save(newTopic);
                });
    }

    private Author createAuthor(String name, String era, String birth, String death) {
        return authorRepository.findByName(name)
                .orElseGet(() -> {
                    Author newAuthor = new Author();
                    newAuthor.setName(name);
                    newAuthor.setEra(era);
                    newAuthor.setBirthDate(LocalDate.parse(birth));
                    newAuthor.setDeathDate(LocalDate.parse(death));
                    return authorRepository.save(newAuthor);
                });
    }

    private String extractTextFromPdf(String resourcePath) throws IOException {
        logger.info("Extraindo texto de: {}", resourcePath);
        Resource resource = resourceLoader.getResource(resourcePath);
        byte[] pdfBytes;
        try (InputStream is = resource.getInputStream()) {
            pdfBytes = is.readAllBytes();
        }

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper pdfStripper = new PDFTextStripper();
            return pdfStripper.getText(document);
        }
    }

    private String cleanChunkText(String rawChunkContent) {
        String cleaned = rawChunkContent.replaceAll("(?m)^\\s*\\d+\\s*$", "");
        cleaned = cleaned.replaceAll("(?<!\n)\r?\n(?!\n)", " ");
        return cleaned.replaceAll(" +", " ").trim();
    }
}
