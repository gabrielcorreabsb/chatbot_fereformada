
package br.com.fereformada.api.config;

import br.com.fereformada.api.dto.ChunkData;
import br.com.fereformada.api.model.Author;
import br.com.fereformada.api.model.ContentChunk;
import br.com.fereformada.api.model.Topic;
import br.com.fereformada.api.model.Work;
import br.com.fereformada.api.repository.*;
import br.com.fereformada.api.service.ChunkingService;
import br.com.fereformada.api.service.StudyNoteBatchService;
import br.com.fereformada.api.service.TaggingService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
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
import br.com.fereformada.api.service.GeminiApiClient;
import br.com.fereformada.api.model.StudyNote;
import br.com.fereformada.api.repository.StudyNoteRepository;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

@Component
public class DatabaseSeeder implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseSeeder.class);


    private PrintWriter notesLogWriter;

    private static final java.util.Set<String> SINGLE_CHAPTER_BOOKS = java.util.Set.of(
            "Obadias", "Filemom", "2 João", "3 João", "Judas"
    );

    private final StudyNoteBatchService studyNoteBatchService;
    private static final int BATCH_SIZE = 50;
    private final AuthorRepository authorRepository;
    private final WorkRepository workRepository;
    private final TopicRepository topicRepository;
    private final ContentChunkRepository contentChunkRepository;
    private final ResourceLoader resourceLoader;
    private final ChunkingService chunkingService;
    private final TaggingService taggingService;
    private final GeminiApiClient geminiApiClient;
    private final StudyNoteRepository studyNoteRepository;

    public DatabaseSeeder(AuthorRepository authorRepository, WorkRepository workRepository,
                          TopicRepository topicRepository, ContentChunkRepository contentChunkRepository,
                          ResourceLoader resourceLoader, ChunkingService chunkingService,
                          TaggingService taggingService, GeminiApiClient geminiApiClient, StudyNoteRepository studyNoteRepository, StudyNoteBatchService studyNoteBatchService) {
        this.authorRepository = authorRepository;
        this.workRepository = workRepository;
        this.topicRepository = topicRepository;
        this.contentChunkRepository = contentChunkRepository;
        this.resourceLoader = resourceLoader;
        this.chunkingService = chunkingService;
        this.taggingService = taggingService;
        this.geminiApiClient = geminiApiClient;
        this.studyNoteRepository = studyNoteRepository;
        this.studyNoteBatchService = studyNoteBatchService;

    }

    @Override
    public void run(String... args) throws Exception {
        logger.info("🔍 Verificando status do banco de dados...");

        // Verificar o que já existe
        DatabaseStatus status = checkDatabaseStatus();
        logDatabaseStatus(status);

        if (status.isComplete()) {
            logger.info("✅ Todos os dados já estão carregados. Nada a fazer.");
            return;
        }

        logger.info("📦 Carregando dados faltantes...");

        // Garantir que tópicos e autores existem
        ensureTopicsAndAuthorsExist();

        // Carregar apenas o que está faltando
        loadMissingData(status);

        logger.info("🎉 Carga de dados finalizada com sucesso!");
    }

    private DatabaseStatus checkDatabaseStatus() {
        DatabaseStatus status = new DatabaseStatus();
        final int TOTAL_BIBLE_BOOKS = 66; // 39 AT + 27 NT

        // Verificar obras existentes
        status.hasConfession = workRepository.findByTitle("Confissão de Fé de Westminster").isPresent();
        status.hasLargerCatechism = workRepository.findByTitle("Catecismo Maior de Westminster").isPresent();
        status.hasShorterCatechism = workRepository.findByTitle("Breve Catecismo de Westminster").isPresent();
        status.hasInstitutes = workRepository.findByTitle("Institutas da Religião Cristã").isPresent();

        // Contar chunks por obra
        if (status.hasConfession) {
            status.confessionChunks = contentChunkRepository.countByWorkTitle("Confissão de Fé de Westminster");
        }
        if (status.hasLargerCatechism) {
            status.largerCatechismChunks = contentChunkRepository.countByWorkTitle("Catecismo Maior de Westminster");
        }
        if (status.hasShorterCatechism) {
            status.shorterCatechismChunks = contentChunkRepository.countByWorkTitle("Breve Catecismo de Westminster");
        }
        if (status.hasInstitutes) {
            status.institutesChunks = contentChunkRepository.countByWorkTitle("Institutas da Religião Cristã");
        }

        if (status.hasSystematicTheology) {
            status.systematicTheologyChunks = contentChunkRepository.countByWorkTitle("Teologia Sistemática");
        }

        // --- CORREÇÃO APLICADA AQUI ---

        // 1. ADICIONE ESTA LINHA para buscar a contagem de livros distintos
        long processedBooksCount = studyNoteRepository.countDistinctBookBySource("Bíblia de Genebra");

        // Esta linha é para o log, então a mantemos
        status.genevaNotesCount = studyNoteRepository.countBySource("Bíblia de Genebra");

        // 2. USE a variável que acabamos de criar para a verificação
        status.hasGenevaNotes = (processedBooksCount >= TOTAL_BIBLE_BOOKS);

        return status;
    }

    private void logDatabaseStatus(DatabaseStatus status) {
        logger.info("📊 Status atual do banco:");
        logger.info("  • Confissão de Westminster: {} (chunks: {})",
                status.hasConfession ? "✅" : "❌", status.confessionChunks);
        logger.info("  • Catecismo Maior: {} (chunks: {})",
                status.hasLargerCatechism ? "✅" : "❌", status.largerCatechismChunks);
        logger.info("  • Breve Catecismo: {} (chunks: {})",
                status.hasShorterCatechism ? "✅" : "❌", status.shorterCatechismChunks);
        logger.info("  • Institutas: {} (chunks: {})",
                status.hasInstitutes ? "✅" : "❌", status.institutesChunks);
        logger.info("  • Notas da Bíblia de Genebra: {} (notas: {})",
                status.hasGenevaNotes ? "✅" : "❌", status.genevaNotesCount);
        logger.info("  • Teologia Sistemática: {} (chunks: {})",
                status.hasSystematicTheology ? "✅" : "❌", status.systematicTheologyChunks);
    }

    @Transactional
    protected void ensureTopicsAndAuthorsExist() {
        // Só cria se não existirem
        if (topicRepository.count() == 0) {
            logger.info("📝 Criando tópicos...");
            createAllTopics();
        }

        logger.info("👥 Verificando e garantindo existência dos autores...");
        createAllAuthors();
    }

    private void createAllTopics() {
        List<Topic> topics = List.of(
                createTopic("Sagradas Escrituras", "A doutrina sobre a revelação de Deus na Bíblia."),
                createTopic("Deus e a Santíssima Trindade", "A doutrina sobre o ser, os atributos de Deus e a Trindade."),
                createTopic("Decretos de Deus", "A doutrina sobre os decretos eternos de Deus, incluindo a predestinação."),
                createTopic("Criação", "A doutrina sobre a criação do mundo e do homem por Deus."),
                createTopic("Providência", "A doutrina sobre o sustento e governo de Deus sobre todas as coisas."),
                createTopic("A Queda e o Pecado", "A doutrina sobre a queda do homem, o pecado original e atual."),
                createTopic("Pacto de Deus", "A doutrina sobre os pactos de Deus com o homem (obras e graça)."),
                createTopic("Cristo, o Mediador", "A doutrina sobre a pessoa e a obra de Jesus Cristo como mediador."),
                createTopic("Livre-Arbítrio", "A doutrina sobre a vontade do homem em seus diferentes estados (inocência, pecado, graça, glória)."),
                createTopic("Vocação Eficaz", "A doutrina sobre o chamado eficaz de Deus aos eleitos."),
                createTopic("Justificação pela Fé", "A doutrina de como o pecador é declarado justo diante de Deus."),
                createTopic("A Lei de Deus", "Os mandamentos e estatutos divinos revelados nas Escrituras.")
        );

        // Inicializar as regras de tagging com os tópicos criados
        taggingService.initializeRules(topics);

        logger.info("✅ {} tópicos criados com sucesso!", topics.size());
    }

    private void createAllAuthors() {
        Author westminster = createAuthor("Assembleia de Westminster", "Puritanos", "1643-01-01", "1653-01-01");
        Author calvin = createAuthor("João Calvino", "Reforma", "1509-07-10", "1564-05-27");
        Author berkhof = createAuthor("Louis Berkhof", "Teologia Reformada (Século XX)", "1873-10-13", "1957-05-18");

        logger.info("✅ Autores criados: {} e {}", westminster.getName(), calvin.getName());
    }

    private void loadMissingData(DatabaseStatus status) throws IOException {
        List<Topic> allTopics = topicRepository.findAll();
        Author westminsterAssembly = authorRepository.findByName("Assembleia de Westminster").orElseThrow();
        Author calvin = authorRepository.findByName("João Calvino").orElseThrow();
        Author berkhof = authorRepository.findByName("Louis Berkhof").orElseThrow();

        // Carregar apenas o que está faltando
        if (!status.hasConfession) {
            logger.info("🔄 Carregando Confissão de Westminster...");
            loadWestminsterConfession(westminsterAssembly, allTopics);
        } else {
            logger.info("⏭️ Confissão já carregada, pulando...");
        }

        if (!status.hasLargerCatechism) {
            logger.info("🔄 Carregando Catecismo Maior...");
            loadWestminsterCatechism(westminsterAssembly, allTopics);
        } else {
            logger.info("⏭️ Catecismo Maior já carregado, pulando...");
        }

        if (!status.hasShorterCatechism) {
            logger.info("🔄 Carregando Breve Catecismo...");
            loadShorterCatechism(westminsterAssembly, allTopics);
        } else {
            logger.info("⏭️ Breve Catecismo já carregado, pulando...");
        }

        if (!status.hasInstitutes) {
            logger.info("🔄 Carregando Institutas...");
            loadCalvinInstitutes(calvin, allTopics);
        } else {
            logger.info("⏭️ Institutas já carregadas, pulando...");
        }

        if (!status.hasGenevaNotes) {
            logger.info("🔄 Carregando Notas da Bíblia de Genebra...");
            loadGenevaStudyNotes();
        } else {
            logger.info("⏭️ Notas da Bíblia de Genebra já carregadas, pulando...");
        }

        if (!status.hasSystematicTheology) {
            logger.info("🔄 Carregando Teologia Sistemática...");
            loadSystematicTheology(berkhof, allTopics);
        } else {
            logger.info("⏭️ Teologia Sistemática já carregada, pulando...");
        }
    }

    // Classe auxiliar para status
    private static class DatabaseStatus {
        boolean hasConfession = false;
        boolean hasLargerCatechism = false;
        boolean hasShorterCatechism = false;
        boolean hasInstitutes = false;
        boolean hasSystematicTheology = false; //

        long confessionChunks = 0;
        long largerCatechismChunks = 0;
        long shorterCatechismChunks = 0;
        long institutesChunks = 0;
        long systematicTheologyChunks = 0;

        boolean hasGenevaNotes = false;
        long genevaNotesCount = 0;

        boolean isComplete() {
            return hasConfession && hasLargerCatechism && hasShorterCatechism && hasInstitutes && hasGenevaNotes && hasSystematicTheology;
        }
    }

    private void loadWestminsterConfession(Author author, List<Topic> availableTopics) throws IOException {
        final String WORK_TITLE = "Confissão de Fé de Westminster";
        if (workRepository.findByTitle(WORK_TITLE).isPresent()) {
            return;
        }
        logger.info("Carregando e catalogando '{}'...", WORK_TITLE);
        Work confession = findOrCreateWork(WORK_TITLE, author, 1646, "CONFISSAO", "CFW");
        String rawText = extractTextFromPdf("classpath:data-content/pdf/confissao_westminster.pdf", 3, 21);

        List<ChunkingService.ParsedChunk> parsedChunks = chunkingService.parseWestminsterConfession(rawText);
        logger.info("Encontrados e catalogados {} chunks (seções) na Confissão.", parsedChunks.size());

        int totalChunks = parsedChunks.size();
        int processedChunks = 0;

        for (var parsedChunk : parsedChunks) {
            processedChunks++;
            logger.info("Processando chunk {}/{}: Capítulo {} - Seção {}",
                    processedChunks, totalChunks,
                    parsedChunk.chapterNumber(), parsedChunk.sectionNumber());

            String cleanedContent = cleanChunkText(parsedChunk.content());
            if (cleanedContent.isBlank() || cleanedContent.length() < 20) continue;

            ContentChunk chunk = new ContentChunk();
            chunk.setChapterNumber(parsedChunk.chapterNumber());
            chunk.setChapterTitle(parsedChunk.chapterTitle());
            chunk.setSectionNumber(parsedChunk.sectionNumber());
            chunk.setContent(cleanedContent);
            chunk.setWork(confession);

            // Gera o embedding
            try {
                PGvector vector = geminiApiClient.generateEmbedding(cleanedContent);
                chunk.setContentVector(convertPGvectorToFloatArray(vector));
                logger.debug("Embedding gerado com sucesso para chunk {}/{}", processedChunks, totalChunks);
            } catch (Exception e) {
                logger.error("Erro ao gerar embedding para chunk {}/{}: {}", processedChunks, totalChunks, e.getMessage());
                // Continue sem o embedding se houver erro
            }

            chunk.setTopics(taggingService.getTagsFor(parsedChunk.chapterTitle(), cleanedContent));
            contentChunkRepository.save(chunk);

            // Log a cada 10 chunks processados
            if (processedChunks % 10 == 0) {
                logger.info("Progresso: {}/{} chunks processados ({}%)",
                        processedChunks, totalChunks,
                        Math.round((processedChunks * 100.0) / totalChunks));
            }
        }

        logger.info("Confissão de Westminster carregada com sucesso! Total: {} chunks", processedChunks);
    }

    private void loadWestminsterCatechism(Author author, List<Topic> availableTopics) throws IOException {
        final String WORK_TITLE = "Catecismo Maior de Westminster";
        if (workRepository.findByTitle(WORK_TITLE).isPresent()) {
            return;
        }
        logger.info("Carregando e catalogando '{}'...", WORK_TITLE);

        Work catechism = findOrCreateWork(WORK_TITLE, author, 1648, "CATECISMO", "CM");
        String rawText = extractTextFromPdf("classpath:data-content/pdf/catecismo_maior_westminster.pdf");

        List<ChunkingService.ParsedQuestionChunk> parsedChunks = chunkingService.parseWestminsterLargerCatechism(rawText);
        logger.info("Encontrados e catalogados {} chunks no Catecismo Maior.", parsedChunks.size());

        int totalChunks = parsedChunks.size();
        int processedChunks = 0;

        for (var parsedChunk : parsedChunks) {
            processedChunks++;
            logger.info("Processando pergunta {}/{}: {}",
                    processedChunks, totalChunks,
                    parsedChunk.question().substring(0, Math.min(50, parsedChunk.question().length())));

            String cleanedAnswer = cleanChunkText(parsedChunk.answer());
            if (cleanedAnswer.isBlank() || cleanedAnswer.length() < 10) continue;

            ContentChunk chunk = new ContentChunk();
            chunk.setQuestion(parsedChunk.question());
            chunk.setContent(cleanedAnswer);
            chunk.setSectionNumber(parsedChunk.questionNumber());
            chunk.setChapterTitle("Catecismo Maior");
            chunk.setWork(catechism);

            // Gera o embedding para a combinação da pergunta e resposta.
            try {
                String textToEmbed = parsedChunk.question() + "\n" + cleanedAnswer;
                PGvector vector = geminiApiClient.generateEmbedding(textToEmbed);
                chunk.setContentVector(convertPGvectorToFloatArray(vector));
                logger.debug("Embedding gerado com sucesso para pergunta {}/{}", processedChunks, totalChunks);
            } catch (Exception e) {
                logger.error("Erro ao gerar embedding para pergunta {}/{}: {}", processedChunks, totalChunks, e.getMessage());
                // Continue sem o embedding se houver erro
            }

            chunk.setTopics(taggingService.getTagsFor(parsedChunk.question(), cleanedAnswer));
            contentChunkRepository.save(chunk);

            // Log a cada 10 chunks processados
            if (processedChunks % 10 == 0) {
                logger.info("Progresso Catecismo Maior: {}/{} chunks processados ({}%)",
                        processedChunks, totalChunks,
                        Math.round((processedChunks * 100.0) / totalChunks));
            }
        }

        logger.info("Catecismo Maior carregado com sucesso! Total: {} chunks", processedChunks);
    }

    private void loadShorterCatechism(Author author, List<Topic> availableTopics) throws IOException {
        final String WORK_TITLE = "Breve Catecismo de Westminster";
        if (workRepository.findByTitle(WORK_TITLE).isPresent()) {
            return;
        }
        logger.info("Carregando e catalogando '{}'...", WORK_TITLE);

        Work catechism = findOrCreateWork(WORK_TITLE, author, 1647, "CATECISMO", "BC");
        String rawText = extractTextFromPdf("classpath:data-content/pdf/breve_catecismo_westminster.pdf");

        List<ChunkingService.ParsedQuestionChunk> parsedChunks = chunkingService.parseWestminsterShorterCatechism(rawText);
        logger.info("Encontrados e catalogados {} chunks no Breve Catecismo.", parsedChunks.size());

        int totalChunks = parsedChunks.size();
        int processedChunks = 0;

        for (var parsedChunk : parsedChunks) {
            processedChunks++;
            logger.info("Processando pergunta {}/{}: {}",
                    processedChunks, totalChunks,
                    parsedChunk.question().substring(0, Math.min(50, parsedChunk.question().length())));

            String cleanedAnswer = cleanChunkText(parsedChunk.answer());
            if (cleanedAnswer.isBlank() || cleanedAnswer.length() < 10) continue;

            ContentChunk chunk = new ContentChunk();
            chunk.setQuestion(parsedChunk.question());
            chunk.setContent(cleanedAnswer);
            chunk.setSectionNumber(parsedChunk.questionNumber());
            chunk.setChapterTitle("Breve Catecismo");
            chunk.setWork(catechism);

            // Gera o embedding para a combinação da pergunta e resposta.
            try {
                String textToEmbed = parsedChunk.question() + "\n" + cleanedAnswer;
                PGvector vector = geminiApiClient.generateEmbedding(textToEmbed);
                chunk.setContentVector(convertPGvectorToFloatArray(vector));
                logger.debug("Embedding gerado com sucesso para pergunta {}/{}", processedChunks, totalChunks);
            } catch (Exception e) {
                logger.error("Erro ao gerar embedding para pergunta {}/{}: {}", processedChunks, totalChunks, e.getMessage());
                // Continue sem o embedding se houver erro
            }

            chunk.setTopics(taggingService.getTagsFor(parsedChunk.question(), cleanedAnswer));
            contentChunkRepository.save(chunk);

            // Log a cada 10 chunks processados
            if (processedChunks % 10 == 0) {
                logger.info("Progresso Breve Catecismo: {}/{} chunks processados ({}%)",
                        processedChunks, totalChunks,
                        Math.round((processedChunks * 100.0) / totalChunks));
            }
        }

        logger.info("Breve Catecismo carregado com sucesso! Total: {} chunks", processedChunks);
    }

    private void loadCalvinInstitutes(Author author, List<Topic> availableTopics) throws IOException {
        final String WORK_TITLE = "Institutas da Religião Cristã";
        if (workRepository.findByTitle(WORK_TITLE).isPresent()) {
            logger.info("'{}' já está no banco. Pulando.", WORK_TITLE);
            return;
        }

        logger.info("Carregando e catalogando '{}'...", WORK_TITLE);

        Work institutes = findOrCreateWork(WORK_TITLE, author, 1536, "LIVRO", "ICR"); // "Institutas da Religião Cristã"
        String rawText = extractTextFromPdf("classpath:data-content/pdf/Institutas da Religiao Crista - Joao Calvino.pdf", 36, 367);

        List<ChunkingService.ParsedChunk> parsedChunks = chunkingService.parseCalvinInstitutes(rawText);
        logger.info("Encontrados e catalogados {} chunks (seções) nas Institutas.", parsedChunks.size());

        int totalChunks = parsedChunks.size();
        int processedChunks = 0;

        for (var parsedChunk : parsedChunks) {
            processedChunks++;
            logger.info("Processando seção {}/{}: Livro {} - Cap. {} - Seção {}",
                    processedChunks, totalChunks,
                    parsedChunk.chapterNumber(),
                    parsedChunk.chapterTitle() != null ? parsedChunk.chapterTitle().substring(0, Math.min(30, parsedChunk.chapterTitle().length())) : "N/A",
                    parsedChunk.sectionNumber());

            String cleanedContent = cleanChunkText(parsedChunk.content());
            if (cleanedContent.isBlank() || cleanedContent.length() < 100) continue;

            ContentChunk chunk = new ContentChunk();
            chunk.setChapterNumber(parsedChunk.chapterNumber());
            chunk.setChapterTitle(parsedChunk.chapterTitle());
            chunk.setSectionTitle(parsedChunk.sectionTitle());
            chunk.setSectionNumber(parsedChunk.sectionNumber());
            chunk.setContent(cleanedContent);
            chunk.setWork(institutes);

            // Gera o embedding para o conteúdo da seção.
            try {
                PGvector vector = geminiApiClient.generateEmbedding(cleanedContent);
                chunk.setContentVector(convertPGvectorToFloatArray(vector));
                logger.debug("Embedding gerado com sucesso para seção {}/{}", processedChunks, totalChunks);
            } catch (Exception e) {
                logger.error("Erro ao gerar embedding para seção {}/{}: {}", processedChunks, totalChunks, e.getMessage());
                // Continue sem o embedding se houver erro
            }

            String taggingInput = parsedChunk.chapterTitle() + " " +
                    (parsedChunk.sectionTitle() != null ? parsedChunk.sectionTitle() : "") + " " +
                    cleanedContent;
            chunk.setTopics(taggingService.getTagsFor(taggingInput, ""));

            contentChunkRepository.save(chunk);

            // Log a cada 20 chunks processados (Institutas tem mais conteúdo)
            if (processedChunks % 20 == 0) {
                logger.info("Progresso Institutas: {}/{} chunks processados ({}%)",
                        processedChunks, totalChunks,
                        Math.round((processedChunks * 100.0) / totalChunks));
            }
        }

        logger.info("'{}' carregado e salvo no banco. Total: {} chunks", WORK_TITLE, processedChunks);
    }

    private float[] convertPGvectorToFloatArray(PGvector pgVector) {
        if (pgVector == null) {
            return null;
        }
        try {
            // Primeiro tenta toString() e parsing
            String vectorString = pgVector.toString();

            // Remove colchetes se existirem
            if (vectorString.startsWith("[") && vectorString.endsWith("]")) {
                vectorString = vectorString.substring(1, vectorString.length() - 1);
            }

            // Divide por vírgula e converte
            String[] parts = vectorString.split(",");
            float[] result = new float[parts.length];

            for (int i = 0; i < parts.length; i++) {
                result[i] = Float.parseFloat(parts[i].trim());
            }

            return result;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao converter PGvector para float[]: " + e.getMessage(), e);
        }
    }

    private Work findOrCreateWork(String title, Author author, int year, String type, String acronym) {
        // 1. Tenta encontrar a obra pelo título (que agora é ÚNICO)
        Optional<Work> existingWork = workRepository.findByTitle(title);

        if (existingWork.isPresent()) {
            // Se encontrou, apenas a retorna. Não faz nada.
            return existingWork.get();
        }

        // 2. Se não encontrou, cria a nova obra
        logger.info("Criando nova obra no seeder: {}", title);
        Work work = new Work();
        work.setTitle(title);
        work.setAuthor(author);
        work.setPublicationYear(year);
        work.setType(type);
        work.setAcronym(acronym);
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
        cleaned = cleaned.replaceAll("[“”]", "\"");
        return cleaned.replaceAll(" +", " ").trim();
    }

    private String extractTextFromPdf(String resourcePath, int startPage, int endPage) throws IOException {
        logger.info("Extraindo texto de: {} (páginas {} a {})", resourcePath, startPage, endPage);
        Resource resource = resourceLoader.getResource(resourcePath);
        try (InputStream is = resource.getInputStream();
             PDDocument document = Loader.loadPDF(is.readAllBytes())) {
            PDFTextStripper pdfStripper = new PDFTextStripper();
            pdfStripper.setStartPage(startPage);
            pdfStripper.setEndPage(endPage);
            return pdfStripper.getText(document);
        }
    }

    private void loadGenevaStudyNotes() {
        final String SOURCE_NAME = "Bíblia de Genebra";
        logger.info("Verificando e carregando notas da '{}' livro por livro...", SOURCE_NAME);

        // Inicializar o arquivo de log
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String logFileName = "notes_processing_log_" + timestamp + ".txt";
            notesLogWriter = new PrintWriter(new FileWriter(logFileName, true));
            notesLogWriter.println("=== LOG DE PROCESSAMENTO DE NOTAS BÍBLICAS ===");
            notesLogWriter.println("Iniciado em: " + LocalDateTime.now());
            notesLogWriter.println("================================================\n");
            notesLogWriter.flush();
            logger.info("📝 Arquivo de log criado: {}", logFileName);
        } catch (IOException e) {
            logger.error("Erro ao criar arquivo de log: {}", e.getMessage());
        }

        List<String> otBooks = List.of(
                "Gênesis", "Êxodo", "Levítico", "Números", "Deuteronômio", "Josué", "Juízes", "Rute",
                "1_Samuel", "2_Samuel", "1_Reis", "2_Reis", "1_Crônicas", "2_Crônicas", "Esdras",
                "Neemias", "Ester", "Jó", "Salmos", "Provérbios", "Eclesiastes", "Cantares_de_salomão",
                "Isaías", "Jeremias", "Lamentações_de_jeremias", "Ezequiel", "Daniel", "Oséias",
                "Joel", "Amós", "Obadias", "Jonas", "Miquéias", "Naum", "Habacuque", "Sofonias",
                "Ageu", "Zacarias", "Malaquias"
        );

        List<String> ntBooks = List.of(
                "Mateus", "Marcos", "Lucas", "João", "Atos", "Romanos", "1_Coríntios", "2_Coríntios",
                "Gálatas", "Efésios", "Filipenses", "Colossenses", "1_Tessalonicenses",
                "2_Tessalonicenses", "1_Timóteo", "2_Timóteo", "Tito", "Filemom", "Hebreus",
                "Tiago", "1_Pedro", "2_Pedro", "1_João", "2_João", "3_João", "Judas", "Apocalipse"
        );

        int totalNotesLoaded = 0;
        int totalNotesSkipped = 0;
        int booksSkipped = 0;

        // Processar OT
        for (String bookFileName : otBooks) {
            String bookName = bookFileName.replace('_', ' ');

            String filePath = "classpath:data-content/bible-notes/ot/" + bookFileName + ".txt";
            int[] results = processStudyNoteFile(filePath, bookName, SOURCE_NAME);
            totalNotesLoaded += results[0];
            totalNotesSkipped += results[1];
        }

        // Processar NT
        for (String bookFileName : ntBooks) {
            String bookName = bookFileName.replace('_', ' ');
            if (studyNoteRepository.countByBook(bookName) > 0) {
                logger.info("⏭️ Notas para '{}' já existem no banco. Pulando.", bookName);
                booksSkipped++;
                continue;
            }
            String filePath = "classpath:data-content/bible-notes/nt/" + bookFileName + ".txt";
            int[] results = processStudyNoteFile(filePath, bookName, SOURCE_NAME);
            totalNotesLoaded += results[0];
            totalNotesSkipped += results[1];
        }

        // Finalizar log
        if (notesLogWriter != null) {
            notesLogWriter.println("\n=== RESUMO FINAL ===");
            notesLogWriter.println("Total de notas processadas: " + totalNotesLoaded);
            notesLogWriter.println("Total de notas puladas: " + totalNotesSkipped);
            notesLogWriter.println("Total de livros pulados: " + booksSkipped);
            notesLogWriter.println("Finalizado em: " + LocalDateTime.now());
            notesLogWriter.close();
        }

        logger.info("Carregamento das notas finalizado. {} livros pulados, {} notas carregadas, {} notas puladas.",
                booksSkipped, totalNotesLoaded, totalNotesSkipped);
    }


    private int[] processStudyNoteFile(String filePath, String bookName, String sourceName) {

        Set<String> existingKeys = studyNoteRepository.findExistingNoteKeysByBook(bookName);
        if (!existingKeys.isEmpty()) {
            logger.info("Retomando processamento de '{}'. {} notas já existem e serão puladas.", bookName, existingKeys.size());
        } else {
            logger.info("Iniciando processamento de '{}' do zero.", bookName);
        }

        logger.info("Processando notas para '{}' do arquivo {}...", bookName, filePath);

        // Log no arquivo
        if (notesLogWriter != null) {
            notesLogWriter.println("\n--- LIVRO: " + bookName + " ---");
            notesLogWriter.println("Arquivo: " + filePath);
            notesLogWriter.flush();
        }

        String rawText;
        try {
            rawText = extractTextFromTxt(filePath);
        } catch (IOException e) {
            logger.error("ERRO: Não foi possível ler o arquivo '{}'. Verifique se ele existe. Pulando.", filePath);
            if (notesLogWriter != null) {
                notesLogWriter.println("ERRO: Arquivo não encontrado!");
                notesLogWriter.flush();
            }
            return new int[]{0, 0};
        }

        String cleanedText = rawText.trim();
        if (cleanedText.startsWith(bookName)) {
            cleanedText = cleanedText.substring(bookName.length()).trim();
        }

        String[] noteBlocks = cleanedText.split("\\*\\s+(?=[\\d])");

        int processedCount = 0;
        int skippedCount = existingKeys.size();

        // Uma única lista para acumular as notas para processamento e salvamento
        List<StudyNote> notesBatch = new java.util.ArrayList<>(BATCH_SIZE);

        for (String block : noteBlocks) {
            if (block.trim().isEmpty()) {
                continue;
            }

            try {
                StudyNote note = parseNoteBlock(block.trim(), bookName, sourceName);
                if (note != null) {
                    String noteKey = note.getStartChapter() + ":" + note.getStartVerse();
                    if (existingKeys.contains(noteKey)) {
                        continue; // Pula nota já existente
                    }

                    // 1. Adiciona a nota (ainda sem vetor) a um lote temporário
                    notesBatch.add(note);
                    processedCount++;

                    // 2. Quando o lote estiver cheio, processe-o de uma vez
                    if (notesBatch.size() >= BATCH_SIZE) {
                        logger.info("Processando lote de {} notas para '{}'. Gerando embeddings...", notesBatch.size(), bookName);

                        // 3. Extrai apenas os textos para enviar à API
                        List<String> textsToEmbed = notesBatch.stream()
                                .map(StudyNote::getNoteContent)
                                .collect(Collectors.toList());

                        // 4. Faz UMA ÚNICA chamada à API para o lote inteiro
                        List<PGvector> vectors = geminiApiClient.generateEmbeddingsInBatch(textsToEmbed);

                        // 5. Atribui os vetores de volta às notas correspondentes no lote
                        if (vectors.size() == notesBatch.size()) {
                            for (int i = 0; i < notesBatch.size(); i++) {
                                notesBatch.get(i).setNoteVector(convertPGvectorToFloatArray(vectors.get(i)));
                            }
                        } else {
                            logger.error("Erro de contagem de vetores para o lote de {}. Esperado: {}, Recebido: {}", bookName, notesBatch.size(), vectors.size());
                        }

                        // 6. Salva o lote de notas (agora completo) no banco de dados
                        studyNoteBatchService.saveBatch(notesBatch);
                        notesBatch.clear(); // Limpa o lote para o próximo ciclo
                    }
                } else {
                    logSkippedNote(bookName, block);
                }
            } catch (Exception e) {
                // ... (lógica de erro continua igual)
            }
        }

        // 7. IMPORTANTE: Processa e salva o último lote residual que sobrou
        if (!notesBatch.isEmpty()) {
            logger.info("Processando lote final de {} notas para '{}'. Gerando embeddings...", notesBatch.size(), bookName);

            List<String> textsToEmbed = notesBatch.stream()
                    .map(StudyNote::getNoteContent)
                    .collect(Collectors.toList());
            List<PGvector> vectors = geminiApiClient.generateEmbeddingsInBatch(textsToEmbed);

            if (vectors.size() == notesBatch.size()) {
                for (int i = 0; i < notesBatch.size(); i++) {
                    notesBatch.get(i).setNoteVector(convertPGvectorToFloatArray(vectors.get(i)));
                }
            } else {
                logger.error("Erro de contagem de vetores para o lote final de {}. Esperado: {}, Recebido: {}", bookName, notesBatch.size(), vectors.size());
            }

            studyNoteBatchService.saveBatch(notesBatch);
        }

        if (skippedCount > 0) {
            logger.info("✔ {} novas notas processadas para '{}'. Total de {} notas já existentes foram puladas.", processedCount, bookName, skippedCount);
        } else {
            logger.info("✔ Sucesso! {} notas processadas para {}.", processedCount, bookName);
        }

        return new int[]{processedCount, skippedCount};
    }

    private void logSkippedNote(String bookName, String block) {
        if (notesLogWriter != null) {
            String preview = block.substring(0, Math.min(100, block.length()));
            if (block.length() > 100) preview += "...";
            notesLogWriter.println("  [PULADA] " + bookName + ": " + preview.replaceAll("\\s+", " "));
            notesLogWriter.flush();
        }
    }

    private StudyNote parseNoteBlock(String block, String bookName, String sourceName) {
        // Regex mais flexível para capturar diferentes formatos de referência
        // Aceita: "1.1", "1:1", "1.1-3", "1:1-3", "1", "1-3", etc.
        Pattern pattern = Pattern.compile("^([\\d]+[.:,]?[\\d]*[\\-—]?[\\d]*[.:,]?[\\d]*)\\s+(.*)", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(block);

        if (!matcher.find()) {
            logger.warn("Formato de nota não reconhecido para {}: {}", bookName,
                    block.substring(0, Math.min(50, block.length())));
            return null;
        }

        String reference = matcher.group(1).trim();
        String noteContent = matcher.group(2).trim();

        // Se o conteúdo da nota estiver vazio ou muito curto, pular
        if (noteContent.length() < 10) {
            logger.debug("Nota muito curta ou vazia para {}: {}", bookName, reference);
            return null;
        }

        StudyNote note = new StudyNote();
        note.setSource(sourceName);
        note.setBook(bookName);
        note.setNoteContent(cleanChunkText(noteContent));

        // Parse da referência melhorado
        try {
            int[] parsedRef = parseReferenceForStudyNotes(reference, bookName);
            note.setStartChapter(parsedRef[0]);
            note.setStartVerse(parsedRef[1]);
            note.setEndChapter(parsedRef[2]);
            note.setEndVerse(parsedRef[3]);
        } catch (Exception e) {
            logger.warn("Erro ao fazer parse da referência '{}' para {}: {}", reference, bookName, e.getMessage());
            // Para notas sem referência específica, usar valores padrão
            note.setStartChapter(1);
            note.setStartVerse(1);
            note.setEndChapter(1);
            note.setEndVerse(1);
        }

        return note;
    }

    private void loadSystematicTheology(Author author, List<Topic> availableTopics) throws IOException {
        final String WORK_TITLE = "Teologia Sistemática";
        if (workRepository.findByTitle(WORK_TITLE).isPresent()) {
            logger.info("'{}' já está no banco. Pulando.", WORK_TITLE);
            return;
        }
        logger.info("Carregando e catalogando '{}'...", WORK_TITLE);

        // 1. Crie a entrada para a obra (Work)
        Work berkhofWork = findOrCreateWork(WORK_TITLE, author, 1932, "TEOLOGIA_SISTEMATICA", "TSB"); // "Teologia Sistemática de Berkhof"
        berkhofWork.setAcronym("TSB"); // Adicionando o acrônimo
        workRepository.save(berkhofWork);

        // 2. Leia o arquivo JSON usando o ResourceLoader
        ObjectMapper mapper = new ObjectMapper();
        Resource resource = resourceLoader.getResource("classpath:data-content/berkhof_chunks.json");
        List<ChunkData> chunksData;
        try (InputStream is = resource.getInputStream()) {
            chunksData = mapper.readValue(is, new TypeReference<List<ChunkData>>() {});
        }

        logger.info("{} chunks lidos do JSON. Iniciando processamento e salvamento...", chunksData.size());

        int totalChunks = chunksData.size();
        int processedChunks = 0;

        // 3. Itere sobre os dados do JSON, crie as entidades e salve
        for (ChunkData data : chunksData) {
            processedChunks++;

            ContentChunk chunk = new ContentChunk();
            chunk.setWork(berkhofWork);
            chunk.setChapterTitle(data.getChapterTitle());
            chunk.setSectionTitle(data.getSectionTitle());
            chunk.setSubsectionTitle(data.getSubsectionTitle());
            chunk.setSubSubsectionTitle(data.getSubSubsectionTitle());
            chunk.setContent(data.getContent());

            // Gere o embedding
            try {
                PGvector vector = geminiApiClient.generateEmbedding(data.getContent());
                chunk.setContentVector(convertPGvectorToFloatArray(vector));
            } catch (Exception e) {
                logger.error("Erro ao gerar embedding para chunk {}/{}: {}", processedChunks, totalChunks, e.getMessage());
            }

            // Aplique tags (tópicos)
            String taggingInput = data.getChapterTitle() + " " + data.getSectionTitle() + " " + data.getContent();
            chunk.setTopics(taggingService.getTagsFor(taggingInput, ""));

            contentChunkRepository.save(chunk);

            if (processedChunks % 50 == 0 || processedChunks == totalChunks) {
                logger.info("Progresso Teologia Sistemática: {}/{} chunks processados ({}%)",
                        processedChunks, totalChunks,
                        Math.round((processedChunks * 100.0) / totalChunks));
            }
        }
        logger.info("'{}' carregado e salvo no banco. Total: {} chunks", WORK_TITLE, processedChunks);
    }

    private int[] parseReferenceForStudyNotes(String reference, String bookName) {
        int startChapter = 1, startVerse = 1;
        int endChapter = 1, endVerse = 1;

        try {
            // Remover espaços e caracteres não numéricos exceto . : , -
            String cleanRef = reference.replaceAll("\\s+", "")
                    .replace("—", "-")  // hífen longo
                    .replace("–", "-"); // hífen médio

            // Detectar se a referência usa formato capítulo.versículo ou capítulo:versículo
            if (cleanRef.contains(".") || cleanRef.contains(":")) {
                // Formato com capítulo e versículo: "5.21", "5:21", "5.21-23", etc.
                String[] parts;
                if (cleanRef.contains("-")) {
                    // Range: "5.21-23" ou "5.21-6.1"
                    parts = cleanRef.split("-");
                    String startRef = parts[0];
                    String endRef = parts.length > 1 ? parts[1] : startRef;

                    // Parse início
                    String[] startParts = startRef.split("[.:]");
                    startChapter = Integer.parseInt(startParts[0]);
                    startVerse = startParts.length > 1 ? Integer.parseInt(startParts[1]) : 1;

                    // Parse fim
                    if (endRef.contains(".") || endRef.contains(":")) {
                        String[] endParts = endRef.split("[.:]");
                        endChapter = Integer.parseInt(endParts[0]);
                        endVerse = endParts.length > 1 ? Integer.parseInt(endParts[1]) : 1;
                    } else {
                        // Se o fim é apenas um número, assumir que é versículo do mesmo capítulo
                        endChapter = startChapter;
                        endVerse = Integer.parseInt(endRef);
                    }
                } else if (cleanRef.contains(",")) {
                    // Lista: "5.21,22" - tratar como range
                    parts = cleanRef.split(",");
                    String[] startParts = parts[0].split("[.:]");
                    startChapter = Integer.parseInt(startParts[0]);
                    startVerse = startParts.length > 1 ? Integer.parseInt(startParts[1]) : 1;

                    // Para o fim, pegar o último item da lista
                    String lastRef = parts[parts.length - 1];
                    if (lastRef.contains(".") || lastRef.contains(":")) {
                        String[] endParts = lastRef.split("[.:]");
                        endChapter = Integer.parseInt(endParts[0]);
                        endVerse = endParts.length > 1 ? Integer.parseInt(endParts[1]) : 1;
                    } else {
                        endChapter = startChapter;
                        endVerse = Integer.parseInt(lastRef);
                    }
                } else {
                    // Versículo único: "5.21" ou "5:21"
                    parts = cleanRef.split("[.:]");
                    startChapter = Integer.parseInt(parts[0]);
                    startVerse = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
                    endChapter = startChapter;
                    endVerse = startVerse;
                }
            } else {
                // Formato sem capítulo (para livros de capítulo único ou referências só de versículo)
                if (SINGLE_CHAPTER_BOOKS.contains(bookName)) {
                    startChapter = 1;
                    endChapter = 1;

                    if (cleanRef.contains("-")) {
                        // Range: "1-3", "7-11"
                        String[] parts = cleanRef.split("-");
                        startVerse = Integer.parseInt(parts[0]);
                        endVerse = parts.length > 1 && !parts[1].isEmpty() ?
                                Integer.parseInt(parts[1]) : startVerse;
                    } else if (cleanRef.contains(",")) {
                        // Lista: "12,13"
                        String[] parts = cleanRef.split(",");
                        startVerse = Integer.parseInt(parts[0]);
                        endVerse = Integer.parseInt(parts[parts.length - 1]);
                    } else {
                        // Versículo único: "1", "7"
                        startVerse = Integer.parseInt(cleanRef);
                        endVerse = startVerse;
                    }
                } else {
                    // Para outros livros, número sozinho pode ser capítulo
                    startChapter = Integer.parseInt(cleanRef.replaceAll("[^\\d]", ""));
                    endChapter = startChapter;
                    startVerse = 1;
                    endVerse = 999; // Indicador de "capítulo inteiro"
                }
            }

        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            logger.debug("Usando valores padrão para referência complexa: {}", reference);
            // Valores padrão já definidos no início
        }

        return new int[]{startChapter, startVerse, endChapter, endVerse};
    }


    /**
     * Novo método auxiliar para ler arquivos de texto do classpath.
     */
    private String extractTextFromTxt(String resourcePath) throws IOException {
        Resource resource = resourceLoader.getResource(resourcePath);
        try (InputStream is = resource.getInputStream()) {
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private int[] parseReference(String reference, String bookName) {
        // Trata referências com vírgula (ex: "1,2") pegando apenas a primeira parte
        String cleanReference = reference.split(",")[0].trim();

        String[] parts = cleanReference.split("-");
        String startPart = parts[0];

        int startChapter, startVerse;

        if (startPart.contains(".")) {
            // Caso padrão: "1.1", "1.25", etc.
            String[] startRef = startPart.split("\\.");
            startChapter = Integer.parseInt(startRef[0]);
            startVerse = Integer.parseInt(startRef[1]);
        } else {
            // Caso de número único: "1", "14", etc.
            if (SINGLE_CHAPTER_BOOKS.contains(bookName)) {
                // Para livros de capítulo único, o número é o versículo.
                startChapter = 1;
                startVerse = Integer.parseInt(startPart);
            } else {
                // Para outros livros, assumimos que é uma referência ao capítulo inteiro.
                // Usamos versículo 0 como um marcador para "capítulo inteiro".
                startChapter = Integer.parseInt(startPart);
                startVerse = 0;
            }
        }

        int endChapter = startChapter;
        int endVerse = startVerse;

        if (parts.length > 1) {
            String endPart = parts[1];
            if (endPart.isEmpty()) {
                return new int[]{startChapter, startVerse, endChapter, endVerse};
            }

            if (endPart.contains(".")) {
                String[] endRef = endPart.split("\\.");
                endChapter = Integer.parseInt(endRef[0]);
                endVerse = Integer.parseInt(endRef[1]);
            } else {
                endChapter = startChapter;
                endVerse = Integer.parseInt(endPart);
            }
        }

        return new int[]{startChapter, startVerse, endChapter, endVerse};
    }
}