package sn.samapiece.enregistrement.photo;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Sérialise {@link TypePhoto} vers les valeurs SQL en minuscules attendues par la
 * contrainte CHECK de la table {@code photo} ({@code recto}, {@code verso}).
 */
@Converter
public class TypePhotoConverter implements AttributeConverter<TypePhoto, String> {

    @Override
    public String convertToDatabaseColumn(TypePhoto attribute) {
        return attribute == null ? null : attribute.name().toLowerCase();
    }

    @Override
    public TypePhoto convertToEntityAttribute(String dbData) {
        return dbData == null ? null : TypePhoto.valueOf(dbData.toUpperCase());
    }
}
