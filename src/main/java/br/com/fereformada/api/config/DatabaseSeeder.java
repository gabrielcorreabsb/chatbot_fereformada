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
        // ------------------------------------

        // O if se torna desnecessário, mas podemos mantê-lo por segurança
        // if (workRepository.count() > 0) {
        //     logger.info("Banco de dados já populado. Nenhuma ação necessária.");
        //     return;
        // }

        logger.info("Iniciando a carga de dados completa via Seeder...");

        // Passo 1: Criar os dados de base PRIMEIRO
        Topic sovereigntyTopic = createTopic("Soberania de Deus", "A doutrina do controle e autoridade suprema de Deus sobre toda a criação.");
        Topic decreesTopic = createTopic("Decretos de Deus", "Os decretos eternos de Deus pelos quais Ele preordenou tudo o que acontece.");
        Author westminsterAssembly = createAuthor("Assembleia de Westminster", "Puritanos", "1643-01-01", "1653-01-01");
        createAuthor("João Calvino", "Reforma", "1509-07-10", "1564-05-27");

        // Passo 2: Agora, carregar a obra que depende dos dados acima
        List<Topic> availableTopics = List.of(sovereigntyTopic, decreesTopic);
        loadWestminsterConfession(westminsterAssembly, availableTopics);

        logger.info("Carga de dados finalizada com sucesso.");
    }

    private void loadWestminsterConfession(Author author, List<Topic> availableTopics) throws IOException {
        logger.info("Carregando a Confissão de Fé de Westminster...");

        Work confession = new Work();
        confession.setTitle("Confissão de Fé de Westminster");
        confession.setAuthor(author);
        confession.setPublicationYear(1646);
        confession.setType("CONFISSAO");
        workRepository.save(confession);

        String filePath = "classpath:data-content/pdf/confissao_westminster.pdf"; // Verifique se o nome do arquivo está correto
        String rawText = extractTextFromPdf(filePath);

        // Regex final e robusta, baseada no seu documento
        String westminsterRegex = "(CAPÍTULO \\d+:.*)";
        List<String> rawChunks = chunkingService.chunkByStructure(rawText, westminsterRegex);

        logger.info("Encontrados {} chunks estruturados no documento.", rawChunks.size());

        for (String rawChunk : rawChunks) {
            // Limpeza feita AQUI, em cada chunk individualmente
            // 1. Remove quebras de linha no meio das frases
            String cleanedChunk = rawChunk.replaceAll("(?<!\n)\r?\n(?!\n)", " ");
            // 2. Remove números de página que estão sozinhos em uma linha
            cleanedChunk = cleanedChunk.replaceAll("(?m)^\\s*\\d+\\s*$", "");
            // 3. Normaliza espaços múltiplos
            cleanedChunk = cleanedChunk.replaceAll(" +", " ").trim();

            if (cleanedChunk.isBlank() || cleanedChunk.length() < 50) continue;

            ContentChunk chunk = new ContentChunk();
            chunk.setContent(cleanedChunk);
            chunk.setWork(confession);

            // Lógica de etiquetagem (tagging)
            Set<Topic> topicsForChunk = new HashSet<>();
            if (cleanedChunk.toLowerCase().contains("decreto de deus") || cleanedChunk.toLowerCase().contains("predestinou")) {
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
        logger.info("Confissão de Fé de Westminster carregada e salva no banco.");
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

    private String cleanExtractedText(String rawText) {
        String cleanedText = rawText.replaceAll("(?<!\n)\r?\n(?!\n)", " ");
        cleanedText = cleanedText.replaceAll(" +", " ");
        return cleanedText;
    }
}