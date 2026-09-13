package sn.samapiece.recherche.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Config;
import com.meilisearch.sdk.exceptions.MeilisearchException;
import com.meilisearch.sdk.model.TasksQuery;
import com.meilisearch.sdk.model.TasksResults;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
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
class RecherchePubliqueIntegrationTest {

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
    private static final String NUMERO_DOCUMENT_REEL = "1234567890123";

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
    private JdbcTemplate jdbcTemplate;

    private Client clientMeilisearchDeTest;

    @BeforeEach
    void nettoyer() {
        pieceRepository.deleteAll();
        // piece_sequence n'est pas exposee par un repository Spring Data mais reference poste par
        // FK : a vider avant posteRepository.deleteAll(), sinon la suppression du poste echoue
        // (meme correctif que PieceIndexationIntegrationTest, ticket #11/#17).
        jdbcTemplate.update("DELETE FROM piece_sequence");
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
                .getContentAsString(StandardCharsets.UTF_8);
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
                + "\"numeroDocument\":\"" + NUMERO_DOCUMENT_REEL + "\","
                + "\"dateNaissanceTitulaire\":\"1990-05-12\","
                + "\"dateDepot\":\"" + dateDepot + "\","
                + "\"etatDocument\":\"bon état\","
                + "\"remarques\":\"trouvée sur la voie publique\""
                + "}";
    }

    private JsonNode creerPieceEtAttendreIndexation() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00800", Role.AGENT, poste);

        String reponse = mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceJson(LocalDate.of(2026, 9, 13))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        attendreLaTacheDIndexationLaPlusRecente();
        return OBJECT_MAPPER.readTree(reponse);
    }

    private void attendreLaTacheDIndexationLaPlusRecente() throws MeilisearchException {
        TasksResults taches = clientMeilisearchDeTest.getTasks(
                new TasksQuery().setIndexUids(new String[] {"pieces-test"}).setLimit(1));
        if (taches.getResults().length > 0) {
            clientMeilisearchDeTest.waitForTask(taches.getResults()[0].getUid());
        }
    }

    private String rechercheJson(
            String typeDocument, String nomTitulaire, String prenomTitulaire, String numeroDocument, String dateNaissance) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"typeDocument\":").append(typeDocument == null ? "null" : "\"" + typeDocument + "\"").append(',');
        json.append("\"nomTitulaire\":").append(nomTitulaire == null ? "null" : "\"" + nomTitulaire + "\"").append(',');
        json.append("\"prenomTitulaire\":").append(prenomTitulaire == null ? "null" : "\"" + prenomTitulaire + "\"").append(',');
        json.append("\"numeroDocument\":").append(numeroDocument == null ? "null" : "\"" + numeroDocument + "\"").append(',');
        json.append("\"dateNaissanceTitulaire\":").append(dateNaissance == null ? "null" : "\"" + dateNaissance + "\"");
        json.append('}');
        return json.toString();
    }

    @Test
    void criteresSuffisantsAvecCorrespondance_devraitRenvoyerTrouveAvecPosteEtReferenceDossier() throws Exception {
        JsonNode pieceCreee = creerPieceEtAttendreIndexation();
        String numeroFiche = pieceCreee.get("numeroFiche").asText();

        String reponse = mockMvc.perform(post("/api/v1/recherche-publique")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rechercheJson("CNI", "Fall", "Moussa", NUMERO_DOCUMENT_REEL, null)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode corps = OBJECT_MAPPER.readTree(reponse);

        assertThat(corps.get("trouve").asBoolean()).isTrue();
        assertThat(corps.get("typeDocument").asText()).isEqualTo("CNI");
        assertThat(corps.get("referenceDossier").asText()).isEqualTo(numeroFiche);
        assertThat(corps.get("poste").get("nom").asText()).isEqualTo("Commissariat Central Dakar");
        assertThat(corps.get("poste").get("adresse").asText()).isEqualTo("Adresse Commissariat Central Dakar");
        assertThat(OBJECT_MAPPER.readTree(corps.get("poste").get("horaires").asText()))
                .isEqualTo(OBJECT_MAPPER.readTree(HORAIRES));
        assertThat(corps.get("poste").get("telephone").asText()).isEqualTo("+221338210000");

        assertThat(corps.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "trouve", "typeDocument", "poste", "referenceDossier");
        assertThat(corps.get("poste").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("nom", "adresse", "horaires", "telephone");
    }

    static Stream<Arguments> criteresInsuffisants() {
        return Stream.of(
                Arguments.of((Object) null, "Fall", null, NUMERO_DOCUMENT_REEL, null),
                Arguments.of("CNI", null, null, NUMERO_DOCUMENT_REEL, null),
                Arguments.of("CNI", "", null, NUMERO_DOCUMENT_REEL, null),
                Arguments.of("CNI", "   ", null, NUMERO_DOCUMENT_REEL, null),
                Arguments.of("CNI", "Fall", "Moussa", null, null));
    }

    @ParameterizedTest
    @MethodSource("criteresInsuffisants")
    void criteresInsuffisants_devraitRenvoyer400CriteresInsuffisantsQuelQueSoitLeContenu(
            String typeDocument, String nomTitulaire, String prenomTitulaire, String numeroDocument, String dateNaissance)
            throws Exception {
        String reponse = mockMvc.perform(post("/api/v1/recherche-publique")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rechercheJson(typeDocument, nomTitulaire, prenomTitulaire, numeroDocument, dateNaissance)))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode corps = OBJECT_MAPPER.readTree(reponse);

        assertThat(corps.get("code").asText()).isEqualTo("CRITERES_INSUFFISANTS");
        assertThat(corps.get("message").asText())
                .isEqualTo("Critères de recherche insuffisants : type, nom, et numéro ou date de naissance sont requis.");
    }

    @Test
    void aucuneCorrespondance_devraitRenvoyerTrouveFalseSansAucuneAutreDonnee() throws Exception {
        creerPieceEtAttendreIndexation();

        String reponse = mockMvc.perform(post("/api/v1/recherche-publique")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rechercheJson("CNI", "NomInexistant", null, NUMERO_DOCUMENT_REEL, null)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode corps = OBJECT_MAPPER.readTree(reponse);

        assertThat(corps.get("trouve").asBoolean()).isFalse();
        assertThat(corps.get("typeDocument").isNull()).isTrue();
        assertThat(corps.get("poste").isNull()).isTrue();
        assertThat(corps.get("referenceDossier").isNull()).isTrue();
    }

    @Test
    void statutNonDisponible_devraitRenvoyerTrouveFalseMemeSiIndexeDisponibleDansMeilisearch() throws Exception {
        JsonNode pieceCreee = creerPieceEtAttendreIndexation();
        UUID pieceId = UUID.fromString(pieceCreee.get("id").asText());
        jdbcTemplate.update("UPDATE piece SET statut = 'retiree' WHERE id = ?", pieceId);

        String reponse = mockMvc.perform(post("/api/v1/recherche-publique")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rechercheJson("CNI", "Fall", "Moussa", NUMERO_DOCUMENT_REEL, null)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode corps = OBJECT_MAPPER.readTree(reponse);

        assertThat(corps.get("trouve").asBoolean()).isFalse();
    }

    @Test
    void numeroErrone_devraitRenvoyerTrouveFalse() throws Exception {
        creerPieceEtAttendreIndexation();

        String reponse = mockMvc.perform(post("/api/v1/recherche-publique")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rechercheJson("CNI", "Fall", "Moussa", "9999999999999", null)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode corps = OBJECT_MAPPER.readTree(reponse);

        assertThat(corps.get("trouve").asBoolean()).isFalse();
    }

    @Test
    void plusieursCriteresUnSeulCorrespond_numeroCorrectDateFausse_devraitRenvoyerTrouveFalse() throws Exception {
        creerPieceEtAttendreIndexation();

        String reponse = mockMvc.perform(post("/api/v1/recherche-publique")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rechercheJson("CNI", "Fall", "Moussa", NUMERO_DOCUMENT_REEL, "2000-01-01")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode corps = OBJECT_MAPPER.readTree(reponse);

        assertThat(corps.get("trouve").asBoolean()).isFalse();
    }

    @Test
    void plusieursCriteresUnSeulCorrespond_numeroFauxDateCorrecte_devraitRenvoyerTrouveFalse() throws Exception {
        creerPieceEtAttendreIndexation();

        String reponse = mockMvc.perform(post("/api/v1/recherche-publique")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rechercheJson("CNI", "Fall", "Moussa", "9999999999999", "1990-05-12")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode corps = OBJECT_MAPPER.readTree(reponse);

        assertThat(corps.get("trouve").asBoolean()).isFalse();
    }
}
