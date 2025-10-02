
package br.com.fereformada.api.config;

import br.com.fereformada.api.model.Author;
import br.com.fereformada.api.model.ContentChunk;
import br.com.fereformada.api.model.Topic;
import br.com.fereformada.api.model.Work;
import br.com.fereformada.api.repository.*;
import br.com.fereformada.api.service.ChunkingService;
import br.com.fereformada.api.service.TaggingService;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final GeminiApiClient geminiApiClient;
    private final StudyNoteRepository studyNoteRepository; // <-- NOVO


    public DatabaseSeeder(AuthorRepository authorRepository, WorkRepository workRepository,
                          TopicRepository topicRepository, ContentChunkRepository contentChunkRepository,
                          ResourceLoader resourceLoader, ChunkingService chunkingService,
                          TaggingService taggingService, GeminiApiClient geminiApiClient, StudyNoteRepository studyNoteRepository) {
        this.authorRepository = authorRepository;
        this.workRepository = workRepository;
        this.topicRepository = topicRepository;
        this.contentChunkRepository = contentChunkRepository;
        this.resourceLoader = resourceLoader;
        this.chunkingService = chunkingService;
        this.taggingService = taggingService;
        this.geminiApiClient = geminiApiClient;
        this.studyNoteRepository = studyNoteRepository;
    }

    @Override
    @Transactional
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

        // Verificar obras existentes
        status.hasConfession = workRepository.findByTitle("Confissão de Fé de Westminster").isPresent();
        status.hasLargerCatechism = workRepository.findByTitle("Catecismo Maior de Westminster").isPresent();
        status.hasShorterCatechism = workRepository.findByTitle("Breve Catecismo de Westminster").isPresent();
        status.hasInstitutes = workRepository.findByTitle("Institutas da Religião Cristã").isPresent();
        status.hasGenevaNotes = workRepository.findByTitle("Geneva Notes").isPresent();

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
        status.genevaNotesCount = studyNoteRepository.countBySource("Bíblia de Genebra");
        status.hasGenevaNotes = status.genevaNotesCount > 0;

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
    }

    private void ensureTopicsAndAuthorsExist() {
        // Só cria se não existirem
        if (topicRepository.count() == 0) {
            logger.info("📝 Criando tópicos...");
            createAllTopics();
        }

        if (authorRepository.count() == 0) {
            logger.info("👥 Criando autores...");
            createAllAuthors();
        }
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

        logger.info("✅ Autores criados: {} e {}", westminster.getName(), calvin.getName());
    }

    private void loadMissingData(DatabaseStatus status) throws IOException {
        List<Topic> allTopics = topicRepository.findAll();
        Author westminsterAssembly = authorRepository.findByName("Assembleia de Westminster").orElseThrow();
        Author calvin = authorRepository.findByName("João Calvino").orElseThrow();

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
    }

    // Classe auxiliar para status
    private static class DatabaseStatus {
        boolean hasConfession = false;
        boolean hasLargerCatechism = false;
        boolean hasShorterCatechism = false;
        boolean hasInstitutes = false;

        long confessionChunks = 0;
        long largerCatechismChunks = 0;
        long shorterCatechismChunks = 0;
        long institutesChunks = 0;

        boolean hasGenevaNotes = false;
        long genevaNotesCount = 0;

        boolean isComplete() {
            return hasConfession && hasLargerCatechism && hasShorterCatechism && hasInstitutes && hasGenevaNotes;
        }
    }

    private void loadWestminsterConfession(Author author, List<Topic> availableTopics) throws IOException {
        final String WORK_TITLE = "Confissão de Fé de Westminster";
        if (workRepository.findByTitle(WORK_TITLE).isPresent()) {
            return;
        }
        logger.info("Carregando e catalogando '{}'...", WORK_TITLE);
        Work confession = createWork(WORK_TITLE, author, 1646, "CONFISSAO");
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

        Work catechism = createWork(WORK_TITLE, author, 1648, "CATECISMO");
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

        Work catechism = createWork(WORK_TITLE, author, 1647, "CATECISMO");
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

        Work institutes = createWork(WORK_TITLE, author, 1536, "LIVRO");
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

    private void loadGenevaStudyNotes() throws IOException {
        final String SOURCE_NAME = "Bíblia de Genebra";
        logger.info("Verificando e carregando notas da '{}' livro por livro...", SOURCE_NAME);

        // Lista dos nomes dos arquivos do Antigo Testamento
        List<String> otBooks = List.of(
                "Gênesis", "Êxodo", "Levítico", "Números", "Deuteronômio", "Josué", "Juízes", "Rute",
                "1_Samuel", "2_Samuel", "1_Reis", "2_Reis", "1_Crônicas", "2_Crônicas", "Esdras", "Neemias", "Ester", "Jó",
                "Salmos", "Provérbios", "Eclesiastes", "Cantares_de_salomão", "Isaías", "Jeremias",
                "Lamentações_de_jeremias", "Ezequiel", "Daniel", "Oséias", "Joel", "Amós", "Obadias",
                "Jonas", "Miquéias", "Naum", "Habacuque", "Sofonias", "Ageu", "Zacarias", "Malaquias"
        );

        // Lista dos nomes dos arquivos do Novo Testamento
        List<String> ntBooks = List.of(
                "Mateus", "Marcos", "Lucas", "João", "Atos", "Romanos", "1_Coríntios", "2_Coríntios",
                "Gálatas", "Efésios", "Filipenses", "Colossenses", "1_Tessalonicenses", "2_Tessalonicenses",
                "1_Timóteo", "2_Timóteo", "Tito", "Filemom", "Hebreus", "Tiago", "1_Pedro", "2_Pedro",
                "1_João", "2_João", "3_João", "Judas", "Apocalipse"
        );

        int totalNotesLoaded = 0;
        int booksSkipped = 0;
        List<String> booksToProcess = new java.util.ArrayList<>();
        booksToProcess.addAll(otBooks.stream().map(b -> "ot/" + b).toList());
        booksToProcess.addAll(ntBooks.stream().map(b -> "nt/" + b).toList());

        for (String bookPath : booksToProcess) {
            String[] pathParts = bookPath.split("/");
            String testament = pathParts[0];
            String bookFileName = pathParts[1];
            String bookName = bookFileName.replace('_', ' ');

            // --- A LÓGICA PRINCIPAL ESTÁ AQUI ---
            if (studyNoteRepository.countByBook(bookName) > 0) {
                // Se já existem notas para este livro, pula para o próximo.
                logger.info("⏭️ Notas para '{}' já existem no banco. Pulando.", bookName);
                booksSkipped++;
                continue;
            }

            // Se não existe, processa o arquivo.
            String filePath = "classpath:data-content/bible-notes/" + testament + "/" + bookFileName + ".txt";
            totalNotesLoaded += processStudyNoteFile(filePath, bookName, SOURCE_NAME);
        }

        logger.info("Carregamento das notas da Bíblia de Genebra finalizado. {} livros pulados, {} novas notas carregadas.", booksSkipped, totalNotesLoaded);
    }

    /**
     * Processa um único arquivo de texto de notas, parseia e salva no banco.
     * @return O número de notas processadas no arquivo.
     */
    private int processStudyNoteFile(String filePath, String bookName, String sourceName) {
        logger.info("Processando notas para '{}' do arquivo {}...", bookName, filePath);

        String rawText;
        try {
            rawText = extractTextFromTxt(filePath);
        } catch (IOException e) {
            logger.error("ERRO: Não foi possível ler o arquivo de notas em '{}'. Pulando este livro.", filePath);
            return 0; // Retorna 0 notas processadas
        }

        // A primeira linha do arquivo é o título, o resto são as notas.
        String[] lines = rawText.split("\n", 2);
        String contentToParse = lines.length > 1 ? lines[1] : "";

        // Usa a mesma lógica de split do Python: quebra o texto pelo marcador de nota "* "
        String[] noteBlocks = contentToParse.split("\n\\*\\s+");
        int processedCount = 0;

        for (String block : noteBlocks) {
            if (block.trim().isEmpty()) continue;

            // Regex para capturar a referência (ex: "1.1-2.25") e o resto do conteúdo
            Pattern pattern = Pattern.compile("^([\\d.:,-]+[\\w-]*)\\s+(.*)", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(block.trim());

            if (matcher.find()) {
                String reference = matcher.group(1);
                String noteContent = matcher.group(2);

                try {
                    StudyNote note = new StudyNote();
                    note.setSource(sourceName);
                    note.setBook(bookName);
                    note.setNoteContent(cleanChunkText(noteContent));

                    int[] parsedRef = parseReference(reference);
                    note.setStartChapter(parsedRef[0]);
                    note.setStartVerse(parsedRef[1]);
                    note.setEndChapter(parsedRef[2]);
                    note.setEndVerse(parsedRef[3]);

                    try {
                        PGvector vector = geminiApiClient.generateEmbedding(note.getNoteContent());
                        note.setNoteVector(convertPGvectorToFloatArray(vector));
                    } catch (Exception e) {
                        logger.warn("Não foi possível gerar embedding para a nota de {}:{}. Erro: {}", bookName, reference, e.getMessage());
                    }

                    studyNoteRepository.save(note);
                    processedCount++;
                } catch (Exception e) {
                    logger.error("Falha ao processar bloco de nota para '{}'. Referência: '{}'. Erro: {}", bookName, reference, e.getMessage());
                }
            }
        }
        logger.info("✔ Sucesso! {} notas processadas para {}.", processedCount, bookName);
        return processedCount;
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

    private int[] parseReference(String reference) {
        // Limpa a string de referência de quaisquer caracteres que não sejam dígitos, pontos ou hífens.
        String cleanReference = reference.replaceAll("[^\\d.\\-]", "");

        String[] parts = cleanReference.split("-");
        String[] startRef = parts[0].split("\\.");

        // Verificação de segurança para referências malformadas
        if (startRef.length < 2) {
            throw new IllegalArgumentException("Referência inicial malformada: " + reference);
        }

        int startChapter = Integer.parseInt(startRef[0]);
        int startVerse = Integer.parseInt(startRef[1]);

        int endChapter = startChapter;
        int endVerse = startVerse;

        if (parts.length > 1) {
            String endPart = parts[1];

            if (endPart.isEmpty()){
                // Lida com casos como "1.1-" que podem vir do PDF
                return new int[]{startChapter, startVerse, endChapter, endVerse};
            }

            if (endPart.contains(".")) {
                String[] endRef = endPart.split("\\.");
                if (endRef.length < 2) {
                    throw new IllegalArgumentException("Referência final malformada: " + reference);
                }
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