package sn.samapiece.alertes.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import sn.samapiece.enregistrement.TypeDocument;

class CreerAlerteRequestTest {

    private static final LocalDate DATE_NAISSANCE = LocalDate.of(1990, 5, 12);
    private static final String CONTACT = "+221771234567";

    @Test
    void estSuffisant_shouldRenvoyerVrai_typeNomEtNumero() {
        CreerAlerteRequest requete = new CreerAlerteRequest(
                TypeDocument.CNI, "Fall", null, "1234567890123", null, CONTACT);

        assertThat(requete.estSuffisant()).isTrue();
    }

    @Test
    void estSuffisant_shouldRenvoyerVrai_typeNomEtDateNaissance() {
        CreerAlerteRequest requete = new CreerAlerteRequest(
                TypeDocument.CNI, "Fall", null, null, DATE_NAISSANCE, CONTACT);

        assertThat(requete.estSuffisant()).isTrue();
    }

    @Test
    void estSuffisant_shouldRenvoyerVrai_typeNomNumeroEtDateNaissance() {
        CreerAlerteRequest requete = new CreerAlerteRequest(
                TypeDocument.CNI, "Fall", "Moussa", "1234567890123", DATE_NAISSANCE, CONTACT);

        assertThat(requete.estSuffisant()).isTrue();
    }

    @Test
    void estSuffisant_shouldRenvoyerFaux_typeManquant() {
        CreerAlerteRequest requete = new CreerAlerteRequest(
                null, "Fall", null, "1234567890123", null, CONTACT);

        assertThat(requete.estSuffisant()).isFalse();
    }

    @Test
    void estSuffisant_shouldRenvoyerFaux_nomManquant() {
        CreerAlerteRequest requete = new CreerAlerteRequest(
                TypeDocument.CNI, null, null, "1234567890123", null, CONTACT);

        assertThat(requete.estSuffisant()).isFalse();
    }

    @Test
    void estSuffisant_shouldRenvoyerFaux_nomVide() {
        CreerAlerteRequest requete = new CreerAlerteRequest(
                TypeDocument.CNI, "", null, "1234567890123", null, CONTACT);

        assertThat(requete.estSuffisant()).isFalse();
    }

    @Test
    void estSuffisant_shouldRenvoyerFaux_nomBlanc() {
        CreerAlerteRequest requete = new CreerAlerteRequest(
                TypeDocument.CNI, "   ", null, "1234567890123", null, CONTACT);

        assertThat(requete.estSuffisant()).isFalse();
    }

    @Test
    void estSuffisant_shouldRenvoyerFaux_niNumeroNiDate() {
        CreerAlerteRequest requete = new CreerAlerteRequest(
                TypeDocument.CNI, "Fall", "Moussa", null, null, CONTACT);

        assertThat(requete.estSuffisant()).isFalse();
    }

    @Test
    void estSuffisant_shouldRenvoyerFaux_numeroVide() {
        CreerAlerteRequest requete = new CreerAlerteRequest(
                TypeDocument.CNI, "Fall", null, "   ", null, CONTACT);

        assertThat(requete.estSuffisant()).isFalse();
    }

    @Test
    void estSuffisant_shouldRenvoyerFaux_toutManquant() {
        CreerAlerteRequest requete = new CreerAlerteRequest(null, null, null, null, null, null);

        assertThat(requete.estSuffisant()).isFalse();
    }

    @Test
    void estSuffisant_shouldIgnorerLeContact_memeAbsent() {
        CreerAlerteRequest requete = new CreerAlerteRequest(
                TypeDocument.CNI, "Fall", null, "1234567890123", null, null);

        assertThat(requete.estSuffisant()).isTrue();
    }
}
