package sn.samapiece.recherche.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import sn.samapiece.enregistrement.NumeroDocumentHasher;
import sn.samapiece.enregistrement.NumeroDocumentHasher.NumeroDocumentHache;
import sn.samapiece.enregistrement.Piece;
import sn.samapiece.enregistrement.PieceRepository;
import sn.samapiece.enregistrement.TypeDocument;
import sn.samapiece.iam.Agent;
import sn.samapiece.iam.AgentRepository;
import sn.samapiece.iam.Role;
import sn.samapiece.referentiel.Poste;
import sn.samapiece.referentiel.PosteRepository;
import sn.samapiece.referentiel.Region;
import sn.samapiece.referentiel.RegionRepository;
import sn.samapiece.referentiel.TypePoste;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RecherchePubliqueMeilisearchIndisponibleIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void proprietesMeilisearchInjoignable(DynamicPropertyRegistry registry) {
        registry.add("samapiece.meilisearch.host", () -> "http://127.0.0.1:1");
        registry.add("samapiece.meilisearch.api-key", () -> "peu-importe");
        registry.add("samapiece.meilisearch.index-pieces", () -> "pieces-test");
    }

    private static final String HORAIRES = "{\"lundi\":{\"ouvert\":true,\"debut\":\"08:00\",\"fin\":\"18:00\"}}";
    private static final String NUMERO_DOCUMENT_REEL = "1234567890123";

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
    private NumeroDocumentHasher hasher;

    @BeforeEach
    void nettoyer() {
        pieceRepository.deleteAll();
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

    private Agent creerAgent(Poste poste, String matricule) {
        return agentRepository.save(new Agent(poste, matricule, "Diop Awa", Role.AGENT, "hash-peu-importe"));
    }

    private String rechercheJson(String numeroDocument) {
        return "{"
                + "\"typeDocument\":\"" + TypeDocument.CNI.name() + "\","
                + "\"nomTitulaire\":\"Fall\","
                + "\"prenomTitulaire\":\"Moussa\","
                + "\"numeroDocument\":\"" + numeroDocument + "\","
                + "\"dateNaissanceTitulaire\":null"
                + "}";
    }

    @Test
    void meilisearchIndisponible_devraitReplierSurPostgresqlEtTrouverLaPiece() throws Exception {
        Poste poste = creerPoste(creerRegion("Dakar"), "Commissariat Central Dakar");
        Agent agent = creerAgent(poste, "PN-2024-00900");
        NumeroDocumentHache hache = hasher.hacher(NUMERO_DOCUMENT_REEL);
        Piece piece = pieceRepository.save(new Piece(
                "PC-REPLI-2026-00001",
                poste,
                agent,
                TypeDocument.CNI,
                "Fall",
                "Moussa",
                hache.hash(),
                hache.sel(),
                hache.masque(),
                LocalDate.of(1990, 5, 12),
                LocalDate.of(2026, 9, 13),
                "bon état",
                null));

        String reponse = mockMvc.perform(post("/api/v1/recherche-publique")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rechercheJson(NUMERO_DOCUMENT_REEL)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode corps = OBJECT_MAPPER.readTree(reponse);

        assertThat(corps.get("trouve").asBoolean()).isTrue();
        assertThat(corps.get("referenceDossier").asText()).isEqualTo(piece.getNumeroFiche());
        assertThat(corps.get("poste").get("nom").asText()).isEqualTo("Commissariat Central Dakar");
    }
}
