package sn.samapiece.recherche.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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

/**
 * Isole le CAPTCHA du rate limiting en abaissant fortement le seuil d'échecs consécutifs
 * ({@code samapiece.captcha.seuil-echecs-consecutifs}) et en relevant la capacité du bucket
 * ({@code samapiece.rate-limiting.recherche-publique.capacite}), voir spec #19.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RecherchePubliqueCaptchaIntegrationTest {

    private static final Pattern QUESTION = Pattern.compile("^(\\d+) \\+ (\\d+) = \\?$");
    private static final String MEILI_MASTER_KEY = "test-master-key";
    private static final String HORAIRES = "{\"lundi\":{\"ouvert\":true,\"debut\":\"08:00\",\"fin\":\"18:00\"}}";
    private static final String MOT_DE_PASSE_CLAIR = "MotDePasse123!";
    private static final String NUMERO_DOCUMENT_REEL = "1234567890123";
    private static final String IP_SIMULEE = "5.6.7.8";
    private static final int SEUIL_ECHECS = 2;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> meilisearch = new GenericContainer<>("getmeili/meilisearch:v1.10")
            .withExposedPorts(7700)
            .withEnv("MEILI_MASTER_KEY", MEILI_MASTER_KEY)
            .withEnv("MEILI_NO_ANALYTICS", "true")
            .waitingFor(Wait.forHttp("/health"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void proprietes(DynamicPropertyRegistry registry) {
        registry.add("samapiece.meilisearch.host",
                () -> "http://" + meilisearch.getHost() + ":" + meilisearch.getMappedPort(7700));
        registry.add("samapiece.meilisearch.api-key", () -> MEILI_MASTER_KEY);
        registry.add("samapiece.meilisearch.index-pieces", () -> "pieces-test-captcha");
        registry.add("spring.data.redis.host", () -> redis.getHost());
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("samapiece.captcha.seuil-echecs-consecutifs", () -> SEUIL_ECHECS);
        registry.add("samapiece.captcha.ttl-compteur-echecs-secondes", () -> 600);
        registry.add("samapiece.captcha.ttl-defi-secondes", () -> 120);
        registry.add("samapiece.rate-limiting.recherche-publique.capacite", () -> 1000);
        registry.add("samapiece.rate-limiting.recherche-publique.periode-secondes", () -> 60);
    }

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

    @Autowired
    private StringRedisTemplate redisTemplate;

    private Client clientMeilisearchDeTest;

    @BeforeEach
    void nettoyer() {
        pieceRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM piece_sequence");
        agentRepository.deleteAll();
        posteRepository.deleteAll();
        regionRepository.deleteAll();
        // Le compteur d'echecs Redis pour IP_SIMULEE n'est jamais nettoye par le code de production
        // (il expire seulement via son TTL glissant) : sans ce reset explicite, les methodes @Test de
        // cette classe partagent le meme conteneur Redis et donc le meme compteur pour la meme IP
        // simulee, faisant echouer les tests selon leur ordre d'execution (deja constate en CI).
        redisTemplate.delete("recherche-publique:echecs:" + IP_SIMULEE);
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

    private void attendreLaTacheDIndexationLaPlusRecente() throws MeilisearchException {
        TasksResults taches = clientMeilisearchDeTest.getTasks(
                new TasksQuery().setIndexUids(new String[] {"pieces-test-captcha"}).setLimit(1));
        if (taches.getResults().length > 0) {
            clientMeilisearchDeTest.waitForTask(taches.getResults()[0].getUid());
        }
    }

    private JsonNode creerPieceEtAttendreIndexation() throws Exception {
        Poste poste = creerPoste(creerRegion("Dakar"), "Commissariat Central Dakar");
        creerAgentActif(poste, "PN-2024-00950", Role.AGENT);
        String token = login("PN-2024-00950", MOT_DE_PASSE_CLAIR);

        String corpsPiece = "{"
                + "\"typeDocument\":\"" + TypeDocument.CNI.name() + "\","
                + "\"nomTitulaire\":\"Fall\","
                + "\"prenomTitulaire\":\"Moussa\","
                + "\"numeroDocument\":\"" + NUMERO_DOCUMENT_REEL + "\","
                + "\"dateNaissanceTitulaire\":\"1990-05-12\","
                + "\"dateDepot\":\"" + LocalDate.of(2026, 9, 13) + "\","
                + "\"etatDocument\":\"bon état\","
                + "\"remarques\":\"trouvée sur la voie publique\""
                + "}";

        String reponse = mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpsPiece))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        attendreLaTacheDIndexationLaPlusRecente();
        return OBJECT_MAPPER.readTree(reponse);
    }

    private MvcResult rechercherCriteresInsuffisants() throws Exception {
        String corps = "{"
                + "\"typeDocument\":null,"
                + "\"nomTitulaire\":\"Fall\","
                + "\"prenomTitulaire\":null,"
                + "\"numeroDocument\":\"" + NUMERO_DOCUMENT_REEL + "\","
                + "\"dateNaissanceTitulaire\":null"
                + "}";
        return mockMvc.perform(post("/api/v1/recherche-publique")
                        .with(request -> {
                            request.setRemoteAddr(IP_SIMULEE);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps))
                .andReturn();
    }

    @Test
    void nEchecsConsecutifs_devraitExigerLeCaptcha() throws Exception {
        for (int i = 0; i < SEUIL_ECHECS; i++) {
            MvcResult resultat = rechercherCriteresInsuffisants();
            assertThat(resultat.getResponse().getStatus()).isEqualTo(400);
        }

        MvcResult resultat = rechercherCriteresInsuffisants();

        assertThat(resultat.getResponse().getStatus()).isEqualTo(428);
        JsonNode corps = OBJECT_MAPPER.readTree(resultat.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(corps.get("code").asText()).isEqualTo("CAPTCHA_REQUIS");
        assertThat(corps.get("captchaChallengeUrl").asText()).isEqualTo("/api/v1/recherche-publique/captcha");
    }

    @Test
    void resolutionDuCaptcha_devraitDebloquerLaRequeteSuivante() throws Exception {
        for (int i = 0; i <= SEUIL_ECHECS; i++) {
            rechercherCriteresInsuffisants();
        }

        String reponseDefi = mockMvc.perform(get("/api/v1/recherche-publique/captcha"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode defi = OBJECT_MAPPER.readTree(reponseDefi);
        String captchaToken = defi.get("captchaToken").asText();
        Matcher matcher = QUESTION.matcher(defi.get("question").asText());
        assertThat(matcher.matches()).isTrue();
        int somme = Integer.parseInt(matcher.group(1)) + Integer.parseInt(matcher.group(2));

        MvcResult resultat = mockMvc.perform(post("/api/v1/recherche-publique")
                        .with(request -> {
                            request.setRemoteAddr(IP_SIMULEE);
                            return request;
                        })
                        .header("X-Captcha-Token", captchaToken)
                        .header("X-Captcha-Reponse", String.valueOf(somme))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"typeDocument\":\"CNI\","
                                + "\"nomTitulaire\":\"Fall\","
                                + "\"prenomTitulaire\":\"Moussa\","
                                + "\"numeroDocument\":\"" + NUMERO_DOCUMENT_REEL + "\","
                                + "\"dateNaissanceTitulaire\":null"
                                + "}"))
                .andReturn();

        assertThat(resultat.getResponse().getStatus()).isNotEqualTo(428);
    }

    @Test
    void succesReinitialiseLeCompteur_devraitAutoriserDeNouveauNMoinsUnEchecSansCaptcha() throws Exception {
        JsonNode pieceCreee = creerPieceEtAttendreIndexation();
        assertThat(pieceCreee).isNotNull();

        for (int i = 0; i < SEUIL_ECHECS - 1; i++) {
            MvcResult resultat = rechercherCriteresInsuffisants();
            assertThat(resultat.getResponse().getStatus()).isEqualTo(400);
        }

        MvcResult rechercheReussie = mockMvc.perform(post("/api/v1/recherche-publique")
                        .with(request -> {
                            request.setRemoteAddr(IP_SIMULEE);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"typeDocument\":\"CNI\","
                                + "\"nomTitulaire\":\"Fall\","
                                + "\"prenomTitulaire\":\"Moussa\","
                                + "\"numeroDocument\":\"" + NUMERO_DOCUMENT_REEL + "\","
                                + "\"dateNaissanceTitulaire\":null"
                                + "}"))
                .andReturn();
        JsonNode corpsReussite = OBJECT_MAPPER.readTree(
                rechercheReussie.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(corpsReussite.get("trouve").asBoolean()).isTrue();

        MvcResult resultatApresSucces = rechercherCriteresInsuffisants();

        assertThat(resultatApresSucces.getResponse().getStatus()).isEqualTo(400);
    }
}
