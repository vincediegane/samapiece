package sn.samapiece.enregistrement.web;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
class PieceListeIntegrationTest {

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
        jdbcTemplate.update("DELETE FROM piece_sequence");
        agentRepository.deleteAll();
        posteRepository.deleteAll();
        regionRepository.deleteAll();
    }

    private Poste creerPoste(Region region, String nom) {
        return posteRepository.save(new Poste(
                region, nom, TypePoste.POLICE, "Adresse " + nom, "+221338210000", HORAIRES, 14.6928, -17.4467));
    }

    private Poste creerPoste() {
        return creerPoste(regionRepository.save(new Region("Dakar")), "Commissariat Central Dakar");
    }

    private Agent creerAgent(Poste poste, String matricule, Role role) {
        return agentRepository.save(
                new Agent(poste, matricule, "Sarr Ibra", role, passwordEncoder.encode(MOT_DE_PASSE_CLAIR)));
    }

    private String token(String matricule, Role role, Poste poste) throws Exception {
        creerAgent(poste, matricule, role);
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

    private Piece creerPiece(
            Poste poste, Agent createur, String numeroFiche, StatutPiece statut, LocalDate dateDepot) {
        Piece piece = new Piece(
                numeroFiche, poste, createur, TypeDocument.CNI, "Diop", "Awa",
                "hash", "sel", "masque", LocalDate.of(1990, 5, 12), dateDepot,
                "bon état", "remarque confidentielle");
        ReflectionTestUtils.setField(piece, "statut", statut);
        return pieceRepository.save(piece);
    }

    private String lire(String url, String token) throws Exception {
        return mockMvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void lister_commeAgent_shouldRetournerSeulementPiecesDuPosteDefautEnStock() throws Exception {
        Region region = regionRepository.save(new Region("Dakar"));
        Poste poste = creerPoste(region, "Poste 1");
        Poste autre = creerPoste(region, "Poste 2");
        Agent createur = creerAgent(poste, "PN-2024-01000", Role.AGENT);
        LocalDate auj = LocalDate.now();
        creerPiece(poste, createur, "PC-A", StatutPiece.DISPONIBLE, auj.minusDays(3));
        creerPiece(poste, createur, "PC-B", StatutPiece.RECLAMEE, auj.minusDays(2));
        creerPiece(poste, createur, "PC-C", StatutPiece.RETIREE, auj.minusDays(1));
        creerPiece(autre, createur, "PC-D", StatutPiece.DISPONIBLE, auj.minusDays(1));
        String token = token("PN-2024-01001", Role.AGENT, poste);

        JsonNode racine = OBJECT_MAPPER.readTree(lire("/api/v1/pieces", token));

        assertThat(racine.get("totalElements").asLong()).isEqualTo(2);
        assertThat(racine.get("content")).hasSize(2);
        assertThat(racine.get("content").get(0).get("numeroFiche").asText()).isEqualTo("PC-A");
        assertThat(racine.get("content").get(1).get("statut").asText()).isEqualTo("RECLAMEE");
    }

    @Test
    void lister_avecStatutRetiree_shouldFiltrer() throws Exception {
        Poste poste = creerPoste();
        Agent createur = creerAgent(poste, "PN-2024-01002", Role.AGENT);
        LocalDate auj = LocalDate.now();
        creerPiece(poste, createur, "PC-A", StatutPiece.DISPONIBLE, auj.minusDays(3));
        creerPiece(poste, createur, "PC-C", StatutPiece.RETIREE, auj.minusDays(1));
        String token = token("PN-2024-01003", Role.AGENT, poste);

        JsonNode racine = OBJECT_MAPPER.readTree(lire("/api/v1/pieces?statut=RETIREE", token));

        assertThat(racine.get("totalElements").asLong()).isEqualTo(1);
        assertThat(racine.get("content").get(0).get("numeroFiche").asText()).isEqualTo("PC-C");
        assertThat(racine.get("content").get(0).get("depasseSeuil").asBoolean()).isFalse();
    }

    @Test
    void lister_pagination_shouldRespecterPageEtSize() throws Exception {
        Poste poste = creerPoste();
        Agent createur = creerAgent(poste, "PN-2024-01004", Role.AGENT);
        LocalDate auj = LocalDate.now();
        creerPiece(poste, createur, "PC-1", StatutPiece.DISPONIBLE, auj.minusDays(3));
        creerPiece(poste, createur, "PC-2", StatutPiece.DISPONIBLE, auj.minusDays(2));
        creerPiece(poste, createur, "PC-3", StatutPiece.DISPONIBLE, auj.minusDays(1));
        String token = token("PN-2024-01005", Role.AGENT, poste);

        JsonNode page0 = OBJECT_MAPPER.readTree(lire("/api/v1/pieces?size=2&page=0", token));
        JsonNode page1 = OBJECT_MAPPER.readTree(lire("/api/v1/pieces?size=2&page=1", token));

        assertThat(page0.get("totalPages").asInt()).isEqualTo(2);
        assertThat(page0.get("content")).hasSize(2);
        assertThat(page1.get("content")).hasSize(1);
        assertThat(page1.get("number").asInt()).isEqualTo(1);
    }

    @Test
    void lister_sizeSuperieurA100_shouldEtrePlafonne() throws Exception {
        Poste poste = creerPoste();
        String token = token("PN-2024-01006", Role.AGENT, poste);

        JsonNode racine = OBJECT_MAPPER.readTree(lire("/api/v1/pieces?size=500", token));

        assertThat(racine.get("size").asInt()).isEqualTo(100);
    }

    @Test
    void lister_ordreParDateDepotPuisNumeroFiche() throws Exception {
        Poste poste = creerPoste();
        Agent createur = creerAgent(poste, "PN-2024-01007", Role.AGENT);
        LocalDate auj = LocalDate.now();
        creerPiece(poste, createur, "PC-Z", StatutPiece.DISPONIBLE, auj.minusDays(10));
        creerPiece(poste, createur, "PC-B", StatutPiece.DISPONIBLE, auj.minusDays(5));
        creerPiece(poste, createur, "PC-A", StatutPiece.DISPONIBLE, auj.minusDays(5));
        String token = token("PN-2024-01008", Role.AGENT, poste);

        JsonNode racine = OBJECT_MAPPER.readTree(lire("/api/v1/pieces?sort=numeroFiche,desc", token));

        assertThat(racine.get("content").get(0).get("numeroFiche").asText()).isEqualTo("PC-Z");
        assertThat(racine.get("content").get(1).get("numeroFiche").asText()).isEqualTo("PC-A");
        assertThat(racine.get("content").get(2).get("numeroFiche").asText()).isEqualTo("PC-B");
    }

    @Test
    void lister_statutInvalide_shouldRetourner400() throws Exception {
        Poste poste = creerPoste();
        String token = token("PN-2024-01009", Role.AGENT, poste);

        for (String valeur : new String[] {"NIMPORTEQUOI", "disponible"}) {
            mockMvc.perform(get("/api/v1/pieces?statut=" + valeur).header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void lister_commeChefPoste_shouldRetourner200() throws Exception {
        Poste poste = creerPoste();
        String token = token("PN-2024-01010", Role.CHEF_POSTE, poste);

        lire("/api/v1/pieces", token);
    }

    @Test
    void lister_posteIdAutrePoste_shouldRetourner403() throws Exception {
        Region region = regionRepository.save(new Region("Dakar"));
        Poste poste = creerPoste(region, "Poste 1");
        Poste autre = creerPoste(region, "Poste 2");
        String token = token("PN-2024-01011", Role.AGENT, poste);

        mockMvc.perform(get("/api/v1/pieces?posteId=" + autre.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCES_REFUSE"));
    }

    @Test
    void lister_posteIdInconnu_shouldRetourner404() throws Exception {
        Poste poste = creerPoste();
        String token = token("PN-2024-01012", Role.AGENT, poste);

        mockMvc.perform(get("/api/v1/pieces?posteId=" + UUID.randomUUID()).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POSTE_INTROUVABLE"));
    }

    @Test
    void lister_posteIdEgalPostePropre_shouldRetourner200() throws Exception {
        Poste poste = creerPoste();
        String token = token("PN-2024-01013", Role.AGENT, poste);

        lire("/api/v1/pieces?posteId=" + poste.getId(), token);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN_REGIONAL", "ADMIN_NATIONAL", "AUDITEUR"})
    void lister_commeRoleNonAutorise_shouldRetourner403(Role role) throws Exception {
        Poste poste = creerPoste();
        String token = token("PN-2024-01014", role, poste);

        mockMvc.perform(get("/api/v1/pieces").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void lister_sansJeton_shouldRetourner401() throws Exception {
        mockMvc.perform(get("/api/v1/pieces")).andExpect(status().isUnauthorized());
    }

    @Test
    void lister_reponse_shouldNePasContenirDonneesPersonnelles() throws Exception {
        Poste poste = creerPoste();
        Agent createur = creerAgent(poste, "PN-2024-01015", Role.AGENT);
        creerPiece(poste, createur, "PC-A", StatutPiece.DISPONIBLE, LocalDate.now().minusDays(3));
        String token = token("PN-2024-01016", Role.AGENT, poste);

        String brut = lire("/api/v1/pieces", token);

        assertThat(brut).contains("numeroFiche");
        for (String interdit : new String[] {
            "nomTitulaire", "prenomTitulaire", "numeroDocumentMasque", "dateNaissanceTitulaire",
            "remarques", "etatDocument", "agentCreateurId", "posteId", "Diop", "Awa", "remarque confidentielle"
        }) {
            assertThat(brut).doesNotContain(interdit);
        }
    }

    @Test
    void lister_totalElementsEgalNombrePiecesEnAttenteDuDashboard() throws Exception {
        Poste poste = creerPoste();
        Agent createur = creerAgent(poste, "PN-2024-01017", Role.AGENT);
        LocalDate auj = LocalDate.now();
        LocalDate depotA = auj.minusDays(30);
        creerPiece(poste, createur, "PC-A", StatutPiece.DISPONIBLE, depotA);
        creerPiece(poste, createur, "PC-B", StatutPiece.RECLAMEE, auj.minusDays(200));
        creerPiece(poste, createur, "PC-C", StatutPiece.RETIREE, auj.minusDays(5));
        String token = token("PN-2024-01018", Role.AGENT, poste);

        JsonNode liste = OBJECT_MAPPER.readTree(lire("/api/v1/pieces", token));
        JsonNode stats = OBJECT_MAPPER.readTree(lire("/api/v1/statistiques/poste/" + poste.getId(), token));

        assertThat(liste.get("totalElements").asLong()).isEqualTo(stats.get("nombrePiecesEnAttente").asLong());
        long depassant = 0;
        for (JsonNode item : liste.get("content")) {
            if (item.get("depasseSeuil").asBoolean()) {
                depassant++;
            }
            if (item.get("numeroFiche").asText().equals("PC-A")) {
                assertThat(item.get("ancienneteJours").asLong()).isEqualTo(ChronoUnit.DAYS.between(depotA, auj));
            }
        }
        assertThat(depassant).isEqualTo(stats.get("nombrePiecesDepassantSeuil").asLong());
    }
}
