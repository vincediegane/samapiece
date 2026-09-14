package sn.samapiece.enregistrement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
import sn.samapiece.enregistrement.NumeroDocumentHasher;
import sn.samapiece.enregistrement.NumeroDocumentHasher.NumeroDocumentHache;
import sn.samapiece.enregistrement.Piece;
import sn.samapiece.enregistrement.PieceRepository;
import sn.samapiece.enregistrement.Retrait;
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

    private String creerPieceJson(LocalDate dateDepot, boolean confirmerMalgreDoublon) {
        ObjectNode noeud = creerPieceRequestJson(dateDepot);
        noeud.put("confirmerMalgreDoublon", confirmerMalgreDoublon);
        return noeud.toString();
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

    private Piece creerPieceEnBaseAvecHashReel(
            Poste poste,
            Agent agentCreateur,
            StatutPiece statut,
            TypeDocument typeDocument,
            String numeroDocumentClair) {
        NumeroDocumentHache hache = new NumeroDocumentHasher().hacher(numeroDocumentClair);
        Piece piece = new Piece(
                "PC-" + UUID.randomUUID(),
                poste,
                agentCreateur,
                typeDocument,
                "Diop",
                "Awa",
                hache.hash(), hache.sel(), hache.masque(),
                LocalDate.of(1990, 5, 12),
                LocalDate.of(2026, 9, 13),
                "bon état",
                "trouvée sur la voie publique");
        ReflectionTestUtils.setField(piece, "statut", statut);
        return pieceRepository.save(piece);
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
                .andExpect(jsonPath("$.creeMalgreDoublon").value(false))
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
    void creer_avecNumeroDocumentIdentiqueAUneFicheDisponible_shouldRetourner409AvecNumeroFicheCandidat()
            throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00650", Role.AGENT);
        Piece pieceExistante = creerPieceEnBaseAvecHashReel(
                poste, agentCreateur, StatutPiece.DISPONIBLE, TypeDocument.CNI, NUMERO_DOCUMENT_CLAIR);
        String token = creerEtLoginToken("PN-2024-00651", Role.AGENT, poste);

        String reponse = mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceJson(LocalDate.of(2026, 9, 13))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOUBLON_POTENTIEL"))
                .andExpect(jsonPath("$.nomTitulaire").doesNotExist())
                .andExpect(jsonPath("$.prenomTitulaire").doesNotExist())
                .andExpect(jsonPath("$.numeroDocumentMasque").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        List<String> numerosFicheCandidats = new ArrayList<>();
        OBJECT_MAPPER.readTree(reponse).get("numerosFicheCandidats")
                .forEach(n -> numerosFicheCandidats.add(n.asText()));
        assertThat(numerosFicheCandidats).containsExactly(pieceExistante.getNumeroFiche());
    }

    @Test
    void creer_avecConfirmerMalgreDoublonTrue_shouldRetourner201MalgreDoublonDetecte() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00652", Role.AGENT);
        creerPieceEnBaseAvecHashReel(
                poste, agentCreateur, StatutPiece.DISPONIBLE, TypeDocument.CNI, NUMERO_DOCUMENT_CLAIR);
        String token = creerEtLoginToken("PN-2024-00653", Role.AGENT, poste);

        String reponse = mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceJson(LocalDate.of(2026, 9, 13), true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.creeMalgreDoublon").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        UUID id = UUID.fromString(OBJECT_MAPPER.readTree(reponse).get("id").asText());
        Piece piece = pieceRepository.findById(id).orElseThrow();
        assertThat(piece.isCreeMalgreDoublon()).isTrue();
    }

    @Test
    void creer_avecPlusieursCandidatsActifsCorrespondants_shouldRetourner409AvecTousLesNumerosFicheCandidats()
            throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00654", Role.AGENT);
        Piece candidat1 = creerPieceEnBaseAvecHashReel(
                poste, agentCreateur, StatutPiece.DISPONIBLE, TypeDocument.CNI, NUMERO_DOCUMENT_CLAIR);
        Piece candidat2 = creerPieceEnBaseAvecHashReel(
                poste, agentCreateur, StatutPiece.LITIGE, TypeDocument.CNI, NUMERO_DOCUMENT_CLAIR);
        String token = creerEtLoginToken("PN-2024-00655", Role.AGENT, poste);

        String reponse = mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceJson(LocalDate.of(2026, 9, 13))))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        List<String> numerosFicheCandidats = new ArrayList<>();
        OBJECT_MAPPER.readTree(reponse).get("numerosFicheCandidats")
                .forEach(n -> numerosFicheCandidats.add(n.asText()));
        assertThat(numerosFicheCandidats).containsExactlyInAnyOrder(
                candidat1.getNumeroFiche(), candidat2.getNumeroFiche());
    }

    @Test
    void creer_avecNumeroDocumentIdentiqueMaisStatutRetiree_shouldRetourner201SansDetectionDoublon()
            throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00656", Role.AGENT);
        creerPieceEnBaseAvecHashReel(
                poste, agentCreateur, StatutPiece.RETIREE, TypeDocument.CNI, NUMERO_DOCUMENT_CLAIR);
        String token = creerEtLoginToken("PN-2024-00657", Role.AGENT, poste);

        mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceJson(LocalDate.of(2026, 9, 13))))
                .andExpect(status().isCreated());
    }

    @Test
    void creer_avecNumeroDocumentIdentiqueMaisStatutArchivee_shouldRetourner201SansDetectionDoublon()
            throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00658", Role.AGENT);
        creerPieceEnBaseAvecHashReel(
                poste, agentCreateur, StatutPiece.ARCHIVEE, TypeDocument.CNI, NUMERO_DOCUMENT_CLAIR);
        String token = creerEtLoginToken("PN-2024-00659", Role.AGENT, poste);

        mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceJson(LocalDate.of(2026, 9, 13))))
                .andExpect(status().isCreated());
    }

    @Test
    void creer_avecNumeroDocumentIdentiqueMaisStatutDetruite_shouldRetourner201SansDetectionDoublon()
            throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00660", Role.AGENT);
        creerPieceEnBaseAvecHashReel(
                poste, agentCreateur, StatutPiece.DETRUITE, TypeDocument.CNI, NUMERO_DOCUMENT_CLAIR);
        String token = creerEtLoginToken("PN-2024-00661", Role.AGENT, poste);

        mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceJson(LocalDate.of(2026, 9, 13))))
                .andExpect(status().isCreated());
    }

    @Test
    void creer_avecNumeroDocumentIdentiqueMaisTypeDocumentDifferent_shouldRetourner201SansDetectionDoublon()
            throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00662", Role.AGENT);
        creerPieceEnBaseAvecHashReel(
                poste, agentCreateur, StatutPiece.DISPONIBLE, TypeDocument.PASSEPORT, NUMERO_DOCUMENT_CLAIR);
        String token = creerEtLoginToken("PN-2024-00663", Role.AGENT, poste);

        mockMvc.perform(post("/api/v1/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerPieceJson(LocalDate.of(2026, 9, 13))))
                .andExpect(status().isCreated());
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

    @Test
    void retirer_commeAgentDuPosteDeLaPiece_avecPieceDisponible_shouldRetourner200EtPasserRetiree() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00600", Role.AGENT);
        Piece piece = creerPieceEnBase(poste, agentCreateur, StatutPiece.DISPONIBLE);
        String token = creerEtLoginToken("PN-2024-00601", Role.AGENT, poste);

        mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/retrait")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retraitRequestJson("Ndiaye Fatou", "Carte d'electeur presentee")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("RETIREE"));

        Piece pieceMiseAJour = pieceRepository.findById(piece.getId()).orElseThrow();
        assertThat(pieceMiseAJour.getStatut()).isEqualTo(StatutPiece.RETIREE);

        List<Retrait> retraits = retraitRepository.findByPieceIdOrderByCreeLeDesc(piece.getId());
        assertThat(retraits).hasSize(1);
        Retrait retrait = retraits.get(0);
        assertThat(retrait.getNomReclamant()).isEqualTo("Ndiaye Fatou");
        assertThat(retrait.getPieceJustificativePresentee()).isEqualTo("Carte d'electeur presentee");
        Agent agentValidateur = agentRepository.findByMatricule("PN-2024-00601").orElseThrow();
        assertThat(retrait.getAgentValidateur().getId()).isEqualTo(agentValidateur.getId());
    }

    @Test
    void retirer_avecNomReclamantDifferentDuNomTitulaire_shouldRetourner200QuandMemeSansOCR() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00602", Role.AGENT);
        Piece piece = creerPieceEnBase(poste, agentCreateur, StatutPiece.DISPONIBLE);
        String token = creerEtLoginToken("PN-2024-00603", Role.AGENT, poste);

        mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/retrait")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retraitRequestJson("Un nom totalement different de Diop Awa", "recu de depot")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("RETIREE"));
    }

    @Test
    void retirer_survienneSurPieceDejaRetiree_shouldRetourner409() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00604", Role.AGENT);
        Piece piece = creerPieceEnBase(poste, agentCreateur, StatutPiece.RETIREE);
        String token = creerEtLoginToken("PN-2024-00605", Role.AGENT, poste);

        String reponse = mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/retrait")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retraitRequestJson("Ndiaye Fatou", "Carte d'electeur presentee")))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(OBJECT_MAPPER.readTree(reponse).get("code").asText()).isEqualTo("TRANSITION_STATUT_INTERDITE");
    }

    @Test
    void retirer_survienneSurPieceArchivee_shouldRetourner409() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00606", Role.AGENT);
        Piece piece = creerPieceEnBase(poste, agentCreateur, StatutPiece.ARCHIVEE);
        String token = creerEtLoginToken("PN-2024-00607", Role.AGENT, poste);

        mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/retrait")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retraitRequestJson("Ndiaye Fatou", "Carte d'electeur presentee")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSITION_STATUT_INTERDITE"));
    }

    @Test
    void retirer_survienneSurPieceEnLitige_shouldRetourner409() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00608", Role.AGENT);
        Piece piece = creerPieceEnBase(poste, agentCreateur, StatutPiece.LITIGE);
        String token = creerEtLoginToken("PN-2024-00609", Role.AGENT, poste);

        mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/retrait")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retraitRequestJson("Ndiaye Fatou", "Carte d'electeur presentee")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSITION_STATUT_INTERDITE"));
    }

    @Test
    void signaler_commeAgentDuPoste_avecStatutCibleLitige_shouldRetourner200EtPasserLitige() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00610", Role.AGENT);
        Piece piece = creerPieceEnBase(poste, agentCreateur, StatutPiece.DISPONIBLE);
        String token = creerEtLoginToken("PN-2024-00611", Role.AGENT, poste);

        mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/signaler")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signalerRequestJson("LITIGE", "Deux reclamants pour la meme piece")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("LITIGE"));

        String reponseRetrait = mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/retrait")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retraitRequestJson("Ndiaye Fatou", "Carte d'electeur presentee")))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(OBJECT_MAPPER.readTree(reponseRetrait).get("code").asText())
                .isEqualTo("TRANSITION_STATUT_INTERDITE");
    }

    @Test
    void signaler_commeAgentDuPoste_avecStatutCibleSignalee_shouldRetourner200EtPasserSignalee() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00612", Role.AGENT);
        Piece piece = creerPieceEnBase(poste, agentCreateur, StatutPiece.DISPONIBLE);
        String token = creerEtLoginToken("PN-2024-00613", Role.AGENT, poste);

        mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/signaler")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signalerRequestJson("SIGNALEE", "Numero de document suspect")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("SIGNALEE"));

        mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/retrait")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retraitRequestJson("Ndiaye Fatou", "Carte d'electeur presentee")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSITION_STATUT_INTERDITE"));
    }

    @Test
    void signaler_avecStatutCibleInvalide_shouldRetourner400() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00614", Role.AGENT);
        Piece piece = creerPieceEnBase(poste, agentCreateur, StatutPiece.DISPONIBLE);
        String token = creerEtLoginToken("PN-2024-00615", Role.AGENT, poste);

        mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/signaler")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signalerRequestJson("DISPONIBLE", "motif quelconque")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void debloquer_commeChefPoste_survienneSurPieceRetiree_shouldRetourner200PuisPermettreNouveauRetrait()
            throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00616", Role.AGENT);
        Piece piece = creerPieceEnBase(poste, agentCreateur, StatutPiece.DISPONIBLE);
        String tokenAgent = creerEtLoginToken("PN-2024-00617", Role.AGENT, poste);
        String tokenChefPoste = creerEtLoginToken("PN-2024-00618", Role.CHEF_POSTE, poste);

        mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/retrait")
                        .header("Authorization", "Bearer " + tokenAgent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retraitRequestJson("Ndiaye Fatou", "Carte d'electeur presentee")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/debloquer")
                        .header("Authorization", "Bearer " + tokenChefPoste)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deblocageRequestJson("Erreur d'identification du reclamant")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("DISPONIBLE"));

        mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/retrait")
                        .header("Authorization", "Bearer " + tokenAgent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retraitRequestJson("Ndiaye Fatou", "Carte d'electeur presentee")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("RETIREE"));
    }

    @Test
    void retirer_commeAdminRegional_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00619", Role.AGENT);
        Piece piece = creerPieceEnBase(poste, agentCreateur, StatutPiece.DISPONIBLE);
        String token = creerEtLoginToken("PN-2024-00620", Role.ADMIN_REGIONAL, poste);

        mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/retrait")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retraitRequestJson("Ndiaye Fatou", "Carte d'electeur presentee")))
                .andExpect(status().isForbidden());
    }

    @Test
    void signaler_commeAuditeur_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00621", Role.AGENT);
        Piece piece = creerPieceEnBase(poste, agentCreateur, StatutPiece.DISPONIBLE);
        String token = creerEtLoginToken("PN-2024-00622", Role.AUDITEUR, poste);

        mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/signaler")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signalerRequestJson("LITIGE", "motif")))
                .andExpect(status().isForbidden());
    }

    @Test
    void debloquer_commeAgent_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00623", Role.AGENT);
        Piece piece = creerPieceEnBase(poste, agentCreateur, StatutPiece.RETIREE);
        String token = creerEtLoginToken("PN-2024-00624", Role.AGENT, poste);

        mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/debloquer")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deblocageRequestJson("motif")))
                .andExpect(status().isForbidden());
    }

    @Test
    void debloquer_commeAdminRegionalHorsRegion_shouldRetourner403() throws Exception {
        Region regionPiece = creerRegion("Dakar");
        Poste postePiece = creerPoste(regionPiece, "Commissariat Central Dakar");
        Agent agentCreateur = creerAgentActif(postePiece, "PN-2024-00625", Role.AGENT);
        Piece piece = creerPieceEnBase(postePiece, agentCreateur, StatutPiece.RETIREE);

        Poste posteAdmin = creerPoste(creerRegion("Thies"), "Commissariat Thies");
        String token = creerEtLoginToken("PN-2024-00626", Role.ADMIN_REGIONAL, posteAdmin);

        mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/debloquer")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deblocageRequestJson("motif")))
                .andExpect(status().isForbidden());
    }

    @Test
    void debloquer_commeAdminRegionalDeLaMemeRegion_shouldRetourner200() throws Exception {
        Region region = creerRegion("Dakar");
        Poste postePiece = creerPoste(region, "Commissariat Central Dakar");
        Agent agentCreateur = creerAgentActif(postePiece, "PN-2024-00627", Role.AGENT);
        Piece piece = creerPieceEnBase(postePiece, agentCreateur, StatutPiece.RETIREE);

        Poste posteAdmin = creerPoste(region, "Commissariat Secondaire Dakar");
        String token = creerEtLoginToken("PN-2024-00628", Role.ADMIN_REGIONAL, posteAdmin);

        mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/debloquer")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deblocageRequestJson("motif")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("DISPONIBLE"));
    }

    @Test
    void retirer_commeAgentDunAutrePoste_shouldRetourner403() throws Exception {
        Region region = creerRegion("Dakar");
        Poste postePiece = creerPoste(region, "Poste 1");
        Poste autrePoste = creerPoste(region, "Poste 2");
        Agent agentCreateur = creerAgentActif(postePiece, "PN-2024-00629", Role.AGENT);
        Piece piece = creerPieceEnBase(postePiece, agentCreateur, StatutPiece.DISPONIBLE);
        String token = creerEtLoginToken("PN-2024-00630", Role.AGENT, autrePoste);

        mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/retrait")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retraitRequestJson("Ndiaye Fatou", "Carte d'electeur presentee")))
                .andExpect(status().isForbidden());
    }

    @Test
    void retirer_sansNomReclamant_shouldRetourner400() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00631", Role.AGENT);
        Piece piece = creerPieceEnBase(poste, agentCreateur, StatutPiece.DISPONIBLE);
        String token = creerEtLoginToken("PN-2024-00632", Role.AGENT, poste);
        ObjectNode corps = OBJECT_MAPPER.createObjectNode();
        corps.put("pieceJustificativePresentee", "Carte d'electeur presentee");

        mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/retrait")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void retirer_sansPieceJustificativePresentee_shouldRetourner400() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00633", Role.AGENT);
        Piece piece = creerPieceEnBase(poste, agentCreateur, StatutPiece.DISPONIBLE);
        String token = creerEtLoginToken("PN-2024-00634", Role.AGENT, poste);
        ObjectNode corps = OBJECT_MAPPER.createObjectNode();
        corps.put("nomReclamant", "Ndiaye Fatou");

        mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/retrait")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void signaler_sansMotif_shouldRetourner400() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00635", Role.AGENT);
        Piece piece = creerPieceEnBase(poste, agentCreateur, StatutPiece.DISPONIBLE);
        String token = creerEtLoginToken("PN-2024-00636", Role.AGENT, poste);
        ObjectNode corps = OBJECT_MAPPER.createObjectNode();
        corps.put("statutCible", "LITIGE");

        mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/signaler")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void debloquer_sansMotif_shouldRetourner400() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00637", Role.AGENT);
        Piece piece = creerPieceEnBase(poste, agentCreateur, StatutPiece.RETIREE);
        String token = creerEtLoginToken("PN-2024-00638", Role.CHEF_POSTE, poste);
        ObjectNode corps = OBJECT_MAPPER.createObjectNode();

        mockMvc.perform(post("/api/v1/pieces/" + piece.getId() + "/debloquer")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps.toString()))
                .andExpect(status().isBadRequest());
    }
}
