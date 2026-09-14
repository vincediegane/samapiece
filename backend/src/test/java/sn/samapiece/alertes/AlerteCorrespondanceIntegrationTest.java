package sn.samapiece.alertes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import sn.samapiece.alertes.AlerteContactChiffrementService.ContactChiffre;
import sn.samapiece.enregistrement.NumeroDocumentHasher;
import sn.samapiece.enregistrement.NumeroDocumentHasher.NumeroDocumentHache;
import sn.samapiece.enregistrement.PieceRepository;
import sn.samapiece.enregistrement.TypeDocument;
import sn.samapiece.iam.Agent;
import sn.samapiece.iam.AgentRepository;
import sn.samapiece.iam.Role;
import sn.samapiece.iam.web.LoginRequest;
import sn.samapiece.notifications.NumeroTelephone;
import sn.samapiece.referentiel.Poste;
import sn.samapiece.referentiel.PosteRepository;
import sn.samapiece.referentiel.Region;
import sn.samapiece.referentiel.RegionRepository;
import sn.samapiece.referentiel.TypePoste;

/**
 * Test d'integration bout-en-bout du pipeline complet #23 : creation d'une Piece DISPONIBLE ->
 * rapprochement en-process (AFTER_COMMIT) -> publication AMQP -> consommation -> envoi SMS, avec
 * un vrai broker RabbitMQ (Testcontainers), une vraie base Postgres, et Meilisearch (pour ne pas
 * desactiver PieceIndexationListener, qui partage la meme transaction de creation).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AlerteCorrespondanceIntegrationTest {

    private static final String MEILI_MASTER_KEY = "test-master-key";
    private static final String HORAIRES = "{\"lundi\":{\"ouvert\":true,\"debut\":\"08:00\",\"fin\":\"18:00\"}}";
    private static final String MOT_DE_PASSE_CLAIR = "MotDePasse123!";
    private static final String CONTACT_CLAIR = "+221771234567";
    private static final String NUMERO_DOCUMENT = "1234567890123";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitmq = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management-alpine"));

    @Container
    static GenericContainer<?> meilisearch = new GenericContainer<>("getmeili/meilisearch:v1.10")
            .withExposedPorts(7700)
            .withEnv("MEILI_MASTER_KEY", MEILI_MASTER_KEY)
            .withEnv("MEILI_NO_ANALYTICS", "true")
            .waitingFor(Wait.forHttp("/health"));

    @DynamicPropertySource
    static void proprietes(DynamicPropertyRegistry registry) {
        registry.add("samapiece.meilisearch.host",
                () -> "http://" + meilisearch.getHost() + ":" + meilisearch.getMappedPort(7700));
        registry.add("samapiece.meilisearch.api-key", () -> MEILI_MASTER_KEY);
        registry.add("samapiece.meilisearch.index-pieces", () -> "pieces-test");
        registry.add("samapiece.alerte-correspondance.max-tentatives", () -> "3");
        registry.add("samapiece.alerte-correspondance.retry-ttl-30s-ms", () -> "200");
        registry.add("samapiece.alerte-correspondance.retry-ttl-2m-ms", () -> "200");
        registry.add("samapiece.alerte-correspondance.retry-ttl-10m-ms", () -> "200");
    }

    @TestConfiguration
    static class PasserelleSmsTestConfiguration {
        @Bean
        @Primary
        FakePasserelleSms fakePasserelleSms() {
            return new FakePasserelleSms();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AlerteRepository alerteRepository;

    @Autowired
    private AlerteDesinscriptionTokenRepository tokenRepository;

    @Autowired
    private AlerteContactChiffrementService chiffrementService;

    @Autowired
    private NumeroDocumentHasher numeroDocumentHasher;

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
    private FakePasserelleSms passerelleSms;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @BeforeEach
    void nettoyer() {
        tokenRepository.deleteAll();
        alerteRepository.deleteAll();
        pieceRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM piece_sequence");
        agentRepository.deleteAll();
        posteRepository.deleteAll();
        regionRepository.deleteAll();
        passerelleSms.envois.clear();
        amqpAdmin.purgeQueue(AlerteCorrespondanceRabbitConfig.QUEUE_RETRY_30S, true);
        amqpAdmin.purgeQueue(AlerteCorrespondanceRabbitConfig.QUEUE_RETRY_2M, true);
        amqpAdmin.purgeQueue(AlerteCorrespondanceRabbitConfig.QUEUE_RETRY_10M, true);
        amqpAdmin.purgeQueue(AlerteCorrespondanceRabbitConfig.QUEUE_CONSUME, true);
        amqpAdmin.purgeQueue(AlerteCorrespondanceRabbitConfig.QUEUE_DEAD_LETTER, true);
    }

    private Region creerRegion(String nom) {
        return regionRepository.save(new Region(nom));
    }

    private Poste creerPoste(Region region, String nom) {
        return posteRepository.save(new Poste(
                region, nom, TypePoste.POLICE, "Adresse " + nom, "+221338210000", HORAIRES, 14.6928, -17.4467));
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
                + "\"numeroDocument\":\"" + NUMERO_DOCUMENT + "\","
                + "\"dateNaissanceTitulaire\":\"1990-05-12\","
                + "\"dateDepot\":\"" + dateDepot + "\","
                + "\"etatDocument\":\"bon état\","
                + "\"remarques\":\"trouvée sur la voie publique\""
                + "}";
    }

    private Alerte creerAlerteActive(
            String prenomTitulaire, LocalDate dateNaissance, String numeroDocument, String contact) {
        String hash = null;
        String sel = null;
        String masque = null;
        if (numeroDocument != null) {
            NumeroDocumentHache hache = numeroDocumentHasher.hacher(numeroDocument);
            hash = hache.hash();
            sel = hache.sel();
            masque = hache.masque();
        }
        ContactChiffre contactChiffre = chiffrementService.chiffrer(
                NumeroTelephone.de(contact).valeurBrute().getBytes(StandardCharsets.UTF_8));
        Alerte alerte = new Alerte(
                TypeDocument.CNI, "Fall", prenomTitulaire, hash, sel, masque,
                dateNaissance, "sms", contactChiffre.octetsChiffres(), contactChiffre.ivBase64());
        return alerteRepository.saveAndFlush(alerte);
    }

    private String creerAlerteJson(String contact) {
        return "{"
                + "\"typeDocument\":\"CNI\","
                + "\"nomTitulaire\":\"Fall\","
                + "\"prenomTitulaire\":\"Moussa\","
                + "\"numeroDocument\":\"" + NUMERO_DOCUMENT + "\","
                + "\"dateNaissanceTitulaire\":null,"
                + "\"contact\":\"" + contact + "\""
                + "}";
    }

    private String extraireTokenDuMessage(String message) {
        int index = message.indexOf("token=");
        return message.substring(index + "token=".length());
    }

    @Test
    void alerteActiveCorrespondante_devraitRecevoirUnSmsRapidement() throws Exception {
        creerAlerteActive("Moussa", LocalDate.of(1990, 5, 12), NUMERO_DOCUMENT, CONTACT_CLAIR);
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
        JsonNode corps = OBJECT_MAPPER.readTree(reponse);
        String numeroFiche = corps.get("numeroFiche").asText();

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(passerelleSms.envois).hasSize(1);
            FakePasserelleSms.Envoi envoi = passerelleSms.dernierEnvoi();
            assertThat(envoi.destinataire()).isEqualTo(NumeroTelephone.de(CONTACT_CLAIR));
            assertThat(envoi.message()).contains(numeroFiche).contains(poste.getNom());
            assertThat(envoi.message()).doesNotContain("Fall").doesNotContain("Moussa")
                    .doesNotContain(NUMERO_DOCUMENT);
        });
    }

    @Test
    void dechiffrementTouoursEnEchec_devraitFinirEnDeadLetterApresMaxTentatives() throws Exception {
        Alerte alerte = creerAlerteActive("Moussa", LocalDate.of(1990, 5, 12), NUMERO_DOCUMENT, CONTACT_CLAIR);
        jdbcTemplate.update(
                "UPDATE alerte SET contact_iv = ? WHERE id = ?",
                Base64.getEncoder().encodeToString(new byte[12]),
                alerte.getId());
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00801", Role.AGENT, poste);

        mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceJson(LocalDate.of(2026, 9, 13))))
                .andExpect(status().isCreated());

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Object messageRecu = rabbitTemplate.receiveAndConvert(AlerteCorrespondanceRabbitConfig.QUEUE_DEAD_LETTER, 500);
            assertThat(messageRecu).isInstanceOf(AlerteCorrespondanceMessage.class);
            AlerteCorrespondanceMessage message = (AlerteCorrespondanceMessage) messageRecu;
            assertThat(message.alerteId()).isEqualTo(alerte.getId());
            assertThat(message.nombreTentatives()).isEqualTo(4);
        });
        assertThat(passerelleSms.envois).isEmpty();
    }

    @Test
    void alerteDesinscriteAvantDepot_neDevraitJamaisEtreNotifiee() throws Exception {
        mockMvc.perform(post("/api/v1/alertes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerAlerteJson(CONTACT_CLAIR)))
                .andExpect(status().isCreated());
        String tokenBrut = extraireTokenDuMessage(passerelleSms.dernierEnvoi().message());
        mockMvc.perform(delete("/api/v1/alertes/{id}", tokenBrut))
                .andExpect(status().isNoContent());
        passerelleSms.envois.clear();

        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00802", Role.AGENT, poste);
        mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceJson(LocalDate.of(2026, 9, 13))))
                .andExpect(status().isCreated());

        Awaitility.await()
                .pollDelay(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(passerelleSms.envois).isEmpty());
    }
}
