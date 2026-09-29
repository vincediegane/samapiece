package sn.samapiece.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
class StatistiquesConsolideesIntegrationTest {

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
        // piece_sequence reference poste par FK : a vider avant posteRepository.deleteAll().
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

    private Agent creerAgentActif(Poste poste, String matricule, Role role) {
        return agentRepository.save(
                new Agent(poste, matricule, "Diop Awa", role, passwordEncoder.encode(MOT_DE_PASSE_CLAIR)));
    }

    private String creerEtLoginToken(String matricule, Role role, Poste poste) throws Exception {
        creerAgentActif(poste, matricule, role);
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

    private void creerPiece(Poste poste, Agent agentCreateur, StatutPiece statut, int ageJours) {
        Piece piece = new Piece(
                "PC-" + UUID.randomUUID(),
                poste,
                agentCreateur,
                TypeDocument.CNI,
                "Diop",
                "Awa",
                "hash", "sel", "masque",
                LocalDate.of(1990, 5, 12),
                LocalDate.now().minusDays(ageJours),
                "bon état",
                "trouvée sur la voie publique");
        ReflectionTestUtils.setField(piece, "statut", statut);
        pieceRepository.save(piece);
    }

    private Agent agentCreateur(Poste poste, String matricule) {
        return creerAgentActif(poste, matricule, Role.AGENT);
    }

    private JsonNode appeler(String chemin, String token) throws Exception {
        String reponse = mockMvc.perform(get(chemin).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return OBJECT_MAPPER.readTree(reponse);
    }

    private JsonNode lignePoste(JsonNode racine, UUID posteId) {
        for (JsonNode ligne : racine.get("postes")) {
            if (ligne.get("posteId").asText().equals(posteId.toString())) {
                return ligne;
            }
        }
        throw new AssertionError("Poste absent de la reponse : " + posteId);
    }

    @Test
    void regionale_commeAdminRegional_shouldRetournerSeulementSaRegion() throws Exception {
        Region dakar = creerRegion("Dakar");
        Region thies = creerRegion("Thies");
        Poste central = creerPoste(dakar, "Commissariat Central");
        Poste pikine = creerPoste(dakar, "Commissariat Pikine");
        Poste thiesPoste = creerPoste(thies, "Commissariat Thies");

        Agent agentCentral = agentCreateur(central, "PN-2024-01001");
        Agent agentPikine = agentCreateur(pikine, "PN-2024-01002");
        Agent agentThies = agentCreateur(thiesPoste, "PN-2024-01003");

        creerPiece(central, agentCentral, StatutPiece.DISPONIBLE, 200);
        creerPiece(central, agentCentral, StatutPiece.RECLAMEE, 30);
        creerPiece(pikine, agentPikine, StatutPiece.DISPONIBLE, 10);
        creerPiece(thiesPoste, agentThies, StatutPiece.DISPONIBLE, 500);

        String token = creerEtLoginToken("PN-2024-01004", Role.ADMIN_REGIONAL, pikine);

        JsonNode racine = appeler("/api/v1/statistiques/regionale", token);

        assertThat(racine.get("portee").asText()).isEqualTo("REGIONALE");
        assertThat(racine.get("regionId").asText()).isEqualTo(dakar.getId().toString());
        assertThat(racine.get("regionNom").asText()).isEqualTo("Dakar");
        assertThat(racine.get("seuilAncienneteJours").asInt()).isEqualTo(180);
        assertThat(racine.get("postes")).hasSize(2);

        JsonNode totaux = racine.get("totaux");
        assertThat(totaux.get("nombrePiecesEnAttente").asLong()).isEqualTo(3);
        assertThat(totaux.get("ancienneteMoyenneJours").asDouble()).isEqualTo(240 / 3.0);
        assertThat(totaux.get("ancienneteMaxJours").asLong()).isEqualTo(200);
        assertThat(totaux.get("nombrePiecesDepassantSeuil").asLong()).isEqualTo(1);
        assertThat(totaux.get("nombrePostes").asInt()).isEqualTo(2);
        assertThat(totaux.get("nombrePostesEnDepassement").asInt()).isEqualTo(1);

        JsonNode ligneCentral = lignePoste(racine, central.getId());
        assertThat(ligneCentral.get("posteNom").asText()).isEqualTo("Commissariat Central");
        assertThat(ligneCentral.get("regionNom").asText()).isEqualTo("Dakar");
        assertThat(ligneCentral.get("nombrePiecesEnAttente").asLong()).isEqualTo(2);
    }

    @Test
    void regionale_regionSansPiece_shouldRetournerTotauxNuls() throws Exception {
        Region dakar = creerRegion("Dakar");
        Poste central = creerPoste(dakar, "Commissariat Central");
        creerPoste(dakar, "Commissariat Pikine");
        String token = creerEtLoginToken("PN-2024-01010", Role.ADMIN_REGIONAL, central);

        JsonNode racine = appeler("/api/v1/statistiques/regionale", token);

        JsonNode totaux = racine.get("totaux");
        assertThat(totaux.get("nombrePiecesEnAttente").asLong()).isEqualTo(0);
        assertThat(totaux.get("ancienneteMoyenneJours").isNull()).isTrue();
        assertThat(totaux.get("ancienneteMaxJours").isNull()).isTrue();
        assertThat(totaux.get("nombrePiecesDepassantSeuil").asLong()).isEqualTo(0);
        assertThat(totaux.get("nombrePostes").asInt()).isEqualTo(2);
        assertThat(totaux.get("nombrePostesEnDepassement").asInt()).isEqualTo(0);
        assertThat(racine.get("postes")).hasSize(2);
        for (JsonNode ligne : racine.get("postes")) {
            assertThat(ligne.get("nombrePiecesEnAttente").asLong()).isEqualTo(0);
            assertThat(ligne.get("ancienneteMoyenneJours").isNull()).isTrue();
            assertThat(ligne.get("ancienneteMaxJours").isNull()).isTrue();
        }
    }

    @Test
    void nationale_commeAdminNational_shouldRetournerTousLesPostes() throws Exception {
        Region dakar = creerRegion("Dakar");
        Region thies = creerRegion("Thies");
        Poste central = creerPoste(dakar, "Commissariat Central");
        Poste thiesPoste = creerPoste(thies, "Commissariat Thies");
        Agent agentCentral = agentCreateur(central, "PN-2024-01020");
        Agent agentThies = agentCreateur(thiesPoste, "PN-2024-01021");
        creerPiece(central, agentCentral, StatutPiece.DISPONIBLE, 20);
        creerPiece(thiesPoste, agentThies, StatutPiece.DISPONIBLE, 40);
        String token = creerEtLoginToken("PN-2024-01022", Role.ADMIN_NATIONAL, central);

        JsonNode racine = appeler("/api/v1/statistiques/nationale", token);

        assertThat(racine.get("portee").asText()).isEqualTo("NATIONALE");
        assertThat(racine.get("regionId").isNull()).isTrue();
        assertThat(racine.get("regionNom").isNull()).isTrue();
        assertThat(racine.get("postes")).hasSize(2);
        assertThat(racine.get("totaux").get("nombrePiecesEnAttente").asLong()).isEqualTo(2);
        assertThat(racine.get("totaux").get("ancienneteMoyenneJours").asDouble()).isEqualTo(30.0);
        assertThat(racine.get("totaux").get("ancienneteMaxJours").asLong()).isEqualTo(40);
        assertThat(lignePoste(racine, central.getId()).get("regionNom").asText()).isEqualTo("Dakar");
        assertThat(lignePoste(racine, thiesPoste.getId()).get("regionNom").asText()).isEqualTo("Thies");
    }

    @Test
    void nationale_shouldTrierParDepassantsPuisNom() throws Exception {
        Region dakar = creerRegion("Dakar");
        Poste alpha = creerPoste(dakar, "Alpha");
        Poste bravo = creerPoste(dakar, "Bravo");
        Poste charlie = creerPoste(dakar, "Charlie");
        Agent agentCharlie = agentCreateur(charlie, "PN-2024-01030");
        creerPiece(charlie, agentCharlie, StatutPiece.DISPONIBLE, 250);
        creerPiece(charlie, agentCharlie, StatutPiece.DISPONIBLE, 260);
        Agent agentBravo = agentCreateur(bravo, "PN-2024-01031");
        creerPiece(bravo, agentBravo, StatutPiece.DISPONIBLE, 190);
        String token = creerEtLoginToken("PN-2024-01032", Role.ADMIN_NATIONAL, alpha);

        JsonNode racine = appeler("/api/v1/statistiques/nationale", token);

        assertThat(racine.get("postes").get(0).get("posteNom").asText()).isEqualTo("Charlie");
        assertThat(racine.get("postes").get(1).get("posteNom").asText()).isEqualTo("Bravo");
        assertThat(racine.get("postes").get(2).get("posteNom").asText()).isEqualTo("Alpha");
    }

    @Test
    void regionale_commeAgent_shouldRetourner403() throws Exception {
        assertInterdit("/api/v1/statistiques/regionale", Role.AGENT, "PN-2024-01040");
    }

    @Test
    void regionale_commeChefPoste_shouldRetourner403() throws Exception {
        assertInterdit("/api/v1/statistiques/regionale", Role.CHEF_POSTE, "PN-2024-01041");
    }

    @Test
    void regionale_commeAuditeur_shouldRetourner403() throws Exception {
        assertInterdit("/api/v1/statistiques/regionale", Role.AUDITEUR, "PN-2024-01042");
    }

    @Test
    void regionale_commeAdminNational_shouldRetourner403() throws Exception {
        assertInterdit("/api/v1/statistiques/regionale", Role.ADMIN_NATIONAL, "PN-2024-01043");
    }

    @Test
    void nationale_commeAgent_shouldRetourner403() throws Exception {
        assertInterdit("/api/v1/statistiques/nationale", Role.AGENT, "PN-2024-01044");
    }

    @Test
    void nationale_commeChefPoste_shouldRetourner403() throws Exception {
        assertInterdit("/api/v1/statistiques/nationale", Role.CHEF_POSTE, "PN-2024-01045");
    }

    @Test
    void nationale_commeAuditeur_shouldRetourner403() throws Exception {
        assertInterdit("/api/v1/statistiques/nationale", Role.AUDITEUR, "PN-2024-01046");
    }

    @Test
    void nationale_commeAdminRegional_shouldRetourner403() throws Exception {
        assertInterdit("/api/v1/statistiques/nationale", Role.ADMIN_REGIONAL, "PN-2024-01047");
    }

    @Test
    void regionale_et_nationale_sansJeton_shouldRetourner401() throws Exception {
        mockMvc.perform(get("/api/v1/statistiques/regionale")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/statistiques/nationale")).andExpect(status().isUnauthorized());
    }

    private void assertInterdit(String chemin, Role role, String matricule) throws Exception {
        Poste poste = creerPoste(creerRegion("Dakar"), "Commissariat Central");
        String token = creerEtLoginToken(matricule, role, poste);
        mockMvc.perform(get(chemin).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void consolide_shouldEtreCoherentAvecEndpointPoste() throws Exception {
        Region dakar = creerRegion("Dakar");
        Region thies = creerRegion("Thies");
        Poste central = creerPoste(dakar, "Commissariat Central");
        Poste pikine = creerPoste(dakar, "Commissariat Pikine");
        Poste thiesPoste = creerPoste(thies, "Commissariat Thies");
        creerPoste(thies, "Poste vide");

        for (Poste poste : new Poste[] {central, pikine, thiesPoste}) {
            Agent createur = agentCreateur(poste, "PN-2024-0105" + (poste == central ? 0 : poste == pikine ? 1 : 2));
            creerPiece(poste, createur, StatutPiece.DISPONIBLE, 5);
            creerPiece(poste, createur, StatutPiece.RECLAMEE, 30);
            creerPiece(poste, createur, StatutPiece.RETIREE, 500);
        }
        Agent createurCentral = agentRepository.findByMatricule("PN-2024-01050").orElseThrow();
        creerPiece(central, createurCentral, StatutPiece.DISPONIBLE, 200);
        creerPiece(central, createurCentral, StatutPiece.DISPONIBLE, 180);
        creerPiece(pikine, agentRepository.findByMatricule("PN-2024-01051").orElseThrow(),
                StatutPiece.DISPONIBLE, 181);

        String token = creerEtLoginToken("PN-2024-01053", Role.ADMIN_NATIONAL, central);

        JsonNode consolide = appeler("/api/v1/statistiques/nationale", token);
        assertThat(consolide.get("postes")).hasSize(4);

        for (Poste poste : new Poste[] {central, pikine, thiesPoste}) {
            JsonNode unitaire = appeler("/api/v1/statistiques/poste/" + poste.getId(), token);
            JsonNode ligne = lignePoste(consolide, poste.getId());
            assertThat(ligne.get("nombrePiecesEnAttente").asLong())
                    .isEqualTo(unitaire.get("nombrePiecesEnAttente").asLong());
            assertThat(ligne.get("ancienneteMoyenneJours").asDouble())
                    .isEqualTo(unitaire.get("ancienneteMoyenneJours").asDouble());
            assertThat(ligne.get("ancienneteMaxJours").asLong())
                    .isEqualTo(unitaire.get("ancienneteMaxJours").asLong());
            assertThat(ligne.get("nombrePiecesDepassantSeuil").asLong())
                    .isEqualTo(unitaire.get("nombrePiecesDepassantSeuil").asLong());
            assertThat(consolide.get("seuilAncienneteJours").asInt())
                    .isEqualTo(unitaire.get("seuilAncienneteJours").asInt());
        }
        assertThat(lignePoste(consolide, central.getId()).get("nombrePiecesDepassantSeuil").asLong()).isEqualTo(1);
        assertThat(lignePoste(consolide, central.getId()).get("nombrePiecesEnAttente").asLong()).isEqualTo(4);
    }
}
