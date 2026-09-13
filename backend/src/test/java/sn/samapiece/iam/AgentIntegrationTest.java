package sn.samapiece.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
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
class AgentIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String HORAIRES = "{\"lundi\":{\"ouvert\":true,\"debut\":\"08:00\",\"fin\":\"18:00\"}}";

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private PosteRepository posteRepository;

    @Autowired
    private RegionRepository regionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    @Test
    void persisterEtRelireAgent_shouldMapperTousLesChamps() {
        Poste poste = creerPoste();

        Agent agent = agentRepository.save(
                new Agent(poste, "PN-2024-00123", "Diop Awa", Role.CHEF_POSTE, "$2a$10$hashopaque"));

        List<Agent> agents = agentRepository.findAll();

        assertThat(agents).hasSize(1);
        Agent relu = agents.get(0);
        assertThat(relu.getId()).isEqualTo(agent.getId());
        assertThat(relu.getId()).isNotNull();
        assertThat(relu.getMatricule()).isEqualTo("PN-2024-00123");
        assertThat(relu.getNom()).isEqualTo("Diop Awa");
        assertThat(relu.getRole()).isEqualTo(Role.CHEF_POSTE);
        assertThat(relu.getHashMotDePasse()).isEqualTo("$2a$10$hashopaque");
        assertThat(relu.isActif()).isTrue();
        assertThat(relu.getDerniereConnexion()).isNull();
        assertThat(relu.getPoste().getId()).isEqualTo(poste.getId());
        assertThat(relu.getCreeLe()).isNotNull();
        assertThat(relu.getMajLe()).isNotNull();
    }

    @Test
    void insertAgentAvecMatriculeDuplique_shouldViolerContrainteUnique() {
        Poste poste = creerPoste();
        agentRepository.saveAndFlush(
                new Agent(poste, "PN-2024-00001", "Diop Awa", Role.AGENT, "$2a$10$hash1"));

        assertThatThrownBy(() -> agentRepository.saveAndFlush(
                        new Agent(poste, "PN-2024-00001", "Fall Moussa", Role.CHEF_POSTE, "$2a$10$hash2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void insertAgentAvecRoleInvalide_shouldViolerContrainteCheck() {
        Poste poste = creerPoste();

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO agent (id, poste_id, matricule, nom, role, hash_mot_de_passe) "
                                + "VALUES (?, ?, 'PN-2024-00099', 'Agent Invalide', 'role_inexistant', 'hash')",
                        UUID.randomUUID(),
                        poste.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
