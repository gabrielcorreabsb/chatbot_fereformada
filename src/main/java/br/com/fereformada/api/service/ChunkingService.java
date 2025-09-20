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

    // --- MÉTODOS PÚBLICOS (A Interface Limpa do Serviço) ---

    public List<ParsedChunk> parseWestminsterConfession(String rawText) {
        String regex = "CAPÍTULO (\\d+):\\s*(.*)";
        return parseByChapterAndThenNumberedSections(rawText, regex);
    }

    // MÉTODO DEDICADO E CORRIGIDO PARA AS INSTITUTAS
    public List<ParsedChunk> parseCalvinInstitutes(String rawText) {
        // Regex para o padrão "CAPÍTULO [ROMANO]" e seu título longo que pode ter quebras de linha.
        // Captura o numeral (grupo 1) e o título (grupo 2)
        String regex = "CAPÍTULO\\s+([IVXLCDM]+)\\s*\\n\\s*([\\s\\S]*?)(?=\\n1\\.\\s)";
        return parseByChapterAndThenNumberedSections(rawText, regex);
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

        record ChapterMarker(int startIndex) {}
        List<ChapterMarker> markers = new ArrayList<>();
        while (chapterMatcher.find()) {
            markers.add(new ChapterMarker(chapterMatcher.start()));
        }

        if (markers.isEmpty()) {
            if (!rawText.isBlank()) finalChunks.add(new ParsedChunk(rawText, "Documento Completo", 0, 0));
            return finalChunks;
        }

        for (int i = 0; i < markers.size(); i++) {
            int blockStart = markers.get(i).startIndex();
            int blockEnd = (i + 1 < markers.size()) ? markers.get(i + 1).startIndex() : rawText.length();
            String chapterBlock = rawText.substring(blockStart, blockEnd);

            chapterMatcher.region(blockStart, blockEnd);
            String currentChapterTitle = "Título não encontrado";
            int currentChapterNumber = i + 1;

            if (chapterMatcher.find()) {
                if (chapterMatcher.groupCount() > 1 && chapterMatcher.group(2) != null) {
                    currentChapterTitle = chapterMatcher.group(2).trim().replaceAll("[\\n\\r]+", " ");
                }
            }

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