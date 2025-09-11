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
import java.util.Arrays;
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


    public DatabaseSeeder(AuthorRepository authorRepository, WorkRepository workRepository,
                          TopicRepository topicRepository, ContentChunkRepository contentChunkRepository,
                          ResourceLoader resourceLoader, ChunkingService chunkingService) {
        this.authorRepository = authorRepository;
        this.workRepository = workRepository;
        this.topicRepository = topicRepository;
        this.contentChunkRepository = contentChunkRepository;
        this.resourceLoader = resourceLoader;
        this.chunkingService = chunkingService;

    }

    @Override
    @Transactional
    public void run(String... args) throws Exception {

        // --- ADICIONE ESTE BLOCO DE CÓDIGO ---
        // Limpa as tabelas na ordem correta para evitar erros de chave estrangeira
        logger.info("Limpando o banco de dados para uma nova carga...");
        contentChunkRepository.deleteAll();
        workRepository.deleteAll();
        topicRepository.deleteAll();
        authorRepository.deleteAll();
        logger.info("Iniciando a carga de dados completa via Seeder...");

        // Passo 1: Criar/garantir que os dados de base existam
        Topic sovereigntyTopic = createTopic("Soberania de Deus", "...");
        Topic decreesTopic = createTopic("Decretos de Deus", "...");
        // --- ADICIONE OS NOVOS TÓPICOS AQUI ---
        Topic justificationTopic = createTopic("Justificação pela Fé", "A doutrina de como o pecador é declarado justo diante de Deus.");
        Topic lawOfGodTopic = createTopic("A Lei de Deus", "Os mandamentos e estatutos divinos revelados nas Escrituras.");
        Topic scripturesTopic = createTopic("Sagradas Escrituras", "A doutrina sobre a revelação de Deus na Bíblia.");

        Author westminsterAssembly = createAuthor("Assembleia de Westminster", "Puritanos", "1643-01-01", "1653-01-01");

        // Adicione os novos tópicos à lista que será passada aos métodos de carga
        List<Topic> allTopics = List.of(sovereigntyTopic, decreesTopic, justificationTopic, lawOfGodTopic, scripturesTopic);

        // Passo 2: Carregar os documentos
        loadWestminsterConfession(westminsterAssembly, allTopics);
        loadWestminsterCatechism(westminsterAssembly, allTopics);

        logger.info("Carga de dados finalizada com sucesso.");
    }

    private void loadWestminsterConfession(Author author, List<Topic> availableTopics) throws IOException {
        final String WORK_TITLE = "Confissão de Fé de Westminster";
        if (workRepository.findByTitle(WORK_TITLE).isPresent()) {
            logger.info("'{}' já está no banco. Pulando.", WORK_TITLE);
            return;
        }

        logger.info("Carregando e catalogando '{}'...", WORK_TITLE);

        Work confession = new Work();
        confession.setTitle(WORK_TITLE);
        confession.setAuthor(author);
        confession.setPublicationYear(1646);
        confession.setType("CONFISSAO");
        workRepository.save(confession);

        String filePath = "classpath:data-content/pdf/confissao_westminster.pdf";
        String rawText = extractTextFromPdf(filePath);

        // Regex para capturar o NÚMERO (grupo 1) e o TÍTULO (grupo 2) do capítulo
        String westminsterRegex = "CAPÍTULO (\\d+):\\s*(.*)";

        // O serviço agora retorna uma lista de chunks já catalogados por seção
        List<ChunkingService.ParsedChunk> parsedChunks = chunkingService.parseChaptersAndSections(rawText, westminsterRegex);

        logger.info("Encontrados e catalogados {} chunks (seções) no documento.", parsedChunks.size());

        for (ChunkingService.ParsedChunk parsedChunk : parsedChunks) {
            String cleanedContent = cleanChunkText(parsedChunk.content());

            if (cleanedContent.isBlank() || cleanedContent.length() < 20) continue;

            ContentChunk chunk = new ContentChunk();
            // Salvando os dados ricos e catalogados
            chunk.setContent(cleanedContent);
            chunk.setChapterNumber(parsedChunk.chapterNumber());
            chunk.setChapterTitle(parsedChunk.chapterTitle());
            chunk.setSectionNumber(parsedChunk.sectionNumber());
            chunk.setWork(confession);

            // Lógica de etiquetagem (tagging)
            Set<Topic> topicsForChunk = new HashSet<>();
            if (cleanedContent.toLowerCase().contains("decreto de deus") || cleanedContent.toLowerCase().contains("predestinou")) {
                availableTopics.stream()
                        .filter(t -> t.getName().equals("Soberania de Deus"))
                        .findFirst()
                        .ifPresent(topicsForChunk::add);
                availableTopics.stream()
                        .filter(t -> t.getName().equals("Decretos de Deus"))
                        .findFirst()
                        .ifPresent(topicsForChunk::add);
            }

            if (!topicsForChunk.isEmpty()) {
                chunk.setTopics(topicsForChunk);
            }

            contentChunkRepository.save(chunk);
        }
        logger.info("'{}' carregada e salva no banco.", WORK_TITLE);
    }


    // Métodos utilitários para criar as entidades de forma segura
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
            pdfBytes = is.readAllBytes(); // Lê o fluxo de dados para um array de bytes
        }

        // Agora, o PDDocument é carregado a partir do array de bytes
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

    private void loadWestminsterCatechism(Author author, List<Topic> availableTopics) throws IOException {
        final String WORK_TITLE = "Catecismo Maior de Westminster";
        if (workRepository.findByTitle(WORK_TITLE).isPresent()) {
            logger.info("'{}' já está no banco. Pulando.", WORK_TITLE);
            return;
        }

        logger.info("Carregando e catalogando '{}'...", WORK_TITLE);

        Work catechism = new Work();
        catechism.setTitle(WORK_TITLE);
        catechism.setAuthor(author);
        catechism.setPublicationYear(1648);
        catechism.setType("CATECISMO");
        workRepository.save(catechism);

        String filePath = "classpath:data-content/pdf/catecismo_maior_westminster.pdf";
        String rawText = extractTextFromPdf(filePath);

        // Regex para capturar o NÚMERO (grupo 1) e a PERGUNTA (grupo 2)
        String questionRegex = "^\\s*(\\d+)\\.\\s*(.*?)\\?";

        List<ChunkingService.ParsedQuestionChunk> parsedChunks = chunkingService.parseQuestionsAndAnswers(rawText, questionRegex);

        logger.info("Encontrados e catalogados {} chunks (perguntas/respostas) no documento.", parsedChunks.size());

        for (var parsedChunk : parsedChunks) {
            String cleanedAnswer = cleanChunkText(parsedChunk.answer());
            if (cleanedAnswer.isBlank() || cleanedAnswer.length() < 10) continue;

            ContentChunk chunk = new ContentChunk();
            chunk.setQuestion(parsedChunk.question());
            chunk.setContent(cleanedAnswer);
            chunk.setSectionNumber(parsedChunk.questionNumber());
            chunk.setChapterTitle("Catecismo Maior");
            chunk.setWork(catechism);

            // --- LÓGICA DE ETIQUETAGEM (TAGGING) BASEADA NA PERGUNTA ---
            Set<Topic> topicsForChunk = new HashSet<>();
            String questionText = parsedChunk.question().toLowerCase();

            if (questionText.contains("palavra de deus") || questionText.contains("escrituras")) {
                findTopic("Sagradas Escrituras", availableTopics).ifPresent(topicsForChunk::add);
            }
            if (questionText.contains("justificação")) {
                findTopic("Justificação pela Fé", availableTopics).ifPresent(topicsForChunk::add);
            }
            if (questionText.contains("lei de deus") || questionText.contains("mandamentos")) {
                findTopic("A Lei de Deus", availableTopics).ifPresent(topicsForChunk::add);
            }
            // ... adicione mais regras 'if' aqui conforme necessário ...

            if (!topicsForChunk.isEmpty()) {
                chunk.setTopics(topicsForChunk);
            }
            // --- FIM DA LÓGICA DE ETIQUETAGEM ---

            contentChunkRepository.save(chunk);
        }
        logger.info("'{}' carregado e salvo no banco.", WORK_TITLE);
    }

    // Adicione este método utilitário dentro da classe DatabaseSeeder
    private java.util.Optional<Topic> findTopic(String name, List<Topic> topics) {
        return topics.stream()
                .filter(t -> t.getName().equals(name))
                .findFirst();
    }
}