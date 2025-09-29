package br.com.fereformada.api.config;

import com.pgvector.PGvector;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.postgresql.util.PGobject;

import java.sql.SQLException;

// A anotação autoApply = true é a chave.
// Ela diz ao Hibernate para usar este converter para TODOS os campos do tipo PGvector.
@Converter(autoApply = true)
public class PGvectorConverter implements AttributeConverter<PGvector, Object> {

    @Override
    public Object convertToDatabaseColumn(PGvector attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            // Cria um objeto PGobject, define seu tipo como "vector" e passa a representação
            // textual do vetor. Este é o formato que o driver JDBC entende.
            PGobject pgObject = new PGobject();
            pgObject.setType("vector");
            pgObject.setValue(attribute.toString());
            return pgObject;
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao converter PGvector para PGobject", e);
        }
    }

    @Override
    public PGvector convertToEntityAttribute(Object dbData) {
        if (dbData == null) {
            return null;
        }

        // O driver pode retornar o dado como PGobject, então tratamos isso primeiro.
        if (dbData instanceof PGobject) {
            try {
                return new PGvector(((PGobject) dbData).getValue());
            } catch (SQLException e) {
                throw new RuntimeException("Erro ao converter PGobject para PGvector", e);
            }
        }

        if (dbData instanceof PGvector) {
            return (PGvector) dbData;
        }

        if (dbData instanceof String) {
            try {
                return new PGvector((String) dbData);
            } catch (SQLException e) {
                throw new RuntimeException("Erro ao converter string para PGvector", e);
            }
        }

        throw new IllegalArgumentException("Tipo não suportado para conversão para PGvector: " + dbData.getClass().getName());
    }
}