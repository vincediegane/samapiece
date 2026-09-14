package sn.samapiece.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import sn.samapiece.enregistrement.Piece;
import sn.samapiece.enregistrement.PieceRepository;
import sn.samapiece.enregistrement.StatutPiece;
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
class StatistiquesPosteIntegrationTest {

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
    private AgentRepository agentRepository;

    @Autowired
    private PosteRepository posteRepository;

    @Autowired
    private RegionRepository regionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void nettoyer() {
        pieceRepository.deleteAll();
        // piece_sequence n'est pas exposee par un repository Spring Data mais reference poste par
        // FK : a vider avant posteRepository.deleteAll(), sinon la suppression du poste echoue.
        jdbcTemplate.update("DELETE FROM piece_sequence");
        agentRepository.deleteAll();
        posteRepository.deleteAll();
        regionRepository.deleteAll();
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

    private Piece creerPieceDisponible(Poste poste, Agent agentCreateur, LocalDate dateDepot) {
        Piece piece = new Piece(
                "PC-" + UUID.randomUUID(),
                poste,
                agentCreateur,
                TypeDocument.CNI,
                "Diop",
                "Awa",
                "hash", "sel", "masque",
                LocalDate.of(1990, 5, 12),
                dateDepot,
                "bon état",
                "trouvée sur la voie publique");
        return pieceRepository.save(piece);
    }

    private Piece creerPieceAvecStatut(
            Poste poste, Agent agentCreateur, StatutPiece statut, LocalDate dateDepot) {
        Piece piece = new Piece(
                "PC-" + UUID.randomUUID(),
                poste,
                agentCreateur,
                TypeDocument.CNI,
                "Diop",
                "Awa",
                "hash", "sel", "masque",
                LocalDate.of(1990, 5, 12),
                dateDepot,
                "bon état",
                "trouvée sur la voie publique");
        ReflectionTestUtils.setField(piece, "statut", statut);
        return pieceRepository.save(piece);
    }

    @Test
    void consulter_avecPiecesConnues_shouldRetournerIndicateursCorrects() throws Exception {
        LocalDate aujourdHui = LocalDate.now();
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00900", Role.AGENT);

        LocalDate dateDepotA = aujourdHui.minusDays(30);
        LocalDate dateDepotB = aujourdHui.minusDays(200);
        LocalDate dateDepotC = aujourdHui.minusDays(5);

        creerPieceDisponible(poste, agentCreateur, dateDepotA);
        creerPieceAvecStatut(poste, agentCreateur, StatutPiece.RECLAMEE, dateDepotB);
        creerPieceAvecStatut(poste, agentCreateur, StatutPiece.RETIREE, dateDepotC);

        long joursA = ChronoUnit.DAYS.between(dateDepotA, aujourdHui);
        long joursB = ChronoUnit.DAYS.between(dateDepotB, aujourdHui);
        double moyenneAttendue = (joursA + joursB) / 2.0;

        String tokenAdmin = creerEtLoginToken("PN-2024-00901", Role.ADMIN_NATIONAL, poste);

        String reponse = mockMvc.perform(get("/api/v1/statistiques/poste/" + poste.getId())
                        .header("Authorization", "Bearer " + tokenAdmin))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        JsonNode racine = OBJECT_MAPPER.readTree(reponse);
        assertThat(racine.get("posteId").asText()).isEqualTo(poste.getId().toString());
        assertThat(racine.get("posteNom").asText()).isEqualTo(poste.getNom());
        assertThat(racine.get("nombrePiecesEnAttente").asLong()).isEqualTo(2);
        assertThat(racine.get("ancienneteMoyenneJours").asDouble()).isEqualTo(moyenneAttendue);
        assertThat(racine.get("ancienneteMaxJours").asLong()).isEqualTo(joursB);
        assertThat(racine.get("seuilAncienneteJours").asInt()).isEqualTo(180);
        assertThat(racine.get("nombrePiecesDepassantSeuil").asLong()).isEqualTo(1);
    }

    @Test
    void consulter_sansPieceEnAttente_shouldRetournerValeursNulles() throws Exception {
        LocalDate aujourdHui = LocalDate.now();
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00902", Role.AGENT);
        creerPieceAvecStatut(poste, agentCreateur, StatutPiece.RETIREE, aujourdHui.minusDays(10));

        String tokenAdmin = creerEtLoginToken("PN-2024-00903", Role.ADMIN_NATIONAL, poste);

        String reponse = mockMvc.perform(get("/api/v1/statistiques/poste/" + poste.getId())
                        .header("Authorization", "Bearer " + tokenAdmin))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        JsonNode racine = OBJECT_MAPPER.readTree(reponse);
        assertThat(racine.get("nombrePiecesEnAttente").asLong()).isEqualTo(0);
        assertThat(racine.get("ancienneteMoyenneJours").isNull()).isTrue();
        assertThat(racine.get("ancienneteMaxJours").isNull()).isTrue();
        assertThat(racine.get("nombrePiecesDepassantSeuil").asLong()).isEqualTo(0);
    }

    @Test
    void consulter_commeAgentDuPoste_shouldRetourner200() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00904", Role.AGENT, poste);

        mockMvc.perform(get("/api/v1/statistiques/poste/" + poste.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void consulter_commeChefPosteDuPoste_shouldRetourner200() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00905", Role.CHEF_POSTE, poste);

        mockMvc.perform(get("/api/v1/statistiques/poste/" + poste.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void consulter_commeAgentDunAutrePoste_shouldRetourner403() throws Exception {
        Region region = creerRegion("Dakar");
        Poste postePiece = creerPoste(region, "Poste 1");
        Poste autrePoste = creerPoste(region, "Poste 2");
        String token = creerEtLoginToken("PN-2024-00906", Role.AGENT, autrePoste);

        mockMvc.perform(get("/api/v1/statistiques/poste/" + postePiece.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCES_REFUSE"));
    }

    @Test
    void consulter_commeAdminRegionalMemeRegion_shouldRetourner200() throws Exception {
        Region region = creerRegion("Dakar");
        Poste postePiece = creerPoste(region, "Poste 1");
        Poste posteAdmin = creerPoste(region, "Poste 2");
        String token = creerEtLoginToken("PN-2024-00907", Role.ADMIN_REGIONAL, posteAdmin);

        mockMvc.perform(get("/api/v1/statistiques/poste/" + postePiece.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void consulter_commeAdminRegionalHorsRegion_shouldRetourner403() throws Exception {
        Poste postePiece = creerPoste(creerRegion("Dakar"), "Poste 1");
        Poste posteAdmin = creerPoste(creerRegion("Thies"), "Poste 2");
        String token = creerEtLoginToken("PN-2024-00908", Role.ADMIN_REGIONAL, posteAdmin);

        mockMvc.perform(get("/api/v1/statistiques/poste/" + postePiece.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void consulter_commeAdminNational_shouldRetourner200() throws Exception {
        Poste postePiece = creerPoste(creerRegion("Dakar"), "Poste 1");
        Poste posteAdmin = creerPoste(creerRegion("Thies"), "Poste 2");
        String token = creerEtLoginToken("PN-2024-00909", Role.ADMIN_NATIONAL, posteAdmin);

        mockMvc.perform(get("/api/v1/statistiques/poste/" + postePiece.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void consulter_commeAuditeur_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00910", Role.AUDITEUR, poste);

        mockMvc.perform(get("/api/v1/statistiques/poste/" + poste.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void consulter_avecPosteInexistant_shouldRetourner404() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00911", Role.ADMIN_NATIONAL, poste);

        mockMvc.perform(get("/api/v1/statistiques/poste/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POSTE_INTROUVABLE"));
    }
}
