package br.com.fereformada.api.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChunkingService {

    /**
     * Objeto para retornar os chunks já parsados e catalogados.
     */
    public record ParsedChunk(String content, String chapterTitle, int chapterNumber, int sectionNumber) {}

    // NOVO Record para o Catecismo
    public record ParsedQuestionChunk(String question, String answer, int questionNumber) {}

    public List<ParsedChunk> parseChaptersAndSections(String rawText, String chapterRegexStr) {
        List<ParsedChunk> finalChunks = new ArrayList<>();
        Pattern chapterPattern = Pattern.compile(chapterRegexStr, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

        // 1. Encontra todos os inícios de capítulo para delimitar os blocos de texto
        Matcher chapterTitleMatcher = chapterPattern.matcher(rawText);
        List<Integer> chapterStartIndexes = new ArrayList<>();
        while(chapterTitleMatcher.find()) {
            chapterStartIndexes.add(chapterTitleMatcher.start());
        }

        if (chapterStartIndexes.isEmpty()) {
            if (!rawText.isBlank()) {
                finalChunks.add(new ParsedChunk(rawText, "Documento Completo", 0, 0));
            }
            return finalChunks;
        }

        // 2. Itera sobre cada bloco de capítulo
        for (int i = 0; i < chapterStartIndexes.size(); i++) {
            int blockStart = chapterStartIndexes.get(i);
            int blockEnd = (i + 1 < chapterStartIndexes.size()) ? chapterStartIndexes.get(i + 1) : rawText.length();
            String chapterBlock = rawText.substring(blockStart, blockEnd);

            // 3. Extrai o título e número do capítulo atual
            chapterTitleMatcher.region(blockStart, blockEnd);
            String currentChapterTitle = "";
            int currentChapterNumber = 0;
            if (chapterTitleMatcher.find()) {
                currentChapterNumber = Integer.parseInt(chapterTitleMatcher.group(1));
                currentChapterTitle = chapterTitleMatcher.group(2).trim();
            }

            // 4. Divide o bloco do capítulo em seções (ex: "1. ...", "2. ...")
            // A regex procura por um número seguido de um ponto no início de uma linha
            Pattern sectionPattern = Pattern.compile("^\\s*(\\d+)\\.\\s+", Pattern.MULTILINE);
            String[] sections = sectionPattern.split(chapterBlock);

            int sectionCounter = 1;
            for (String sectionContent : sections) {
                String trimmedContent = sectionContent.trim();
                // Pula o texto que vem antes da primeira seção (o próprio título do capítulo)
                if (trimmedContent.isEmpty() || (sectionCounter == 1 && trimmedContent.equalsIgnoreCase(currentChapterTitle))) {
                    continue;
                }
                finalChunks.add(new ParsedChunk(trimmedContent, currentChapterTitle, currentChapterNumber, sectionCounter++));
            }
        }


        return finalChunks;
    }

    public List<ParsedQuestionChunk> parseQuestionsAndAnswers(String rawText, String questionRegexStr) {
        List<ParsedQuestionChunk> finalChunks = new ArrayList<>();
        Pattern questionPattern = Pattern.compile(questionRegexStr, Pattern.MULTILINE);

        // Divide o texto inteiro usando a pergunta como delimitador
        String[] parts = questionPattern.split(rawText);

        // Encontra todas as perguntas para correlacionar com as respostas
        Matcher questionMatcher = questionPattern.matcher(rawText);
        List<String> questions = new ArrayList<>();
        List<Integer> questionNumbers = new ArrayList<>();
        while(questionMatcher.find()) {
            questionNumbers.add(Integer.parseInt(questionMatcher.group(1))); // Grupo 1: Número
            questions.add(questionMatcher.group(2).trim()); // Grupo 2: Texto da pergunta
        }

        // Correlaciona a pergunta com a resposta (a resposta é a parte que vem DEPOIS da pergunta)
        for (int i = 0; i < questions.size(); i++) {
            // A resposta para a pergunta 'i' está em 'parts[i+1]'
            if (i + 1 < parts.length) {
                String answer = parts[i + 1].trim();
                // Remove as referências bíblicas do final da resposta
                answer = answer.replaceAll("(?m)^[A-Z][a-z]+\\s[\\d.,:;-]+.*$", "").trim();

                if (!answer.isEmpty()) {
                    finalChunks.add(new ParsedQuestionChunk(questions.get(i), answer, questionNumbers.get(i)));
                }
            }
        }
        return finalChunks;
    }
}