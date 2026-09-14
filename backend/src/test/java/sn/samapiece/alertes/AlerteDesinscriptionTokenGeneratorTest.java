package sn.samapiece.alertes;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AlerteDesinscriptionTokenGeneratorTest {

    private final AlerteDesinscriptionTokenGenerator generator = new AlerteDesinscriptionTokenGenerator();

    @Test
    void genererBrut_shouldProduireUneChaineDe43CaracteresBase64Url() {
        String token = generator.genererBrut();

        assertThat(token).hasSize(43);
        assertThat(token).matches("^[A-Za-z0-9_-]{43}$");
    }

    @Test
    void genererBrut_shouldProduireDesValeursDifferentesAChaqueAppel() {
        String premier = generator.genererBrut();
        String second = generator.genererBrut();

        assertThat(premier).isNotEqualTo(second);
    }

    @Test
    void hacher_shouldEtreDeterministe() {
        String token = generator.genererBrut();

        assertThat(generator.hacher(token)).isEqualTo(generator.hacher(token));
    }

    @Test
    void hacher_shouldEtreDifferentPourDeuxJetonsDifferents() {
        String premier = generator.genererBrut();
        String second = generator.genererBrut();

        assertThat(generator.hacher(premier)).isNotEqualTo(generator.hacher(second));
    }
}
