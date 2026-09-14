package sn.samapiece.enregistrement;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.format.DateTimeFormatter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

@Component
public class PieceRecuPdfGenerator {

    private static final DateTimeFormatter FORMAT_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final float MARGE_GAUCHE = 60f;
    private static final float TAILLE_TITRE = 16f;
    private static final float TAILLE_TEXTE = 11f;
    private static final float INTERLIGNE = 20f;

    public byte[] genererPdf(Piece piece) {
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            PDFont police = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont policeGrasse = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            float y = page.getMediaBox().getHeight() - 80f;

            try (PDPageContentStream contenu = new PDPageContentStream(document, page)) {
                y = ecrireLigne(contenu, policeGrasse, TAILLE_TITRE, y, "Reçu de dépôt");
                y -= 10f;
                y = ecrireLigne(contenu, police, TAILLE_TEXTE, y, "Numéro de fiche : " + piece.getNumeroFiche());
                y = ecrireLigne(contenu, police, TAILLE_TEXTE, y, "Poste : " + piece.getPoste().getNom());
                y = ecrireLigne(contenu, police, TAILLE_TEXTE, y, "Adresse : " + piece.getPoste().getAdresse());
                y = ecrireLigne(contenu, police, TAILLE_TEXTE, y,
                        "Date de dépôt : " + piece.getDateDepot().format(FORMAT_DATE));
                y = ecrireLigne(contenu, police, TAILLE_TEXTE, y,
                        "Type de document : " + piece.getTypeDocument().name());
                String etatDocument = piece.getEtatDocument() != null ? piece.getEtatDocument() : "Non renseigné";
                y = ecrireLigne(contenu, police, TAILLE_TEXTE, y, "État du document : " + etatDocument);
                y -= 10f;
                y = ecrireLigne(contenu, police, TAILLE_TEXTE, y,
                        "Ce reçu ne constitue pas une preuve de propriété du document.");
                ecrireLigne(contenu, police, TAILLE_TEXTE, y,
                        "Pour tout renseignement, présentez le numéro de fiche ci-dessus au poste indiqué.");
            }

            document.save(sortie);
        } catch (IOException e) {
            throw new UncheckedIOException("Echec de generation du recu PDF.", e);
        }
        return sortie.toByteArray();
    }

    private float ecrireLigne(
            PDPageContentStream contenu, PDFont police, float taille, float y, String texte) throws IOException {
        contenu.beginText();
        contenu.setFont(police, taille);
        contenu.newLineAtOffset(MARGE_GAUCHE, y);
        contenu.showText(texte);
        contenu.endText();
        return y - INTERLIGNE;
    }
}
