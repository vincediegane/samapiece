package sn.samapiece.enregistrement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
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
class PieceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String HORAIRES = "{\"lundi\":{\"ouvert\":true,\"debut\":\"08:00\",\"fin\":\"18:00\"}}";
    private static final String MOT_DE_PASSE_CLAIR = "MotDePasse123!";
    private static final String NUMERO_DOCUMENT_CLAIR = "1234567890123";

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
        // piece_sequence n'est pas exposee par un repository Spring Data (acces exclusif via
        // PieceNumeroFicheGenerator/JdbcTemplate) mais reference poste par FK : a vider avant
        // posteRepository.deleteAll(), sinon la suppression du poste echoue.
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

    private ObjectNode creerPieceRequestJson(LocalDate dateDepot) {
        ObjectNode noeud = OBJECT_MAPPER.createObjectNode();
        noeud.put("typeDocument", TypeDocument.CNI.name());
        noeud.put("nomTitulaire", "Diop");
        noeud.put("prenomTitulaire", "Awa");
        noeud.put("numeroDocument", NUMERO_DOCUMENT_CLAIR);
        noeud.put("dateNaissanceTitulaire", "1990-05-12");
        noeud.put("dateDepot", dateDepot.toString());
        noeud.put("etatDocument", "bon état");
        noeud.put("remarques", "trouvée sur la voie publique");
        return noeud;
    }

    private String creerPieceJson(LocalDate dateDepot) {
        return creerPieceRequestJson(dateDepot).toString();
    }

    @Test
    void creer_commeAgent_avecDonneesValides_shouldRetourner201() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00500", Role.AGENT, poste);

        mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceJson(LocalDate.of(2026, 9, 13))))
                .andExpect(status().isCreated());
    }

    @Test
    void creer_commeChefPoste_avecDonneesValides_shouldRetourner201() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00501", Role.CHEF_POSTE, poste);

        mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceJson(LocalDate.of(2026, 9, 13))))
                .andExpect(status().isCreated());
    }

    @Test
    void creer_commeAdminRegional_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00502", Role.ADMIN_REGIONAL, poste);

        mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceJson(LocalDate.of(2026, 9, 13))))
                .andExpect(status().isForbidden());
    }

    @Test
    void creer_commeAdminNational_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00503", Role.ADMIN_NATIONAL, poste);

        mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceJson(LocalDate.of(2026, 9, 13))))
                .andExpect(status().isForbidden());
    }

    @Test
    void creer_commeAuditeur_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00504", Role.AUDITEUR, poste);

        mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceJson(LocalDate.of(2026, 9, 13))))
                .andExpect(status().isForbidden());
    }

    @Test
    void creer_sansTypeDocument_shouldRetourner400() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00505", Role.AGENT, poste);
        ObjectNode corps = creerPieceRequestJson(LocalDate.of(2026, 9, 13));
        corps.remove("typeDocument");

        mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void creer_sansNomTitulaire_shouldRetourner400() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00506", Role.AGENT, poste);
        ObjectNode corps = creerPieceRequestJson(LocalDate.of(2026, 9, 13));
        corps.remove("nomTitulaire");

        mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void creer_sansPrenomTitulaire_shouldRetourner400() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00507", Role.AGENT, poste);
        ObjectNode corps = creerPieceRequestJson(LocalDate.of(2026, 9, 13));
        corps.remove("prenomTitulaire");

        mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void creer_sansNumeroDocument_shouldRetourner400() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00508", Role.AGENT, poste);
        ObjectNode corps = creerPieceRequestJson(LocalDate.of(2026, 9, 13));
        corps.remove("numeroDocument");

        mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void creer_sansDateDepot_shouldRetourner400() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00509", Role.AGENT, poste);
        ObjectNode corps = creerPieceRequestJson(LocalDate.of(2026, 9, 13));
        corps.remove("dateDepot");

        mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void creer_avecDonneesValides_shouldHacherNumeroDocumentCoteServeur() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00510", Role.AGENT, poste);

        String reponse = mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceJson(LocalDate.of(2026, 9, 13))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.numeroDocumentHash").doesNotExist())
                .andExpect(jsonPath("$.numeroDocumentSel").doesNotExist())
                .andExpect(jsonPath("$.numeroDocument").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String numeroDocumentMasque = OBJECT_MAPPER.readTree(reponse).get("numeroDocumentMasque").asText();
        assertThat(numeroDocumentMasque).doesNotContain(NUMERO_DOCUMENT_CLAIR);

        UUID id = UUID.fromString(OBJECT_MAPPER.readTree(reponse).get("id").asText());
        Piece piece = pieceRepository.findById(id).orElseThrow();
        assertThat(piece.getNumeroDocumentHash()).isNotEqualTo(NUMERO_DOCUMENT_CLAIR);
        assertThat(piece.getNumeroDocumentSel()).isNotBlank();
    }

    @Test
    void creer_avecDonneesValides_shouldRetournerNumeroFicheAuFormatAttendu() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00511", Role.AGENT, poste);

        mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceJson(LocalDate.of(2026, 9, 13))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.numeroFiche").value(
                        org.hamcrest.Matchers.matchesPattern("^PC-[0-9A-F]{8}-\\d{4}-\\d{5}$")));
    }

    @Test
    void creer_deuxFoisMemePoste_shouldIncrementerLaSequence() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00512", Role.AGENT, poste);

        String reponse1 = mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceJson(LocalDate.of(2026, 9, 13))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String reponse2 = mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceJson(LocalDate.of(2026, 9, 13))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String numeroFiche1 = OBJECT_MAPPER.readTree(reponse1).get("numeroFiche").asText();
        String numeroFiche2 = OBJECT_MAPPER.readTree(reponse2).get("numeroFiche").asText();
        assertThat(numeroFiche1).endsWith("-00001");
        assertThat(numeroFiche2).endsWith("-00002");
    }

    @Test
    void creer_commeAgentDePosteDonne_shouldRetournerPosteEtAgentDuContexte() throws Exception {
        Region region = creerRegion("Dakar");
        Poste poste1 = creerPoste(region, "Poste 1");
        Poste poste2 = creerPoste(region, "Poste 2");
        Agent agent1 = creerAgentActif(poste1, "PN-2024-00513", Role.AGENT);
        Agent agent2 = creerAgentActif(poste2, "PN-2024-00514", Role.AGENT);
        String token1 = login("PN-2024-00513", MOT_DE_PASSE_CLAIR);
        String token2 = login("PN-2024-00514", MOT_DE_PASSE_CLAIR);

        String reponse1 = mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceJson(LocalDate.of(2026, 9, 13))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String reponse2 = mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceJson(LocalDate.of(2026, 9, 13))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(OBJECT_MAPPER.readTree(reponse1).get("posteId").asText()).isEqualTo(poste1.getId().toString());
        assertThat(OBJECT_MAPPER.readTree(reponse1).get("agentCreateurId").asText())
                .isEqualTo(agent1.getId().toString());
        assertThat(OBJECT_MAPPER.readTree(reponse2).get("posteId").asText()).isEqualTo(poste2.getId().toString());
        assertThat(OBJECT_MAPPER.readTree(reponse2).get("agentCreateurId").asText())
                .isEqualTo(agent2.getId().toString());
    }

    @Test
    void creer_commeAgentDesactiveApresEmissionDuToken_shouldRetourner403AccesRefuse() throws Exception {
        Poste poste = creerPoste();
        String tokenAdmin = creerEtLoginToken("PN-2024-00516", Role.ADMIN_NATIONAL, poste);
        Agent agent = creerAgentActif(poste, "PN-2024-00515", Role.AGENT);
        String token = login("PN-2024-00515", MOT_DE_PASSE_CLAIR);

        mockMvc.perform(delete("/api/v1/agents/" + agent.getId())
                        .header("Authorization", "Bearer " + tokenAdmin))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceJson(LocalDate.of(2026, 9, 13))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCES_REFUSE"));
    }
}
