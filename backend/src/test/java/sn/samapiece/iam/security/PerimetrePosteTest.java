package sn.samapiece.iam.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import sn.samapiece.iam.Agent;
import sn.samapiece.iam.Role;
import sn.samapiece.referentiel.Poste;
import sn.samapiece.referentiel.Region;
import sn.samapiece.referentiel.TypePoste;

class PerimetrePosteTest {

    private Region regionAvecId(UUID id) {
        Region region = new Region("Dakar");
        ReflectionTestUtils.setField(region, "id", id);
        return region;
    }

    private Poste posteAvecId(Region region, UUID id) {
        Poste poste = new Poste(
                region, "Commissariat Central Dakar", TypePoste.POLICE,
                "Place de l'Indépendance, Dakar", null, "{}", null, null);
        ReflectionTestUtils.setField(poste, "id", id);
        return poste;
    }

    private Agent agentAvecRoleEtPoste(Role role, Poste poste) {
        return new Agent(poste, "PN-2024-00123", "Diop Awa", role, "$2a$10$hashopaque");
    }

    @Test
    void estDansPerimetre_avecAgentDuMemePoste_shouldRetournerVrai() {
        UUID posteId = UUID.randomUUID();
        Poste poste = posteAvecId(regionAvecId(UUID.randomUUID()), posteId);
        Agent appelant = agentAvecRoleEtPoste(Role.AGENT, poste);

        assertThat(PerimetrePoste.estDansPerimetre(appelant, poste)).isTrue();
    }

    @Test
    void estDansPerimetre_avecAgentDunAutrePoste_shouldRetournerFaux() {
        Region region = regionAvecId(UUID.randomUUID());
        Poste posteAppelant = posteAvecId(region, UUID.randomUUID());
        Poste posteCible = posteAvecId(region, UUID.randomUUID());
        Agent appelant = agentAvecRoleEtPoste(Role.AGENT, posteAppelant);

        assertThat(PerimetrePoste.estDansPerimetre(appelant, posteCible)).isFalse();
    }

    @Test
    void estDansPerimetre_avecChefPosteDuMemePoste_shouldRetournerVrai() {
        UUID posteId = UUID.randomUUID();
        Poste poste = posteAvecId(regionAvecId(UUID.randomUUID()), posteId);
        Agent appelant = agentAvecRoleEtPoste(Role.CHEF_POSTE, poste);

        assertThat(PerimetrePoste.estDansPerimetre(appelant, poste)).isTrue();
    }

    @Test
    void estDansPerimetre_avecChefPosteDunAutrePoste_shouldRetournerFaux() {
        Region region = regionAvecId(UUID.randomUUID());
        Poste posteAppelant = posteAvecId(region, UUID.randomUUID());
        Poste posteCible = posteAvecId(region, UUID.randomUUID());
        Agent appelant = agentAvecRoleEtPoste(Role.CHEF_POSTE, posteAppelant);

        assertThat(PerimetrePoste.estDansPerimetre(appelant, posteCible)).isFalse();
    }

    @Test
    void estDansPerimetre_avecAdminRegionalDeLaMemeRegion_shouldRetournerVrai() {
        Region region = regionAvecId(UUID.randomUUID());
        Poste posteAppelant = posteAvecId(region, UUID.randomUUID());
        Poste posteCible = posteAvecId(region, UUID.randomUUID());
        Agent appelant = agentAvecRoleEtPoste(Role.ADMIN_REGIONAL, posteAppelant);

        assertThat(PerimetrePoste.estDansPerimetre(appelant, posteCible)).isTrue();
    }

    @Test
    void estDansPerimetre_avecAdminRegionalDuneAutreRegion_shouldRetournerFaux() {
        Poste posteAppelant = posteAvecId(regionAvecId(UUID.randomUUID()), UUID.randomUUID());
        Poste posteCible = posteAvecId(regionAvecId(UUID.randomUUID()), UUID.randomUUID());
        Agent appelant = agentAvecRoleEtPoste(Role.ADMIN_REGIONAL, posteAppelant);

        assertThat(PerimetrePoste.estDansPerimetre(appelant, posteCible)).isFalse();
    }

    @Test
    void estDansPerimetre_avecAdminNational_shouldRetournerVraiPourNimporteQuelPoste() {
        Poste posteAppelant = posteAvecId(regionAvecId(UUID.randomUUID()), UUID.randomUUID());
        Poste posteCible = posteAvecId(regionAvecId(UUID.randomUUID()), UUID.randomUUID());
        Agent appelant = agentAvecRoleEtPoste(Role.ADMIN_NATIONAL, posteAppelant);

        assertThat(PerimetrePoste.estDansPerimetre(appelant, posteCible)).isTrue();
    }

    @Test
    void estDansPerimetre_avecAuditeur_shouldRetournerFaux() {
        Region region = regionAvecId(UUID.randomUUID());
        Poste poste = posteAvecId(region, UUID.randomUUID());
        Agent appelant = agentAvecRoleEtPoste(Role.AUDITEUR, poste);

        assertThat(PerimetrePoste.estDansPerimetre(appelant, poste)).isFalse();
    }
}
