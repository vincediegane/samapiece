package sn.samapiece.enregistrement;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Sérialise {@link StatutPiece} vers les valeurs SQL en minuscules attendues par la
 * contrainte CHECK de la table {@code piece} ({@code disponible}, {@code reclamee}, etc.).
 */
@Converter
public class StatutPieceConverter implements AttributeConverter<StatutPiece, String> {

    @Override
    public String convertToDatabaseColumn(StatutPiece attribute) {
        return attribute == null ? null : attribute.name().toLowerCase();
    }

    @Override
    public StatutPiece convertToEntityAttribute(String dbData) {
        return dbData == null ? null : StatutPiece.valueOf(dbData.toUpperCase());
    }
}
