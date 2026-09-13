package sn.samapiece.iam;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Sérialise {@link Role} vers les valeurs SQL en minuscules attendues par la
 * contrainte CHECK de la table {@code agent} ({@code agent}, {@code chef_poste}, etc.).
 */
@Converter
public class RoleConverter implements AttributeConverter<Role, String> {

    @Override
    public String convertToDatabaseColumn(Role attribute) {
        return attribute == null ? null : attribute.name().toLowerCase();
    }

    @Override
    public Role convertToEntityAttribute(String dbData) {
        return dbData == null ? null : Role.valueOf(dbData.toUpperCase());
    }
}
