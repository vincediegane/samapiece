package sn.samapiece.recherche.securite;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EchecRechercheCounterServiceTest {

    private static final String IP = "1.2.3.4";

    private final CaptchaProperties proprietes = new CaptchaProperties();
    private final EchecRechercheCounterService service = creer(5);

    private EchecRechercheCounterService creer(int seuil) {
        proprietes.setSeuilEchecsConsecutifs(seuil);
        return new EchecRechercheCounterService(proprietes);
    }

    @Test
    void captchaRequis_sansEchec_devraitRenvoyerFalse() {
        assertThat(service.captchaRequis(IP)).isFalse();
    }

    @Test
    void captchaRequis_quandCompteurSousLeSeuil_devraitRenvoyerFalse() {
        for (int i = 0; i < 4; i++) {
            service.enregistrerEchec(IP);
        }

        assertThat(service.captchaRequis(IP)).isFalse();
    }

    @Test
    void captchaRequis_quandCompteurAtteintLeSeuil_devraitRenvoyerTrue() {
        for (int i = 0; i < 5; i++) {
            service.enregistrerEchec(IP);
        }

        assertThat(service.captchaRequis(IP)).isTrue();
    }

    @Test
    void enregistrerSucces_devraitReinitialiserLeCompteur() {
        for (int i = 0; i < 5; i++) {
            service.enregistrerEchec(IP);
        }

        service.enregistrerSucces(IP);

        assertThat(service.captchaRequis(IP)).isFalse();
    }

    @Test
    void compteurs_devraitEtreIndependantsParIp() {
        for (int i = 0; i < 5; i++) {
            service.enregistrerEchec(IP);
        }

        assertThat(service.captchaRequis("5.6.7.8")).isFalse();
    }
}
