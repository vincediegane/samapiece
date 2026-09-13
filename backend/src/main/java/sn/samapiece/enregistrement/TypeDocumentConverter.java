package sn.samapiece.enregistrement;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Sérialise {@link TypeDocument} vers les valeurs SQL en minuscules attendues par la
 * contrainte CHECK de la table {@code piece} ({@code cni}, {@code passeport}, etc.).
 */
@Converter
public class TypeDocumentConverter implements AttributeConverter<TypeDocument, String> {

    @Override
    public String convertToDatabaseColumn(TypeDocument attribute) {
        return attribute == null ? null : attribute.name().toLowerCase();
    }

    @Override
    public TypeDocument convertToEntityAttribute(String dbData) {
        return dbData == null ? null : TypeDocument.valueOf(dbData.toUpperCase());
    }
}
