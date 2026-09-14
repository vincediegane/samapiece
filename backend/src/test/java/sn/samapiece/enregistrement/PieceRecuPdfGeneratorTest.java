package sn.samapiece.enregistrement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import sn.samapiece.iam.Agent;
import sn.samapiece.iam.Role;
import sn.samapiece.referentiel.Poste;
import sn.samapiece.referentiel.Region;
import sn.samapiece.referentiel.TypePoste;

class PieceRecuPdfGeneratorTest {

    private final PieceRecuPdfGenerator generateur = new PieceRecuPdfGenerator();

    private Piece pieceDeTest(String etatDocument) {
        Region region = new Region("Dakar");
        ReflectionTestUtils.setField(region, "id", UUID.randomUUID());
        Poste poste = new Poste(
                region, "Commissariat Central Dakar", TypePoste.POLICE,
                "Place de l'Indépendance, Dakar", null, "{}", null, null);
        ReflectionTestUtils.setField(poste, "id", UUID.randomUUID());
        Agent agentCreateur = new Agent(poste, "PN-2024-00123", "Diop Awa", Role.AGENT, "$2a$10$hashopaque");

        Piece piece = new Piece(
                "PC-3F2A9C1B-2026-00001",
                poste,
                agentCreateur,
                TypeDocument.CNI,
                "Fall",
                "Moussa",
                "hash-non-affichable",
                "sel-non-affichable",
                "1234****0123",
                LocalDate.of(1990, 5, 12),
                LocalDate.of(2026, 9, 13),
                etatDocument,
                "trouvée sur la voie publique");
        ReflectionTestUtils.setField(piece, "id", UUID.randomUUID());
        return piece;
    }

    private String extraireTexte(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    @Test
    void genererPdf_shouldContenirLesChampsAttendusEtExclureLesDonneesSensibles() throws Exception {
        Piece piece = pieceDeTest("bon état");

        byte[] pdf = generateur.genererPdf(piece);
        String texte = extraireTexte(pdf);

        assertThat(pdf).isNotEmpty();
        assertThat(texte).contains("Reçu de dépôt");
        assertThat(texte).contains("Numéro de fiche : PC-3F2A9C1B-2026-00001");
        assertThat(texte).contains("Poste : Commissariat Central Dakar");
        assertThat(texte).contains("Adresse : Place de l'Indépendance, Dakar");
        assertThat(texte).contains("Date de dépôt : 13/09/2026");
        assertThat(texte).contains("Type de document : CNI");
        assertThat(texte).contains("État du document : bon état");
        assertThat(texte).contains("Ce reçu ne constitue pas une preuve de propriété du document.");
        assertThat(texte).contains("Pour tout renseignement, présentez le numéro de fiche ci-dessus au poste indiqué.");

        assertThat(texte).doesNotContain(piece.getNomTitulaire());
        assertThat(texte).doesNotContain(piece.getPrenomTitulaire());
        assertThat(texte).doesNotContain(piece.getNumeroDocumentMasque());
        assertThat(texte).doesNotContain("12/05/1990");
    }

    @Test
    void genererPdf_avecEtatDocumentNull_shouldAfficherNonRenseigne() throws Exception {
        Piece piece = pieceDeTest(null);

        byte[] pdf = generateur.genererPdf(piece);
        String texte = extraireTexte(pdf);

        assertThat(texte).contains("État du document : Non renseigné");
    }
}
