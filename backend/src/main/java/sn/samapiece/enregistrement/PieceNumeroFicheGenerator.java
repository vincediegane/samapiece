package sn.samapiece.enregistrement;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PieceNumeroFicheGenerator {

    private static final String SQL_UPSERT_SEQUENCE = """
            INSERT INTO piece_sequence (poste_id, annee, dernier_numero)
            VALUES (?, ?, 1)
            ON CONFLICT (poste_id, annee)
            DO UPDATE SET dernier_numero = piece_sequence.dernier_numero + 1
            RETURNING dernier_numero
            """;

    private final JdbcTemplate jdbcTemplate;

    public PieceNumeroFicheGenerator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String genererNumeroFiche(UUID posteId, LocalDate dateDepot) {
        int annee = dateDepot.getYear();
        Integer sequence = jdbcTemplate.queryForObject(SQL_UPSERT_SEQUENCE, Integer.class, posteId, annee);
        String segmentPoste = posteId.toString().replace("-", "").substring(0, 8).toUpperCase();
        return "PC-%s-%d-%05d".formatted(segmentPoste, annee, sequence);
    }
}
