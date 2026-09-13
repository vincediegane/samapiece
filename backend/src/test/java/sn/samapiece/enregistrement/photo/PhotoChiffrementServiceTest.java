package sn.samapiece.enregistrement.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import sn.samapiece.enregistrement.photo.PhotoChiffrementService.PhotoChiffree;

class PhotoChiffrementServiceTest {

    private static final String CLE_VALIDE_BASE64 =
            Base64.getEncoder().encodeToString(new byte[32]);

    private PhotoChiffrementService service() {
        return new PhotoChiffrementService(CLE_VALIDE_BASE64);
    }

    @Test
    void chiffrer_puisDechiffrer_shouldRetournerOctetsOriginaux() {
        PhotoChiffrementService service = service();
        byte[] octetsClair = "contenu-binaire-de-test".getBytes(StandardCharsets.UTF_8);

        PhotoChiffree chiffre = service.chiffrer(octetsClair);
        byte[] octetsDechiffres = service.dechiffrer(chiffre.octetsChiffres(), chiffre.ivBase64());

        assertThat(octetsDechiffres).isEqualTo(octetsClair);
        assertThat(chiffre.octetsChiffres()).isNotEqualTo(octetsClair);
    }

    @Test
    void chiffrer_shouldGenererUnIvDifferentAChaqueAppel() {
        PhotoChiffrementService service = service();
        byte[] octetsClair = "contenu".getBytes(StandardCharsets.UTF_8);

        PhotoChiffree premier = service.chiffrer(octetsClair);
        PhotoChiffree second = service.chiffrer(octetsClair);

        assertThat(premier.ivBase64()).isNotEqualTo(second.ivBase64());
    }

    @Test
    void dechiffrer_avecDonneesAlterees_shouldLeverIllegalStateException() {
        PhotoChiffrementService service = service();
        PhotoChiffree chiffre = service.chiffrer("contenu".getBytes(StandardCharsets.UTF_8));
        byte[] octetsAlteres = chiffre.octetsChiffres().clone();
        octetsAlteres[0] ^= 0x01;

        assertThatThrownBy(() -> service.dechiffrer(octetsAlteres, chiffre.ivBase64()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void dechiffrer_avecIvIncorrect_shouldLeverIllegalStateException() {
        PhotoChiffrementService service = service();
        PhotoChiffree chiffre = service.chiffrer("contenu".getBytes(StandardCharsets.UTF_8));
        String ivIncorrect = Base64.getEncoder().encodeToString(new byte[12]);

        assertThatThrownBy(() -> service.dechiffrer(chiffre.octetsChiffres(), ivIncorrect))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructeur_avecCleDeTailleIncorrecte_shouldLeverIllegalStateException() {
        String cleTropCourte = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new PhotoChiffrementService(cleTropCourte))
                .isInstanceOf(IllegalStateException.class);
    }
}
