package br.com.fereformada.api.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChunkingService {

    // Classe auxiliar para armazenar a posição de cada título encontrado
    private record SectionMarker(String title, int startIndex) {}

    public List<String> chunkByStructure(String rawText, String structuralRegex) {
        List<String> finalChunks = new ArrayList<>();
        Pattern pattern = Pattern.compile(structuralRegex, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(rawText);

        List<SectionMarker> markers = new ArrayList<>();
        while (matcher.find()) {
            markers.add(new SectionMarker(matcher.group(1).trim(), matcher.start()));
        }

        if (markers.isEmpty()) {
            // Fallback: se nenhum padrão for encontrado, retorna o texto todo como um chunk
            // (ainda teremos a limpeza individual depois)
            if (!rawText.isBlank()) {
                finalChunks.add(rawText);
            }
            return finalChunks;
        }

        // Itera sobre os marcadores para extrair o texto entre eles
        for (int i = 0; i < markers.size(); i++) {
            int startIndex = markers.get(i).startIndex();
            int endIndex = (i + 1 < markers.size()) ? markers.get(i + 1).startIndex() : rawText.length();
            String chunkContent = rawText.substring(startIndex, endIndex);

            if (!chunkContent.isBlank()) {
                finalChunks.add(chunkContent.trim());
            }
        }

        return finalChunks;
    }
}