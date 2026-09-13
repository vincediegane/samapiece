package sn.samapiece.iam;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class AgentTest {

    private static final int SEUIL = 5;
    private static final Duration DUREE_VERROUILLAGE = Duration.ofMinutes(15);

    private Agent nouvelAgent() {
        return new Agent(null, "PN-2024-00123", "Diop Awa", Role.AGENT, "$2a$10$hashopaque");
    }

    @Test
    void enregistrerEchecConnexion_shouldVerrouillerApresSeuilAtteint() {
        Agent agent = nouvelAgent();

        for (int i = 0; i < SEUIL - 1; i++) {
            agent.enregistrerEchecConnexion(SEUIL, DUREE_VERROUILLAGE);
        }
        assertThat(agent.getTentativesEchouees()).isEqualTo(SEUIL - 1);
        assertThat(agent.estVerrouille()).isFalse();

        agent.enregistrerEchecConnexion(SEUIL, DUREE_VERROUILLAGE);

        assertThat(agent.getTentativesEchouees()).isZero();
        assertThat(agent.estVerrouille()).isTrue();
        assertThat(agent.getVerrouilleJusqua()).isAfter(OffsetDateTime.now());
    }

    @Test
    void enregistrerConnexionReussie_shouldReinitialiserCompteurEtVerrouillage() {
        Agent agent = nouvelAgent();
        for (int i = 0; i < SEUIL; i++) {
            agent.enregistrerEchecConnexion(SEUIL, DUREE_VERROUILLAGE);
        }
        assertThat(agent.estVerrouille()).isTrue();

        agent.enregistrerConnexionReussie();

        assertThat(agent.getTentativesEchouees()).isZero();
        assertThat(agent.getVerrouilleJusqua()).isNull();
        assertThat(agent.estVerrouille()).isFalse();
        assertThat(agent.getDerniereConnexion()).isNotNull();
    }

    @Test
    void estVerrouille_shouldRetournerFauxApresExpiration() {
        Agent agent = nouvelAgent();
        for (int i = 0; i < SEUIL; i++) {
            agent.enregistrerEchecConnexion(SEUIL, Duration.ofMillis(-1));
        }

        assertThat(agent.estVerrouille()).isFalse();
    }
}
