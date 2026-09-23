package sn.samapiece.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import sn.samapiece.referentiel.Poste;
import sn.samapiece.referentiel.PosteRepository;
import sn.samapiece.referentiel.Region;
import sn.samapiece.referentiel.RegionRepository;
import sn.samapiece.referentiel.TypePoste;

@SpringBootTest
@Testcontainers
class AdminBootstrapRunnerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String HORAIRES = "{\"lundi\":{\"ouvert\":true,\"debut\":\"08:00\",\"fin\":\"18:00\"}}";
    private static final String MOT_DE_PASSE_CLAIR = "MotDePasse123!";

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private PosteRepository posteRepository;

    @Autowired
    private RegionRepository regionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MotDePasseTemporaireGenerator motDePasseTemporaireGenerator;

    @BeforeEach
    void nettoyer() {
        agentRepository.deleteAll();
        posteRepository.deleteAll();
        regionRepository.deleteAll();
    }

    private Poste creerPoste() {
        Region region = regionRepository.save(new Region("Dakar"));
        return posteRepository.save(new Poste(
                region,
                "Commissariat Central Dakar",
                TypePoste.POLICE,
                "Place de l'Indépendance, Dakar",
                "+221338210000",
                HORAIRES,
                14.6928,
                -17.4467));
    }

    private AdminBootstrapProperties proprietes(boolean enabled, String matricule, String nom, String posteId) {
        AdminBootstrapProperties properties = new AdminBootstrapProperties();
        properties.setEnabled(enabled);
        properties.setMatricule(matricule);
        properties.setNom(nom);
        properties.setPosteId(posteId);
        return properties;
    }

    private AdminBootstrapRunner runner(AdminBootstrapProperties properties) {
        return new AdminBootstrapRunner(
                agentRepository, posteRepository, passwordEncoder, motDePasseTemporaireGenerator, properties);
    }

    @Test
    void run_avecBaseVideEtBootstrapActive_shouldCreerAdminNational() {
        Poste poste = creerPoste();
        AdminBootstrapProperties properties = proprietes(
                true, "PN-2024-90000", "Admin Bootstrap", poste.getId().toString());

        runner(properties).run();

        assertThat(agentRepository.count()).isEqualTo(1);
        Agent admin = agentRepository.findByMatricule("PN-2024-90000").orElseThrow();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN_NATIONAL);
        assertThat(admin.isDoitChangerMotDePasse()).isTrue();
    }

    @Test
    void run_avecAgentExistant_shouldEtreNoOp() {
        Poste poste = creerPoste();
        agentRepository.save(
                new Agent(poste, "PN-2024-90001", "Agent Existant", Role.AGENT, passwordEncoder.encode(MOT_DE_PASSE_CLAIR)));
        AdminBootstrapProperties properties = proprietes(
                true, "PN-2024-90002", "Admin Bootstrap", poste.getId().toString());

        runner(properties).run();

        assertThat(agentRepository.count()).isEqualTo(1);
    }

    @Test
    void run_avecBootstrapDesactive_shouldEtreNoOp() {
        AdminBootstrapProperties properties = proprietes(false, null, null, null);

        runner(properties).run();

        assertThat(agentRepository.count()).isZero();
    }

    @Test
    void run_avecPosteIdInconnu_shouldLeverIllegalStateException() {
        AdminBootstrapProperties properties = proprietes(
                true, "PN-2024-90003", "Admin Bootstrap", UUID.randomUUID().toString());

        assertThatThrownBy(() -> runner(properties).run()).isInstanceOf(IllegalStateException.class);
        assertThat(agentRepository.count()).isZero();
    }
}
