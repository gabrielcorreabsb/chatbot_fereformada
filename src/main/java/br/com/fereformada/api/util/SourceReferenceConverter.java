package br.com.fereformada.api.util;

import br.com.fereformada.api.dto.SourceReference;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Collections;
import java.util.List;

@Converter
public class SourceReferenceConverter implements AttributeConverter<List<SourceReference>, String> {
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<SourceReference> attribute) {
        if (attribute == null || attribute.isEmpty()) return null;
        try { return mapper.writeValueAsString(attribute); }
        catch (Exception e) { throw new RuntimeException("Erro ao converter refs para JSON", e); }
    }

    @Override
    public List<SourceReference> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) return Collections.emptyList();
        try { return mapper.readValue(dbData, new TypeReference<List<SourceReference>>() {}); }
        catch (Exception e) { throw new RuntimeException("Erro ao ler JSON de refs", e); }
    }
}