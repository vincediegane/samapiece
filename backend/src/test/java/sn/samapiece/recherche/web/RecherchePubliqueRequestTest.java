package sn.samapiece.recherche.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import sn.samapiece.enregistrement.TypeDocument;

class RecherchePubliqueRequestTest {

    private static final LocalDate DATE_NAISSANCE = LocalDate.of(1990, 5, 12);

    @Test
    void estSuffisant_shouldRenvoyerVrai_typeNomEtNumero() {
        RecherchePubliqueRequest requete =
                new RecherchePubliqueRequest(TypeDocument.CNI, "Fall", null, "1234567890123", null);

        assertThat(requete.estSuffisant()).isTrue();
    }

    @Test
    void estSuffisant_shouldRenvoyerVrai_typeNomEtDateNaissance() {
        RecherchePubliqueRequest requete =
                new RecherchePubliqueRequest(TypeDocument.CNI, "Fall", null, null, DATE_NAISSANCE);

        assertThat(requete.estSuffisant()).isTrue();
    }

    @Test
    void estSuffisant_shouldRenvoyerVrai_typeNomNumeroEtDateNaissance() {
        RecherchePubliqueRequest requete = new RecherchePubliqueRequest(
                TypeDocument.CNI, "Fall", "Moussa", "1234567890123", DATE_NAISSANCE);

        assertThat(requete.estSuffisant()).isTrue();
    }

    @Test
    void estSuffisant_shouldRenvoyerFaux_typeManquant() {
        RecherchePubliqueRequest requete =
                new RecherchePubliqueRequest(null, "Fall", null, "1234567890123", null);

        assertThat(requete.estSuffisant()).isFalse();
    }

    @Test
    void estSuffisant_shouldRenvoyerFaux_nomManquant() {
        RecherchePubliqueRequest requete =
                new RecherchePubliqueRequest(TypeDocument.CNI, null, null, "1234567890123", null);

        assertThat(requete.estSuffisant()).isFalse();
    }

    @Test
    void estSuffisant_shouldRenvoyerFaux_nomVide() {
        RecherchePubliqueRequest requete =
                new RecherchePubliqueRequest(TypeDocument.CNI, "", null, "1234567890123", null);

        assertThat(requete.estSuffisant()).isFalse();
    }

    @Test
    void estSuffisant_shouldRenvoyerFaux_nomBlanc() {
        RecherchePubliqueRequest requete =
                new RecherchePubliqueRequest(TypeDocument.CNI, "   ", null, "1234567890123", null);

        assertThat(requete.estSuffisant()).isFalse();
    }

    @Test
    void estSuffisant_shouldRenvoyerFaux_niNumeroNiDate() {
        RecherchePubliqueRequest requete =
                new RecherchePubliqueRequest(TypeDocument.CNI, "Fall", "Moussa", null, null);

        assertThat(requete.estSuffisant()).isFalse();
    }

    @Test
    void estSuffisant_shouldRenvoyerFaux_numeroVide() {
        RecherchePubliqueRequest requete =
                new RecherchePubliqueRequest(TypeDocument.CNI, "Fall", null, "   ", null);

        assertThat(requete.estSuffisant()).isFalse();
    }

    @Test
    void estSuffisant_shouldRenvoyerFaux_seulementPrenom() {
        RecherchePubliqueRequest requete =
                new RecherchePubliqueRequest(TypeDocument.CNI, "Fall", "Moussa", null, null);

        assertThat(requete.estSuffisant()).isFalse();
    }

    @Test
    void estSuffisant_shouldRenvoyerFaux_toutManquant() {
        RecherchePubliqueRequest requete = new RecherchePubliqueRequest(null, null, null, null, null);

        assertThat(requete.estSuffisant()).isFalse();
    }
}
