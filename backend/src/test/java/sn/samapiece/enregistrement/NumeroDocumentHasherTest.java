package sn.samapiece.enregistrement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import sn.samapiece.enregistrement.NumeroDocumentHasher.NumeroDocumentHache;

class NumeroDocumentHasherTest {

    private final NumeroDocumentHasher hasher = new NumeroDocumentHasher();

    @Test
    void hacher_shouldGenererUnSelDifferentAChaqueAppel_memePourLeMemeNumero() {
        NumeroDocumentHache premier = hasher.hacher("123456789");
        NumeroDocumentHache second = hasher.hacher("123456789");

        assertThat(premier.sel()).isNotEqualTo(second.sel());
        assertThat(premier.hash()).isNotEqualTo(second.hash());
    }

    @Test
    void masquer_shouldConserverAuMaximumQuatreCaracteresEnClair_numeroLong() {
        NumeroDocumentHache hache = hasher.hacher("1234567890");

        assertThat(hache.masque()).isEqualTo("12●●●●●●90");
    }

    @Test
    void masquer_shouldConserverAuMaximumQuatreCaracteresEnClair_longueurCinq() {
        NumeroDocumentHache hache = hasher.hacher("12345");

        assertThat(hache.masque()).isEqualTo("12●45");
    }

    @Test
    void masquer_shouldMasquerCompletement_longueurExactementQuatre() {
        NumeroDocumentHache hache = hasher.hacher("1234");

        assertThat(hache.masque()).isEqualTo("●●●●");
    }

    @Test
    void masquer_shouldMasquerCompletement_longueurTrois() {
        NumeroDocumentHache hache = hasher.hacher("123");

        assertThat(hache.masque()).isEqualTo("●●●");
    }

    @Test
    void masquer_shouldMasquerCompletement_longueurUn() {
        NumeroDocumentHache hache = hasher.hacher("1");

        assertThat(hache.masque()).isEqualTo("●");
    }

    @Test
    void hacher_shouldProduireUnHashNonReversibleTrivialement() {
        String numeroClair = "123456789";

        NumeroDocumentHache hache = hasher.hacher(numeroClair);

        assertThat(hache.hash()).doesNotContain(numeroClair);
        assertThat(hache.hash()).hasSize(64);
        assertThat(hache.sel()).hasSize(32);
    }

    @Test
    void hacher_shouldRejeterNumeroNull() {
        assertThatThrownBy(() -> hasher.hacher(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void hacher_shouldRejeterNumeroVide() {
        assertThatThrownBy(() -> hasher.hacher("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifier_shouldRenvoyerVrai_numeroCorrectEtSelCorrect() {
        NumeroDocumentHache hache = hasher.hacher("123456789");

        assertThat(hasher.verifier("123456789", hache.sel(), hache.hash())).isTrue();
    }

    @Test
    void verifier_shouldRenvoyerFaux_numeroIncorrect() {
        NumeroDocumentHache hache = hasher.hacher("123456789");

        assertThat(hasher.verifier("999999999", hache.sel(), hache.hash())).isFalse();
    }

    @Test
    void verifier_shouldRenvoyerFaux_selIncorrect() {
        NumeroDocumentHache hache = hasher.hacher("123456789");

        assertThat(hasher.verifier("123456789", "autresel", hache.hash())).isFalse();
    }

    @Test
    void verifier_shouldRejeterNumeroNull() {
        NumeroDocumentHache hache = hasher.hacher("123456789");

        assertThatThrownBy(() -> hasher.verifier(null, hache.sel(), hache.hash()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void verifier_shouldRejeterSelNull() {
        NumeroDocumentHache hache = hasher.hacher("123456789");

        assertThatThrownBy(() -> hasher.verifier("123456789", null, hache.hash()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void verifier_shouldRejeterHashAttenduNull() {
        NumeroDocumentHache hache = hasher.hacher("123456789");

        assertThatThrownBy(() -> hasher.verifier("123456789", hache.sel(), null))
                .isInstanceOf(NullPointerException.class);
    }
}
