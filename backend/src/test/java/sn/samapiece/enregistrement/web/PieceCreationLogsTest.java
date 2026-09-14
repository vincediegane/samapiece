package sn.samapiece.enregistrement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import sn.samapiece.enregistrement.PieceRepository;
import sn.samapiece.enregistrement.RetraitRepository;
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
class PieceCreationLogsTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String HORAIRES = "{\"lundi\":{\"ouvert\":true,\"debut\":\"08:00\",\"fin\":\"18:00\"}}";
    private static final String MOT_DE_PASSE_CLAIR = "MotDePasse123!";
    private static final String NOM_TITULAIRE = "Ndiaye";
    private static final String PRENOM_TITULAIRE = "Coumba";
    private static final String NUMERO_DOCUMENT_CLAIR = "9988776655443";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PieceRepository pieceRepository;

    @Autowired
    private RetraitRepository retraitRepository;

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

    private Logger loggerRacine;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void nettoyer() {
        retraitRepository.deleteAll();
        pieceRepository.deleteAll();
        // piece_sequence n'est pas exposee par un repository Spring Data (acces exclusif via
        // PieceNumeroFicheGenerator/JdbcTemplate) mais reference poste par FK : a vider avant
        // posteRepository.deleteAll(), sinon la suppression du poste echoue.
        jdbcTemplate.update("DELETE FROM piece_sequence");
        agentRepository.deleteAll();
        posteRepository.deleteAll();
        regionRepository.deleteAll();

        loggerRacine = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        loggerRacine.addAppender(appender);
    }

    @AfterEach
    void detacherAppender() {
        loggerRacine.detachAppender(appender);
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

    private String creerPieceRequestJson(LocalDate dateDepot) {
        ObjectNode noeud = OBJECT_MAPPER.createObjectNode();
        noeud.put("typeDocument", TypeDocument.CNI.name());
        noeud.put("nomTitulaire", NOM_TITULAIRE);
        noeud.put("prenomTitulaire", PRENOM_TITULAIRE);
        noeud.put("numeroDocument", NUMERO_DOCUMENT_CLAIR);
        noeud.put("dateNaissanceTitulaire", "1990-05-12");
        noeud.put("dateDepot", dateDepot.toString());
        noeud.put("etatDocument", "bon état");
        noeud.put("remarques", "trouvée sur la voie publique");
        return noeud.toString();
    }

    @Test
    void creer_avecDonneesValides_neDevraitJamaisLogguerDeDonneePersonnelle() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00700", Role.AGENT, poste);

        mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceRequestJson(LocalDate.of(2026, 9, 13))))
                .andExpect(status().isCreated());

        for (ILoggingEvent evenement : appender.list) {
            assertThat(evenement.getFormattedMessage())
                    .doesNotContain(NUMERO_DOCUMENT_CLAIR)
                    .doesNotContain(NOM_TITULAIRE)
                    .doesNotContain(PRENOM_TITULAIRE);
            if (evenement.getThrowableProxy() != null) {
                assertThat(evenement.getThrowableProxy().getMessage())
                        .as("message de l'exception loguee")
                        .doesNotContain(NUMERO_DOCUMENT_CLAIR)
                        .doesNotContain(NOM_TITULAIRE)
                        .doesNotContain(PRENOM_TITULAIRE);
            }
        }
        assertThat(appender.list).isNotEmpty();
    }
}
