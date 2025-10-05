package br.com.fereformada.api.service;

import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChunkingService {

    // --- DTOs (Data Transfer Objects) ---
    // ATUALIZADO: Adicionado 'sectionTitle' para carregar o título específico da seção.
    public record ParsedChunk(String content, String chapterTitle, String sectionTitle, int chapterNumber, int sectionNumber) {}
    public record ParsedQuestionChunk(String question, String answer, int questionNumber) {}


    // --- MÉTODOS PÚBLICOS ---

    public List<ParsedChunk> parseWestminsterConfession(String rawText) {
        String regex = "CAPÍTULO (\\d+):\\s*(.*)";
        return parseByChapterAndGenericSections(rawText, regex);
    }

    // MÉTODO ATUALIZADO: Agora chama a nova lógica de parsing dedicada.
    public List<ParsedChunk> parseCalvinInstitutes(String rawText) {
        // A lógica agora está em um método privado e especializado.
        return parseInstitutesByChapter(rawText);
    }

    public List<ParsedQuestionChunk> parseWestminsterLargerCatechism(String rawText) {
        String regex = "^\\s*(\\d+)\\.\\s*(.*?)\\?";
        return parseQuestionsAndAnswers(rawText, regex);
    }

    public List<ParsedQuestionChunk> parseWestminsterShorterCatechism(String rawText) {
        String regex = "^\\s*Pergunta\\s*(\\d+)\\.\\s*(.*?)(?=R\\.)";
        return parseQuestionsAndAnswers(rawText, regex);
    }


    // --- MÉTODOS PRIVADOS ---

    private List<ParsedChunk> parseInstitutesByChapter(String rawText) {
        List<ParsedChunk> finalChunks = new ArrayList<>();
        // Regex para encontrar o início de um livro ou capítulo.
        Pattern chapterPattern = Pattern.compile("(?m)^LIVRO\\s+[A-Z]+|^CAPÍTULO\\s+[IVXLCDM]+");
        Matcher chapterMatcher = chapterPattern.matcher(rawText);

        // Mapeia o início de cada capítulo
        record ChapterMarker(int startIndex) {}
        List<ChapterMarker> markers = new ArrayList<>();
        while (chapterMatcher.find()) {
            markers.add(new ChapterMarker(chapterMatcher.start()));
        }

        if (markers.isEmpty()) {
            if (!rawText.isBlank()) finalChunks.add(new ParsedChunk(rawText, "Documento Completo", null, 0, 0)); // <--- Correto, 5 argumentos
            return finalChunks;
        }

        // Processa o bloco de cada capítulo
        for (int i = 0; i < markers.size(); i++) {
            int blockStart = markers.get(i).startIndex();
            int blockEnd = (i + 1 < markers.size()) ? markers.get(i + 1).startIndex() : rawText.length();
            String chapterBlock = rawText.substring(blockStart, blockEnd);

            // A chave: divide o capítulo em partes, começando por cada número de seção (ex: "1. ", "2. ").
            // O lookahead (?=...) mantém o número na string, o que é essencial.
            String[] parts = chapterBlock.split("(?m)(?=\\s*\\d+\\.\\s)");
            if (parts.length < 2) continue; // Pula capítulos que não têm seções numeradas.

            // --- LÓGICA DE EXTRAÇÃO DE TÍTULOS ---
            // O primeiro item do array ('parts[0]') contém o título do capítulo e o título da primeira seção.
            String headerBlock = parts[0].trim();

            // Regex para encontrar o título da seção (ex: "A. TÍTULO" ou "TÍTULO EM MAIÚSCULAS")
            // no final do bloco de cabeçalho.
            Pattern sectionTitlePattern = Pattern.compile(
                    "([A-Z]\\.\\s[^\n\r]+|[A-ZÁÉÍÓÚÀÂÊÔÃÕÇ\\s]{5,})$"
            );

            Matcher headerMatcher = sectionTitlePattern.matcher(headerBlock);

            String currentChapterTitle;
            String titleForNextSection; // Armazena o título da próxima seção a ser usada no loop

            if (headerMatcher.find()) {
                // Se encontrou, o título da seção é o que foi encontrado
                titleForNextSection = headerMatcher.group(1).trim();
                // O título do capítulo é tudo o que veio ANTES
                currentChapterTitle = headerBlock.substring(0, headerMatcher.start()).trim();
            } else {
                // Se não encontrou um padrão de título de seção, tudo é título do capítulo.
                currentChapterTitle = headerBlock;
                titleForNextSection = null;
            }

            // Limpa quebras de linha do título do capítulo.
            currentChapterTitle = currentChapterTitle.replaceAll("[\\n\\r]+", " ").trim();
            int currentChapterNumber = i + 1;

            // Itera sobre as partes que contêm as seções numeradas
            for (int j = 1; j < parts.length; j++) {
                String currentSectionBlock = parts[j];

                // Pega o número da seção do início do bloco.
                Pattern numberPattern = Pattern.compile("^\\s*(\\d+)\\.\\s*");
                Matcher numberMatcher = numberPattern.matcher(currentSectionBlock);
                if (!numberMatcher.find()) continue;

                int sectionNumber = Integer.parseInt(numberMatcher.group(1));

                // O título desta seção foi encontrado no bloco anterior.
                String currentSectionTitle = titleForNextSection;

                // O conteúdo é tudo após o número da seção.
                String content = numberMatcher.replaceFirst("").trim();

                // Agora, procuramos pelo título da *próxima* seção no final do conteúdo *atual*.
                Matcher contentCleaner = sectionTitlePattern.matcher(content);
                if (contentCleaner.find()) {
                    // Se encontrar, armazena para a próxima iteração do loop.
                    titleForNextSection = contentCleaner.group(1).trim();
                    // E o mais importante: remove o título do conteúdo atual.
                    content = content.substring(0, contentCleaner.start()).trim();
                } else {
                    // Se não encontrar, não há título para a próxima seção.
                    titleForNextSection = null;
                }

                if (!content.isEmpty()) {
                    finalChunks.add(new ParsedChunk(content, currentChapterTitle, currentSectionTitle, currentChapterNumber, sectionNumber));
                }
            }
        }
        return finalChunks;
    }


    // Renomeado para maior clareza, pois é um método mais genérico.
    private List<ParsedChunk> parseByChapterAndGenericSections(String rawText, String chapterRegexStr) {
        List<ParsedChunk> finalChunks = new ArrayList<>();
        Pattern chapterPattern = Pattern.compile(chapterRegexStr, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        Matcher chapterMatcher = chapterPattern.matcher(rawText);

        record ChapterMarker(String rawNumber, int startIndex) {}
        List<ChapterMarker> markers = new ArrayList<>();
        while (chapterMatcher.find()) {
            markers.add(new ChapterMarker(chapterMatcher.group(1), chapterMatcher.start()));
        }

        if (markers.isEmpty()) {
            if (!rawText.isBlank()) finalChunks.add(new ParsedChunk(rawText, "Documento Completo", null, 0, 0));
            return finalChunks;
        }

        for (int i = 0; i < markers.size(); i++) {
            int blockStart = markers.get(i).startIndex();
            int blockEnd = (i + 1 < markers.size()) ? markers.get(i + 1).startIndex() : rawText.length();
            String chapterBlock = rawText.substring(blockStart, blockEnd);

            Matcher titleMatcher = chapterPattern.matcher(chapterBlock);
            String currentChapterTitle = "Título não encontrado";
            if (titleMatcher.find() && titleMatcher.groupCount() > 1 && titleMatcher.group(2) != null) {
                currentChapterTitle = titleMatcher.group(2).trim().replaceAll("[\\n\\r]+", " ");
            }
            int currentChapterNumber = i + 1;

            Pattern sectionPattern = Pattern.compile("(?m)^(\\d+)\\.\\s+");
            Matcher sectionMatcher = sectionPattern.matcher(chapterBlock);

            List<Integer> sectionStartIndexes = new ArrayList<>();
            while (sectionMatcher.find()) {
                sectionStartIndexes.add(sectionMatcher.start());
            }

            if(sectionStartIndexes.isEmpty()){
                finalChunks.add(new ParsedChunk(chapterBlock, currentChapterTitle, null, currentChapterNumber, 0));
                continue;
            }

            for (int j = 0; j < sectionStartIndexes.size(); j++) {
                int sectionStartInBlock = sectionStartIndexes.get(j);
                int sectionEndInBlock = (j + 1 < sectionStartIndexes.size()) ? sectionStartIndexes.get(j + 1) : chapterBlock.length();
                String sectionBlock = chapterBlock.substring(sectionStartInBlock, sectionEndInBlock);

                Matcher currentSectionMatcher = sectionPattern.matcher(sectionBlock);
                if (currentSectionMatcher.find()) {
                    int sectionNumber = Integer.parseInt(currentSectionMatcher.group(1));
                    String content = sectionBlock.substring(currentSectionMatcher.end()).trim();
                    if (!content.isEmpty()) {
                        // Passando 'null' para o novo campo 'sectionTitle'
                        finalChunks.add(new ParsedChunk(content, currentChapterTitle, null, currentChapterNumber, sectionNumber));
                    }
                }
            }
        }
        return finalChunks;
    }

    private List<ParsedQuestionChunk> parseQuestionsAndAnswers(String rawText, String questionRegexStr) {
        List<ParsedQuestionChunk> finalChunks = new ArrayList<>();
        Pattern questionPattern = Pattern.compile(questionRegexStr, Pattern.MULTILINE | Pattern.DOTALL);

        Matcher questionMatcher = questionPattern.matcher(rawText);
        record QuestionMarker(String text, int number, int startIndex, int endIndex) {}
        List<QuestionMarker> markers = new ArrayList<>();
        while(questionMatcher.find()) {
            markers.add(new QuestionMarker(questionMatcher.group(2).trim(), Integer.parseInt(questionMatcher.group(1)), questionMatcher.start(), questionMatcher.end()));
        }

        if (markers.isEmpty()) return finalChunks;

        for (int i = 0; i < markers.size(); i++) {
            QuestionMarker currentQuestion = markers.get(i);
            int answerStartIndex = currentQuestion.endIndex();
            int answerEndIndex = (i + 1 < markers.size()) ? markers.get(i + 1).startIndex() : rawText.length();
            String answer = rawText.substring(answerStartIndex, answerEndIndex).trim();
            answer = answer.replaceAll("^R\\.\\s*", "");
            answer = answer.replaceAll("(?m)^Ref.*$", "").trim();
            answer = answer.replaceAll("(?m)^Referências.*$", "").trim();
            if (!answer.isEmpty()) {
                finalChunks.add(new ParsedQuestionChunk(currentQuestion.text(), answer, currentQuestion.number()));
            }
        }
        return finalChunks;
    }
}