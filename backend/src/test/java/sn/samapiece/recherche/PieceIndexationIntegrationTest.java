package sn.samapiece.recherche;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Config;
import com.meilisearch.sdk.exceptions.MeilisearchException;
import com.meilisearch.sdk.model.TasksQuery;
import com.meilisearch.sdk.model.TasksResults;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import sn.samapiece.enregistrement.Piece;
import sn.samapiece.enregistrement.PieceRepository;
import sn.samapiece.enregistrement.TypeDocument;
import sn.samapiece.iam.Agent;
import sn.samapiece.iam.AgentRepository;
import sn.samapiece.iam.Role;
import sn.samapiece.iam.web.LoginRequest;
import sn.samapiece.referentiel.Poste;
import sn.samapiece.referentiel.PosteRepository;
import sn.samapiece.referentiel.Region;
import sn.samapiece.referentiel.RegionRepository;
import sn.samapiece.referentiel.TypePoste;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PieceIndexationIntegrationTest {

    private static final String MEILI_MASTER_KEY = "test-master-key";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> meilisearch = new GenericContainer<>("getmeili/meilisearch:v1.10")
            .withExposedPorts(7700)
            .withEnv("MEILI_MASTER_KEY", MEILI_MASTER_KEY)
            .withEnv("MEILI_NO_ANALYTICS", "true")
            .waitingFor(Wait.forHttp("/health"));

    @DynamicPropertySource
    static void proprietesMeilisearch(DynamicPropertyRegistry registry) {
        registry.add("samapiece.meilisearch.host",
                () -> "http://" + meilisearch.getHost() + ":" + meilisearch.getMappedPort(7700));
        registry.add("samapiece.meilisearch.api-key", () -> MEILI_MASTER_KEY);
        registry.add("samapiece.meilisearch.index-pieces", () -> "pieces-test");
    }

    private static final String HORAIRES = "{\"lundi\":{\"ouvert\":true,\"debut\":\"08:00\",\"fin\":\"18:00\"}}";
    private static final String MOT_DE_PASSE_CLAIR = "MotDePasse123!";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PieceRepository pieceRepository;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private PosteRepository posteRepository;

    @Autowired
    private RegionRepository regionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PieceRechercheIndexService indexService;

    private Client clientMeilisearchDeTest;

    @BeforeEach
    void nettoyer() {
        pieceRepository.deleteAll();
        agentRepository.deleteAll();
        posteRepository.deleteAll();
        regionRepository.deleteAll();
        clientMeilisearchDeTest = new Client(new Config(
                "http://" + meilisearch.getHost() + ":" + meilisearch.getMappedPort(7700), MEILI_MASTER_KEY));
    }

    private Region creerRegion(String nom) {
        return regionRepository.save(new Region(nom));
    }

    private Poste creerPoste(Region region, String nom) {
        return posteRepository.save(new Poste(
                region,
                nom,
                TypePoste.POLICE,
                "Adresse " + nom,
                "+221338210000",
                HORAIRES,
                14.6928,
                -17.4467));
    }

    private Poste creerPoste() {
        return creerPoste(creerRegion("Dakar"), "Commissariat Central Dakar");
    }

    private Agent creerAgentActif(Poste poste, String matricule, Role role) {
        return agentRepository.save(
                new Agent(poste, matricule, "Diop Awa", role, passwordEncoder.encode(MOT_DE_PASSE_CLAIR)));
    }

    private String login(String matricule, String motDePasse) throws Exception {
        String corps = OBJECT_MAPPER.writeValueAsString(new LoginRequest(matricule, motDePasse));
        String reponse = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return OBJECT_MAPPER.readTree(reponse).get("accessToken").asText();
    }

    private String creerEtLoginToken(String matricule, Role role, Poste poste) throws Exception {
        creerAgentActif(poste, matricule, role);
        return login(matricule, MOT_DE_PASSE_CLAIR);
    }

    private String creerPieceJson(LocalDate dateDepot) {
        return "{"
                + "\"typeDocument\":\"" + TypeDocument.CNI.name() + "\","
                + "\"nomTitulaire\":\"Fall\","
                + "\"prenomTitulaire\":\"Moussa\","
                + "\"numeroDocument\":\"1234567890123\","
                + "\"dateNaissanceTitulaire\":\"1990-05-12\","
                + "\"dateDepot\":\"" + dateDepot + "\","
                + "\"etatDocument\":\"bon état\","
                + "\"remarques\":\"trouvée sur la voie publique\""
                + "}";
    }

    @Test
    void creer_commeAgent_devraitIndexerLaPieceAvecLesChampsAttendusEtSansDonneesSensibles() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00700", Role.AGENT, poste);

        String reponse = mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceJson(LocalDate.of(2026, 9, 13))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID pieceId = UUID.fromString(OBJECT_MAPPER.readTree(reponse).get("id").asText());

        attendreLaTacheDIndexationLaPlusRecente();

        String documentBrut = clientMeilisearchDeTest.index("pieces-test").getRawDocument(pieceId.toString());
        Map<String, Object> document = OBJECT_MAPPER.readValue(documentBrut, new TypeReference<>() {
        });

        assertThat(document.keySet())
                .containsExactlyInAnyOrder("id", "typeDocument", "nomTitulaire", "prenomTitulaire", "poste", "statut");
        assertThat(document.get("typeDocument")).isEqualTo("CNI");
        assertThat(document.get("nomTitulaire")).isEqualTo("Fall");
        assertThat(document.get("prenomTitulaire")).isEqualTo("Moussa");
        assertThat(document.get("poste")).isEqualTo(poste.getNom());
        assertThat(document.get("statut")).isEqualTo("DISPONIBLE");
    }

    @Test
    void desindexer_devraitSupprimerLeDocumentDeLIndexSansToucherALaBase() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00701", Role.AGENT, poste);

        String reponse = mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceJson(LocalDate.of(2026, 9, 13))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID pieceId = UUID.fromString(OBJECT_MAPPER.readTree(reponse).get("id").asText());
        attendreLaTacheDIndexationLaPlusRecente();
        assertThat(clientMeilisearchDeTest.index("pieces-test").getRawDocument(pieceId.toString())).isNotNull();

        indexService.desindexer(pieceId);
        attendreLaTacheDIndexationLaPlusRecente();

        assertThatThrownBy(() -> clientMeilisearchDeTest.index("pieces-test").getDocument(pieceId.toString(), Map.class))
                .isInstanceOf(MeilisearchException.class);
        Piece piece = pieceRepository.findById(pieceId).orElseThrow();
        assertThat(piece.getStatut().name()).isEqualTo("DISPONIBLE");
    }

    private void attendreLaTacheDIndexationLaPlusRecente() throws MeilisearchException {
        TasksResults taches = clientMeilisearchDeTest.getTasks(
                new TasksQuery().setIndexUids(new String[] {"pieces-test"}).setLimit(1));
        if (taches.getResults().length > 0) {
            clientMeilisearchDeTest.waitForTask(taches.getResults()[0].getUid());
        }
    }
}
