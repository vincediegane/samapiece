package sn.samapiece.recherche.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import sn.samapiece.enregistrement.PieceRepository;
import sn.samapiece.iam.AgentRepository;
import sn.samapiece.referentiel.PosteRepository;
import sn.samapiece.referentiel.RegionRepository;

/**
 * Isole le rate limiting du CAPTCHA en abaissant fortement la capacité du bucket
 * ({@code samapiece.rate-limiting.recherche-publique.capacite}) et en relevant le seuil du CAPTCHA
 * ({@code samapiece.captcha.seuil-echecs-consecutifs}), voir spec #19.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RecherchePubliqueRateLimitingIntegrationTest {

    private static final String IP_SIMULEE = "1.2.3.4";
    private static final long CAPACITE = 3;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void proprietes(DynamicPropertyRegistry registry) {
        registry.add("samapiece.rate-limiting.recherche-publique.capacite", () -> CAPACITE);
        registry.add("samapiece.rate-limiting.recherche-publique.periode-secondes", () -> 60);
        registry.add("samapiece.captcha.seuil-echecs-consecutifs", () -> 1000);
    }

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
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void nettoyer() {
        pieceRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM piece_sequence");
        agentRepository.deleteAll();
        posteRepository.deleteAll();
        regionRepository.deleteAll();
    }

    private String rechercheJson() {
        return "{"
                + "\"typeDocument\":\"CNI\","
                + "\"nomTitulaire\":\"Fall\","
                + "\"prenomTitulaire\":\"Moussa\","
                + "\"numeroDocument\":\"1234567890123\","
                + "\"dateNaissanceTitulaire\":null"
                + "}";
    }

    @Test
    void depassementDuSeuil_devraitRenvoyer429AvecCorpsEtRetryAfter() throws Exception {
        for (int i = 0; i < CAPACITE; i++) {
            mockMvc.perform(post("/api/v1/recherche-publique")
                    .with(request -> {
                        request.setRemoteAddr(IP_SIMULEE);
                        return request;
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(rechercheJson()));
        }

        var resultat = mockMvc.perform(post("/api/v1/recherche-publique")
                        .with(request -> {
                            request.setRemoteAddr(IP_SIMULEE);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rechercheJson()))
                .andReturn();

        assertThat(resultat.getResponse().getStatus()).isEqualTo(429);
        String retryAfter = resultat.getResponse().getHeader("Retry-After");
        assertThat(retryAfter).isNotNull();
        assertThat(Integer.parseInt(retryAfter)).isPositive();
        JsonNode corps = OBJECT_MAPPER.readTree(
                resultat.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(corps.get("code").asText()).isEqualTo("LIMITE_DEBIT_DEPASSEE");
    }
}
