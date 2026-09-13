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

class PerimetreRegionalTest {

    private Region regionAvecId(UUID id) {
        Region region = new Region("Dakar");
        ReflectionTestUtils.setField(region, "id", id);
        return region;
    }

    private Poste posteDansRegion(Region region) {
        return new Poste(
                region, "Commissariat Central Dakar", TypePoste.POLICE,
                "Place de l'Indépendance, Dakar", null, "{}", null, null);
    }

    private Agent agentAvecRoleEtRegion(Role role, Region region) {
        return new Agent(posteDansRegion(region), "PN-2024-00123", "Diop Awa", role, "$2a$10$hashopaque");
    }

    @Test
    void estDansPerimetreRegion_avecAdminNational_shouldRetournerVraiPourNimporteQuelleRegion() {
        Agent appelant = agentAvecRoleEtRegion(Role.ADMIN_NATIONAL, regionAvecId(UUID.randomUUID()));

        assertThat(PerimetreRegional.estDansPerimetreRegion(appelant, UUID.randomUUID())).isTrue();
    }

    @Test
    void estDansPerimetreRegion_avecAdminRegionalDeLaMemeRegion_shouldRetournerVrai() {
        UUID regionId = UUID.randomUUID();
        Agent appelant = agentAvecRoleEtRegion(Role.ADMIN_REGIONAL, regionAvecId(regionId));

        assertThat(PerimetreRegional.estDansPerimetreRegion(appelant, regionId)).isTrue();
    }

    @Test
    void estDansPerimetreRegion_avecAdminRegionalDuneAutreRegion_shouldRetournerFaux() {
        Agent appelant = agentAvecRoleEtRegion(Role.ADMIN_REGIONAL, regionAvecId(UUID.randomUUID()));

        assertThat(PerimetreRegional.estDansPerimetreRegion(appelant, UUID.randomUUID())).isFalse();
    }

    @Test
    void estDansPerimetreRegion_avecChefPoste_shouldRetournerFaux() {
        UUID regionId = UUID.randomUUID();
        Agent appelant = agentAvecRoleEtRegion(Role.CHEF_POSTE, regionAvecId(regionId));

        assertThat(PerimetreRegional.estDansPerimetreRegion(appelant, regionId)).isFalse();
    }

    @Test
    void estDansPerimetreRegion_avecAgent_shouldRetournerFaux() {
        UUID regionId = UUID.randomUUID();
        Agent appelant = agentAvecRoleEtRegion(Role.AGENT, regionAvecId(regionId));

        assertThat(PerimetreRegional.estDansPerimetreRegion(appelant, regionId)).isFalse();
    }

    @Test
    void estDansPerimetreRegion_avecAuditeur_shouldRetournerFaux() {
        UUID regionId = UUID.randomUUID();
        Agent appelant = agentAvecRoleEtRegion(Role.AUDITEUR, regionAvecId(regionId));

        assertThat(PerimetreRegional.estDansPerimetreRegion(appelant, regionId)).isFalse();
    }
}
