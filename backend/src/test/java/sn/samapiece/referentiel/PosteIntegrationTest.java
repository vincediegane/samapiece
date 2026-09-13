package sn.samapiece.referentiel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PosteIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String HORAIRES = "{\"lundi\":{\"ouvert\":true,\"debut\":\"08:00\",\"fin\":\"18:00\"}}";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegionRepository regionRepository;

    @Autowired
    private PosteRepository posteRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void nettoyer() {
        posteRepository.deleteAll();
        regionRepository.deleteAll();
    }

    @Test
    void persisterEtRelirePoste_shouldChargerRegionAssociee() throws Exception {
        Region region = regionRepository.save(new Region("Dakar"));
        Poste poste = posteRepository.save(new Poste(
                region,
                "Commissariat Central Dakar",
                TypePoste.POLICE,
                "Place de l'Indépendance, Dakar",
                "+221338210000",
                HORAIRES,
                14.6928,
                -17.4467));

        List<Poste> postes = posteRepository.findAll();

        assertThat(postes).hasSize(1);
        Poste relu = postes.get(0);
        assertThat(relu.getId()).isEqualTo(poste.getId());
        assertThat(relu.getNom()).isEqualTo("Commissariat Central Dakar");
        assertThat(relu.getType()).isEqualTo(TypePoste.POLICE);
        assertThat(relu.getAdresse()).isEqualTo("Place de l'Indépendance, Dakar");
        assertThat(relu.getTelephone()).isEqualTo("+221338210000");
        assertThat(OBJECT_MAPPER.readTree(relu.getHoraires())).isEqualTo(OBJECT_MAPPER.readTree(HORAIRES));
        assertThat(relu.getLatitude()).isEqualTo(14.6928);
        assertThat(relu.getLongitude()).isEqualTo(-17.4467);
        assertThat(relu.getRegion().getId()).isEqualTo(region.getId());
        assertThat(relu.getRegion().getNom()).isEqualTo("Dakar");
    }

    @Test
    void insertPosteAvecTypeInvalide_shouldFail() {
        Region region = regionRepository.save(new Region("Thiès"));

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO poste (id, region_id, nom, type, adresse, horaires) "
                                + "VALUES (?, ?, 'Poste invalide', 'inconnu', 'Adresse', '{}'::jsonb)",
                        UUID.randomUUID(),
                        region.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void getPostes_shouldReturnListeSansAuthentification() throws Exception {
        Region region = regionRepository.save(new Region("Dakar"));
        posteRepository.save(new Poste(
                region,
                "Commissariat Central Dakar",
                TypePoste.POLICE,
                "Place de l'Indépendance, Dakar",
                "+221338210000",
                HORAIRES,
                14.6928,
                -17.4467));

        mockMvc.perform(get("/api/v1/postes"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].nom").value("Commissariat Central Dakar"))
                .andExpect(jsonPath("$[0].type").value("POLICE"))
                .andExpect(jsonPath("$[0].region.id").value(region.getId().toString()))
                .andExpect(jsonPath("$[0].region.nom").value("Dakar"));
    }

    @Test
    void getRouteNonPubliqueSansAuthentification_shouldReturn401() throws Exception {
        // Route arbitraire non listée dans permitAll() : sert de témoin pour confirmer que
        // anyRequest().authenticated() s'applique bien, avec l'entry point 401 explicite.
        mockMvc.perform(get("/api/v1/inexistant-protege")).andExpect(status().isUnauthorized());
    }
}
