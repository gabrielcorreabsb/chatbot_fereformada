package br.com.fereformada.api.service;

import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChunkingService {

    // DTOs (Data Transfer Objects) para retornar dados estruturados
    public record ParsedChunk(String content, String chapterTitle, int chapterNumber, int sectionNumber) {}
    public record ParsedQuestionChunk(String question, String answer, int questionNumber) {}

    // --- MÉTODOS PÚBLICOS (A Interface do Serviço) ---

    public List<ParsedChunk> parseWestminsterConfession(String rawText) {
        String regex = "CAPÍTULO (\\d+):\\s*(.*)";
        return parseByChapterAndThenNumberedSections(rawText, regex);
    }

    // MÉTODO NOVO E DEDICADO PARA AS INSTITUTAS
    public List<ParsedChunk> parseCalvinInstitutes(String rawText) {
        // Regex para encontrar o início de um capítulo. Ex: "CAPÍTULO I"
        String chapterRegex = "(?m)^CAPÍTULO\\s+([IVXLCDM]+)$";
        return parseByChapterAndThenNumberedSections(rawText, chapterRegex);
    }

    public List<ParsedQuestionChunk> parseWestminsterLargerCatechism(String rawText) {
        String regex = "^\\s*(\\d+)\\.\\s*(.*?)\\?";
        return parseQuestionsAndAnswers(rawText, regex);
    }

    public List<ParsedQuestionChunk> parseWestminsterShorterCatechism(String rawText) {
        String regex = "^\\s*Pergunta\\s*(\\d+)\\.\\s*(.*?)(?=R\\.)";
        return parseQuestionsAndAnswers(rawText, regex);
    }

    // --- MÉTODOS PRIVADOS (A Lógica Interna) ---

    private List<ParsedChunk> parseByChapterAndThenNumberedSections(String rawText, String chapterRegexStr) {
        List<ParsedChunk> finalChunks = new ArrayList<>();
        Pattern chapterPattern = Pattern.compile(chapterRegexStr, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        Matcher chapterMatcher = chapterPattern.matcher(rawText);

        // Estrutura para guardar os dados de cada capítulo encontrado
        record ChapterMarker(String rawNumber, int startIndex) {}
        List<ChapterMarker> markers = new ArrayList<>();
        while (chapterMatcher.find()) {
            markers.add(new ChapterMarker(chapterMatcher.group(1), chapterMatcher.start()));
        }

        if (markers.isEmpty()) {
            if (!rawText.isBlank()) finalChunks.add(new ParsedChunk(rawText, "Documento Completo", 0, 0));
            return finalChunks;
        }

        // Itera sobre os capítulos encontrados para processar cada um
        for (int i = 0; i < markers.size(); i++) {
            int blockStart = markers.get(i).startIndex();
            int blockEnd = (i + 1 < markers.size()) ? markers.get(i + 1).startIndex() : rawText.length();
            String chapterBlock = rawText.substring(blockStart, blockEnd);

            // Extrai o título do capítulo (geralmente as linhas após o marcador de capítulo)
            Matcher titleMatcher = chapterPattern.matcher(chapterBlock);
            String currentChapterTitle = "Título não encontrado";
            if (titleMatcher.find()) {
                // Tenta pegar o segundo grupo da regex como título, se existir
                if (titleMatcher.groupCount() > 1 && titleMatcher.group(2) != null) {
                    currentChapterTitle = titleMatcher.group(2).trim().replaceAll("[\\n\\r]+", " ");
                } else { // Se a regex só captura o número (como nas Institutas)
                    String[] lines = chapterBlock.split("\\R");
                    if (lines.length > 1) {
                        // Junta as próximas linhas que parecem ser o título
                        StringBuilder titleBuilder = new StringBuilder();
                        for(int j = 1; j < lines.length; j++) {
                            if(lines[j].matches("^\\s*\\d+\\..*")) break; // Para se encontrar a primeira seção
                            titleBuilder.append(lines[j].trim()).append(" ");
                        }
                        currentChapterTitle = titleBuilder.toString().trim();
                    }
                }
            }
            int currentChapterNumber = i + 1; // Usar a ordem é mais seguro

            // Encontra todas as seções numeradas dentro do bloco do capítulo
            Pattern sectionPattern = Pattern.compile("(?m)^(\\d+)\\.\\s+");
            Matcher sectionMatcher = sectionPattern.matcher(chapterBlock);

            List<Integer> sectionStartIndexes = new ArrayList<>();
            while (sectionMatcher.find()) {
                sectionStartIndexes.add(sectionMatcher.start());
            }

            if(sectionStartIndexes.isEmpty()){
                finalChunks.add(new ParsedChunk(chapterBlock, currentChapterTitle, currentChapterNumber, 0));
                continue;
            }

            // Para cada seção, extrai seu conteúdo
            for (int j = 0; j < sectionStartIndexes.size(); j++) {
                int sectionStartInBlock = sectionStartIndexes.get(j);
                int sectionEndInBlock = (j + 1 < sectionStartIndexes.size()) ? sectionStartIndexes.get(j + 1) : chapterBlock.length();
                String sectionBlock = chapterBlock.substring(sectionStartInBlock, sectionEndInBlock);

                Matcher currentSectionMatcher = sectionPattern.matcher(sectionBlock);
                if (currentSectionMatcher.find()) {
                    int sectionNumber = Integer.parseInt(currentSectionMatcher.group(1));
                    String content = sectionBlock.substring(currentSectionMatcher.end()).trim();
                    if (!content.isEmpty()) {
                        finalChunks.add(new ParsedChunk(content, currentChapterTitle, currentChapterNumber, sectionNumber));
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