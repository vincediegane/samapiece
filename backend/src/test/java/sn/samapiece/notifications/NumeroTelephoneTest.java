package sn.samapiece.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NumeroTelephoneTest {

    @Test
    void de_avecNumeroLocalNeufChiffres_devraitAjouterLePrefixe221() {
        NumeroTelephone numero = NumeroTelephone.de("771234567");

        assertThat(numero.valeurBrute()).isEqualTo("+221771234567");
    }

    @Test
    void de_avecNumeroDejaAuFormatE164_neDevraitPasDoublerLePrefixe() {
        NumeroTelephone numero = NumeroTelephone.de("+221771234567");

        assertThat(numero.valeurBrute()).isEqualTo("+221771234567");
    }

    @Test
    void de_devraitTrimmerLaSaisie() {
        NumeroTelephone numero = NumeroTelephone.de("  771234567  ");

        assertThat(numero.valeurBrute()).isEqualTo("+221771234567");
    }

    @Test
    void de_avecNumeroNull_devraitLeverIllegalArgumentException() {
        assertThatThrownBy(() -> NumeroTelephone.de(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void de_avecNumeroVide_devraitLeverIllegalArgumentException() {
        assertThatThrownBy(() -> NumeroTelephone.de("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toString_devraitMasquerLeNumero() {
        NumeroTelephone numero = NumeroTelephone.de("+221771234567");

        assertThat(numero.toString()).isEqualTo("+221XXXXXXX67");
    }

    @Test
    void toString_avecNumeroLocalDoncPrefixeAjoute_devraitMasquerLeNumero() {
        NumeroTelephone numero = NumeroTelephone.de("771234567");

        assertThat(numero.toString()).isEqualTo("+221XXXXXXX67");
    }

    @Test
    void toString_neDevraitJamaisContenirLaValeurBrute() {
        NumeroTelephone numero = NumeroTelephone.de("+221771234567");

        assertThat(numero.toString()).doesNotContain("771234");
    }

    @Test
    void toString_avecNumeroCourt_devraitMasquerCompletement() {
        NumeroTelephone numero = NumeroTelephone.de("12345");

        assertThat(numero.toString()).isEqualTo("***");
    }

    @Test
    void equals_devraitEtreBaseSurLaValeurNormalisee() {
        NumeroTelephone premier = NumeroTelephone.de("771234567");
        NumeroTelephone second = NumeroTelephone.de("+221771234567");

        assertThat(premier).isEqualTo(second);
        assertThat(premier).hasSameHashCodeAs(second);
    }

    @Test
    void equals_avecNumerosDifferents_devraitEtreFaux() {
        NumeroTelephone premier = NumeroTelephone.de("771234567");
        NumeroTelephone second = NumeroTelephone.de("771234568");

        assertThat(premier).isNotEqualTo(second);
    }
}
