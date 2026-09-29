package sn.samapiece.audit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
class AuditEndpointIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String HORAIRES = "{\"lundi\":{\"ouvert\":true,\"debut\":\"08:00\",\"fin\":\"18:00\"}}";
    private static final String MOT_DE_PASSE_CLAIR = "MotDePasse123!";

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

    @Autowired
    private EvenementAuditService evenementAuditService;

    @BeforeEach
    void nettoyer() {
        jdbcTemplate.update("DELETE FROM evenement_audit");
        retraitRepository.deleteAll();
        pieceRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM piece_sequence");
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

    private String creerEtLoginToken(String matricule, Role role, Poste poste) throws Exception {
        agentRepository.save(new Agent(poste, matricule, "Diop Awa", role, passwordEncoder.encode(MOT_DE_PASSE_CLAIR)));
        String corps = OBJECT_MAPPER.writeValueAsString(new LoginRequest(matricule, MOT_DE_PASSE_CLAIR));
        String reponse = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return OBJECT_MAPPER.readTree(reponse).get("accessToken").asText();
    }

    @Test
    void lister_commeAgent_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00800", Role.AGENT, poste);

        mockMvc.perform(get("/api/v1/audit/evenements").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void lister_commeChefPoste_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00801", Role.CHEF_POSTE, poste);

        mockMvc.perform(get("/api/v1/audit/evenements").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void lister_commeAdminRegional_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00802", Role.ADMIN_REGIONAL, poste);

        mockMvc.perform(get("/api/v1/audit/evenements").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void lister_commeAuditeur_shouldRetourner200() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00803", Role.AUDITEUR, poste);

        mockMvc.perform(get("/api/v1/audit/evenements").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void lister_sansToken_shouldRetourner401() throws Exception {
        mockMvc.perform(get("/api/v1/audit/evenements")).andExpect(status().isUnauthorized());
    }

    private void inserer(String action, String entiteCible) {
        evenementAuditService.enregistrer(
                UUID.randomUUID(), "AGENT", action, entiteCible, UUID.randomUUID(), "{}", "127.0.0.1");
    }

    private void insererJeuDeDonnees() {
        inserer("PIECE_CREEE", "PIECE");
        inserer("PIECE_CONSULTEE", "PIECE");
        inserer("PIECE_CREEE", "AGENT");
    }

    @Test
    void lister_filtreAction_shouldRetournerSeulementCetteAction() throws Exception {
        String token = creerEtLoginToken("PN-2024-00810", Role.AUDITEUR, creerPoste());
        insererJeuDeDonnees();

        mockMvc.perform(get("/api/v1/audit/evenements").param("action", "PIECE_CREEE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[?(@.action != 'PIECE_CREEE')]").isEmpty());
    }

    @Test
    void lister_filtreEntite_shouldRetournerSeulementCetteEntite() throws Exception {
        String token = creerEtLoginToken("PN-2024-00811", Role.ADMIN_NATIONAL, creerPoste());
        insererJeuDeDonnees();

        mockMvc.perform(get("/api/v1/audit/evenements").param("entiteCible", "PIECE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[?(@.entiteCible != 'PIECE')]").isEmpty());
    }

    @Test
    void lister_filtreActionEtEntite_shouldAppliquerUnEt() throws Exception {
        String token = creerEtLoginToken("PN-2024-00812", Role.AUDITEUR, creerPoste());
        insererJeuDeDonnees();

        mockMvc.perform(get("/api/v1/audit/evenements").param("action", "PIECE_CREEE").param("entiteCible", "PIECE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void lister_filtreValeurInconnue_shouldRetournerPageVide() throws Exception {
        String token = creerEtLoginToken("PN-2024-00813", Role.AUDITEUR, creerPoste());
        insererJeuDeDonnees();

        mockMvc.perform(get("/api/v1/audit/evenements").param("action", "INCONNUE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void lister_sansFiltre_shouldRetournerTousLesEvenements() throws Exception {
        String token = creerEtLoginToken("PN-2024-00814", Role.AUDITEUR, creerPoste());
        insererJeuDeDonnees();

        mockMvc.perform(get("/api/v1/audit/evenements").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void lister_commeAdminNational_shouldRetourner200EtFormatPagine() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00804", Role.ADMIN_NATIONAL, poste);

        mockMvc.perform(get("/api/v1/audit/evenements").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").exists())
                .andExpect(jsonPath("$.number").exists())
                .andExpect(jsonPath("$.size").exists());
    }
}
