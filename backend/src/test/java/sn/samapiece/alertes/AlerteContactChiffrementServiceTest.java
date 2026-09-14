package sn.samapiece.alertes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import sn.samapiece.alertes.AlerteContactChiffrementService.ContactChiffre;

class AlerteContactChiffrementServiceTest {

    private static final String CLE_VALIDE_BASE64 =
            Base64.getEncoder().encodeToString(new byte[32]);

    private AlerteContactChiffrementService service() {
        return new AlerteContactChiffrementService(CLE_VALIDE_BASE64);
    }

    @Test
    void chiffrer_puisDechiffrer_shouldRetournerOctetsOriginaux() {
        AlerteContactChiffrementService service = service();
        byte[] octetsClair = "+221771234567".getBytes(StandardCharsets.UTF_8);

        ContactChiffre chiffre = service.chiffrer(octetsClair);
        byte[] octetsDechiffres = service.dechiffrer(chiffre.octetsChiffres(), chiffre.ivBase64());

        assertThat(octetsDechiffres).isEqualTo(octetsClair);
        assertThat(chiffre.octetsChiffres()).isNotEqualTo(octetsClair);
    }

    @Test
    void chiffrer_shouldGenererUnIvDifferentAChaqueAppel() {
        AlerteContactChiffrementService service = service();
        byte[] octetsClair = "+221771234567".getBytes(StandardCharsets.UTF_8);

        ContactChiffre premier = service.chiffrer(octetsClair);
        ContactChiffre second = service.chiffrer(octetsClair);

        assertThat(premier.ivBase64()).isNotEqualTo(second.ivBase64());
    }

    @Test
    void dechiffrer_avecDonneesAlterees_shouldLeverIllegalStateException() {
        AlerteContactChiffrementService service = service();
        ContactChiffre chiffre = service.chiffrer("+221771234567".getBytes(StandardCharsets.UTF_8));
        byte[] octetsAlteres = chiffre.octetsChiffres().clone();
        octetsAlteres[0] ^= 0x01;

        assertThatThrownBy(() -> service.dechiffrer(octetsAlteres, chiffre.ivBase64()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void dechiffrer_avecIvIncorrect_shouldLeverIllegalStateException() {
        AlerteContactChiffrementService service = service();
        ContactChiffre chiffre = service.chiffrer("+221771234567".getBytes(StandardCharsets.UTF_8));
        String ivIncorrect = Base64.getEncoder().encodeToString(new byte[12]);

        assertThatThrownBy(() -> service.dechiffrer(chiffre.octetsChiffres(), ivIncorrect))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructeur_avecCleDeTailleIncorrecte_shouldLeverIllegalStateException() {
        String cleTropCourte = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new AlerteContactChiffrementService(cleTropCourte))
                .isInstanceOf(IllegalStateException.class);
    }
}
