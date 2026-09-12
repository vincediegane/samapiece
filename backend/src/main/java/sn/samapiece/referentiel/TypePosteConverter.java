package sn.samapiece.referentiel;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Sérialise {@link TypePoste} vers les valeurs SQL en minuscules attendues par la
 * contrainte CHECK de la table {@code poste} ({@code police}/{@code gendarmerie}).
 */
@Converter
public class TypePosteConverter implements AttributeConverter<TypePoste, String> {

    @Override
    public String convertToDatabaseColumn(TypePoste attribute) {
        return attribute == null ? null : attribute.name().toLowerCase();
    }

    @Override
    public TypePoste convertToEntityAttribute(String dbData) {
        return dbData == null ? null : TypePoste.valueOf(dbData.toUpperCase());
    }
}
