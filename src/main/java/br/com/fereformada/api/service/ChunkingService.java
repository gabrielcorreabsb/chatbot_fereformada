package br.com.fereformada.api.service;

import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChunkingService {

    // DTOs para retorno de dados estruturados
    public record ParsedChunk(String content, String chapterTitle, int chapterNumber, int sectionNumber) {}
    public record ParsedQuestionChunk(String question, String answer, int questionNumber) {}

    // --- MÉTODOS PÚBLICOS ---

    public List<ParsedChunk> parseWestminsterConfession(String rawText) {
        // A Regex específica para a Confissão agora vive aqui, encapsulada.
        String regex = "CAPÍTULO (\\d+):\\s*(.*)";
        return parseChaptersAndSections(rawText, regex);
    }

    public List<ParsedQuestionChunk> parseWestminsterLargerCatechism(String rawText) {
        // Regex específica para o Catecismo Maior
        String regex = "^\\s*(\\d+)\\.\\s*(.*?)\\?";
        return parseQuestionsAndAnswers(rawText, regex);
    }

    public List<ParsedQuestionChunk> parseWestminsterShorterCatechism(String rawText) {
        // Regex específica para o Breve Catecismo
        String regex = "^\\s*Pergunta\\s*(\\d+)\\.\\s*(.*?)(?=R\\.)";
        return parseQuestionsAndAnswers(rawText, regex);
    }


    // --- MÉTODOS PRIVADOS (LÓGICA INTERNA) ---

    private List<ParsedChunk> parseChaptersAndSections(String rawText, String chapterRegexStr) {
        List<ParsedChunk> finalChunks = new ArrayList<>();
        Pattern chapterPattern = Pattern.compile(chapterRegexStr, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

        Matcher chapterMatcher = chapterPattern.matcher(rawText);
        List<Integer> chapterStartIndexes = new ArrayList<>();
        while(chapterMatcher.find()) {
            chapterStartIndexes.add(chapterMatcher.start());
        }

        if (chapterStartIndexes.isEmpty()) {
            if (!rawText.isBlank()) {
                finalChunks.add(new ParsedChunk(rawText, "Documento Completo", 0, 0));
            }
            return finalChunks;
        }

        for (int i = 0; i < chapterStartIndexes.size(); i++) {
            int blockStart = chapterStartIndexes.get(i);
            int blockEnd = (i + 1 < chapterStartIndexes.size()) ? chapterStartIndexes.get(i + 1) : rawText.length();
            String chapterBlock = rawText.substring(blockStart, blockEnd);

            chapterMatcher.region(blockStart, blockEnd);
            String currentChapterTitle = "";
            int currentChapterNumber = 0;
            if (chapterMatcher.find()) {
                currentChapterNumber = Integer.parseInt(chapterMatcher.group(1));
                currentChapterTitle = chapterMatcher.group(2).trim();
            }

            Pattern sectionPattern = Pattern.compile("^\\s*(\\d+)\\.\\s+", Pattern.MULTILINE);
            String[] sections = sectionPattern.split(chapterBlock);

            int sectionCounter = 1;
            for (String sectionContent : sections) {
                String trimmedContent = sectionContent.trim();
                if (trimmedContent.isEmpty() || (sectionCounter == 1 && trimmedContent.equalsIgnoreCase(currentChapterTitle))) {
                    continue;
                }
                finalChunks.add(new ParsedChunk(trimmedContent, currentChapterTitle, currentChapterNumber, sectionCounter++));
            }
        }
        return finalChunks;
    }

    private List<ParsedQuestionChunk> parseQuestionsAndAnswers(String rawText, String questionRegexStr) {
        List<ParsedQuestionChunk> finalChunks = new ArrayList<>();
        Pattern questionPattern = Pattern.compile(questionRegexStr, Pattern.MULTILINE | Pattern.DOTALL);

        Matcher questionMatcher = questionPattern.matcher(rawText);

        // Estrutura para guardar os dados de cada pergunta encontrada
        record QuestionMarker(String text, int number, int startIndex, int endIndex) {}
        List<QuestionMarker> markers = new ArrayList<>();

        while(questionMatcher.find()) {
            markers.add(new QuestionMarker(
                    questionMatcher.group(2).trim(), // O texto da pergunta
                    Integer.parseInt(questionMatcher.group(1)), // O número
                    questionMatcher.start(), // Onde a pergunta começa
                    questionMatcher.end() // Onde a pergunta termina
            ));
        }

        if (markers.isEmpty()) {
            return finalChunks; // Nenhuma pergunta encontrada
        }

        // Itera sobre as perguntas encontradas para extrair as respostas entre elas
        for (int i = 0; i < markers.size(); i++) {
            QuestionMarker currentQuestion = markers.get(i);

            int answerStartIndex = currentQuestion.endIndex();
            int answerEndIndex = (i + 1 < markers.size()) ? markers.get(i + 1).startIndex() : rawText.length();

            String answer = rawText.substring(answerStartIndex, answerEndIndex).trim();

            // Limpeza final para remover "R." e referências bíblicas
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