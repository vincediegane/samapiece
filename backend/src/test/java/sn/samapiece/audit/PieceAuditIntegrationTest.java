package sn.samapiece.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
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
import sn.samapiece.enregistrement.RetraitRepository;
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
class PieceAuditIntegrationTest {

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
    private EvenementAuditRepository evenementAuditRepository;

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

    private Piece creerPieceEnBase(Poste poste, Agent agentCreateur, StatutPiece statut) {
        Piece piece = new Piece(
                "PC-" + UUID.randomUUID(),
                poste,
                agentCreateur,
                TypeDocument.CNI,
                "Diop",
                "Awa",
                "hash", "sel", "masque",
                LocalDate.of(1990, 5, 12),
                LocalDate.of(2026, 9, 13),
                "bon état",
                "trouvée sur la voie publique");
        ReflectionTestUtils.setField(piece, "statut", statut);
        return pieceRepository.save(piece);
    }

    private String creerPieceJson(LocalDate dateDepot) {
        ObjectNode noeud = OBJECT_MAPPER.createObjectNode();
        noeud.put("typeDocument", TypeDocument.CNI.name());
        noeud.put("nomTitulaire", "Diop");
        noeud.put("prenomTitulaire", "Awa");
        noeud.put("numeroDocument", "1234567890123");
        noeud.put("dateNaissanceTitulaire", "1990-05-12");
        noeud.put("dateDepot", dateDepot.toString());
        noeud.put("etatDocument", "bon état");
        noeud.put("remarques", "trouvée sur la voie publique");
        return noeud.toString();
    }

    private String retraitRequestJson(String nomReclamant, String pieceJustificativePresentee) {
        ObjectNode noeud = OBJECT_MAPPER.createObjectNode();
        noeud.put("nomReclamant", nomReclamant);
        noeud.put("pieceJustificativePresentee", pieceJustificativePresentee);
        return noeud.toString();
    }

    private String signalerRequestJson(String statutCible, String motif) {
        ObjectNode noeud = OBJECT_MAPPER.createObjectNode();
        noeud.put("statutCible", statutCible);
        noeud.put("motif", motif);
        return noeud.toString();
    }

    private String deblocageRequestJson(String motif) {
        ObjectNode noeud = OBJECT_MAPPER.createObjectNode();
        noeud.put("motif", motif);
        return noeud.toString();
    }

    private List<EvenementAudit> evenementsPourAction(String action) {
        return evenementAuditRepository.findAll(PageRequest.of(0, 50)).stream()
                .filter(e -> e.getAction().equals(action))
                .toList();
    }

    /**
     * PostgreSQL reformate canoniquement une colonne {@code jsonb} a la lecture (espace apres
     * chaque ":"), donc une comparaison de sous-chaine brute sur {@link EvenementAudit#getDetails()}
     * est fragile : on parse le JSON et on lit le champ pour comparer sa valeur, pas sa forme textuelle.
     */
    private String champDetails(String detailsJson, String champ) throws Exception {
        return OBJECT_MAPPER.readTree(detailsJson).get(champ).asText();
    }

    @Test
    void consulter_commeAgentDuPoste_shouldCreerEvenementAuditPieceConsultee() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00700", Role.AGENT);
        Piece piece = creerPieceEnBase(poste, agentCreateur, StatutPiece.DISPONIBLE);
        String token = creerEtLoginToken("PN-2024-00701", Role.AGENT, poste);
        Agent appelant = agentRepository.findByMatricule("PN-2024-00701").orElseThrow();

        mockMvc.perform(get("/api/v1/pieces/" + piece.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(piece.getId().toString()));

        List<EvenementAudit> evenements = evenementsPourAction("PIECE_CONSULTEE");
        assertThat(evenements).hasSize(1);
        EvenementAudit evenement = evenements.get(0);
        assertThat(evenement.getActeurId()).isEqualTo(appelant.getId());
        assertThat(evenement.getTypeActeur()).isEqualTo("AGENT");
        assertThat(evenement.getEntiteCible()).isEqualTo("PIECE");
        assertThat(evenement.getEntiteCibleId()).isEqualTo(piece.getId());
        assertThat(evenement.getAdresseIp()).isNotNull();
        assertThat(evenement.getHorodatage()).isNotNull();
        assertThat(champDetails(evenement.getDetails(), "resultat")).isEqualTo("SUCCES");
    }

    @Test
    void consulter_commeAgentDunAutrePoste_shouldRetourner403() throws Exception {
        Region region = creerRegion("Dakar");
        Poste postePiece = creerPoste(region, "Poste 1");
        Poste autrePoste = creerPoste(region, "Poste 2");
        Agent agentCreateur = creerAgentActif(postePiece, "PN-2024-00702", Role.AGENT);
        Piece piece = creerPieceEnBase(postePiece, agentCreateur, StatutPiece.DISPONIBLE);
        String token = creerEtLoginToken("PN-2024-00703", Role.AGENT, autrePoste);

        mockMvc.perform(get("/api/v1/pieces/" + piece.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        List<EvenementAudit> evenements = evenementsPourAction("PIECE_CONSULTEE");
        assertThat(evenements).hasSize(1);
        assertThat(champDetails(evenements.get(0).getDetails(), "resultat")).isEqualTo("ECHEC");
    }

    @Test
    void consulter_commeAdminRegional_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00704", Role.AGENT);
        Piece piece = creerPieceEnBase(poste, agentCreateur, StatutPiece.DISPONIBLE);
        String token = creerEtLoginToken("PN-2024-00705", Role.ADMIN_REGIONAL, poste);

        mockMvc.perform(get("/api/v1/pieces/" + piece.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void consulter_commeAdminNational_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00706", Role.AGENT);
        Piece piece = creerPieceEnBase(poste, agentCreateur, StatutPiece.DISPONIBLE);
        String token = creerEtLoginToken("PN-2024-00707", Role.ADMIN_NATIONAL, poste);

        mockMvc.perform(get("/api/v1/pieces/" + piece.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void consulter_commeAuditeur_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00708", Role.AGENT);
        Piece piece = creerPieceEnBase(poste, agentCreateur, StatutPiece.DISPONIBLE);
        String token = creerEtLoginToken("PN-2024-00709", Role.AUDITEUR, poste);

        mockMvc.perform(get("/api/v1/pieces/" + piece.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void creer_avecDonneesValides_shouldCreerEvenementAuditPieceCreee() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00710", Role.AGENT, poste);

        String reponse = mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceJson(LocalDate.of(2026, 9, 13))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        UUID idPiece = UUID.fromString(OBJECT_MAPPER.readTree(reponse).get("id").asText());

        List<EvenementAudit> evenements = evenementsPourAction("PIECE_CREEE");
        assertThat(evenements).hasSize(1);
        assertThat(evenements.get(0).getEntiteCibleId()).isEqualTo(idPiece);
        assertThat(champDetails(evenements.get(0).getDetails(), "resultat")).isEqualTo("SUCCES");
    }

    @Test
    void retirer_commeAgentDuPoste_shouldCreerEvenementAuditPieceRetiree() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00711", Role.AGENT);
        Piece piece = creerPieceEnBase(poste, agentCreateur, StatutPiece.DISPONIBLE);
        String token = creerEtLoginToken("PN-2024-00712", Role.AGENT, poste);

        mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/retrait")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retraitRequestJson("Ndiaye Fatou", "Carte d'electeur presentee")))
                .andExpect(status().isOk());

        List<EvenementAudit> evenements = evenementsPourAction("PIECE_RETIREE");
        assertThat(evenements).hasSize(1);
        assertThat(evenements.get(0).getEntiteCibleId()).isEqualTo(piece.getId());
        assertThat(champDetails(evenements.get(0).getDetails(), "resultat")).isEqualTo("SUCCES");
    }

    @Test
    void retirer_commeAgentDunAutrePoste_shouldRetourner403EtCreerEvenementAuditEchec() throws Exception {
        Region region = creerRegion("Dakar");
        Poste postePiece = creerPoste(region, "Poste 1");
        Poste autrePoste = creerPoste(region, "Poste 2");
        Agent agentCreateur = creerAgentActif(postePiece, "PN-2024-00713", Role.AGENT);
        Piece piece = creerPieceEnBase(postePiece, agentCreateur, StatutPiece.DISPONIBLE);
        String token = creerEtLoginToken("PN-2024-00714", Role.AGENT, autrePoste);

        mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/retrait")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retraitRequestJson("Ndiaye Fatou", "Carte d'electeur presentee")))
                .andExpect(status().isForbidden());

        List<EvenementAudit> evenements = evenementsPourAction("PIECE_RETIREE");
        assertThat(evenements).hasSize(1);
        EvenementAudit evenement = evenements.get(0);
        assertThat(evenement.getEntiteCibleId()).isEqualTo(piece.getId());
        assertThat(champDetails(evenement.getDetails(), "resultat")).isEqualTo("ECHEC");
        assertThat(champDetails(evenement.getDetails(), "exception")).isEqualTo("AccesRefuseException");
    }

    @Test
    void signaler_commeAgentDuPoste_shouldCreerEvenementAuditPieceSignalee() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00715", Role.AGENT);
        Piece piece = creerPieceEnBase(poste, agentCreateur, StatutPiece.DISPONIBLE);
        String token = creerEtLoginToken("PN-2024-00716", Role.AGENT, poste);

        mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/signaler")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signalerRequestJson("LITIGE", "Deux reclamants pour la meme piece")))
                .andExpect(status().isOk());

        List<EvenementAudit> evenements = evenementsPourAction("PIECE_SIGNALEE");
        assertThat(evenements).hasSize(1);
        assertThat(evenements.get(0).getEntiteCibleId()).isEqualTo(piece.getId());
        assertThat(champDetails(evenements.get(0).getDetails(), "resultat")).isEqualTo("SUCCES");
    }

    @Test
    void debloquer_commeChefPoste_shouldCreerEvenementAuditPieceDebloquee() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00717", Role.AGENT);
        Piece piece = creerPieceEnBase(poste, agentCreateur, StatutPiece.RETIREE);
        String token = creerEtLoginToken("PN-2024-00718", Role.CHEF_POSTE, poste);

        mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/debloquer")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deblocageRequestJson("Erreur d'identification du reclamant")))
                .andExpect(status().isOk());

        List<EvenementAudit> evenements = evenementsPourAction("PIECE_DEBLOQUEE");
        assertThat(evenements).hasSize(1);
        assertThat(evenements.get(0).getEntiteCibleId()).isEqualTo(piece.getId());
        assertThat(champDetails(evenements.get(0).getDetails(), "resultat")).isEqualTo("SUCCES");
    }
}
