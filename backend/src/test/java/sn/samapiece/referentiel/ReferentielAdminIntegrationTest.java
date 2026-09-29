package sn.samapiece.referentiel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import sn.samapiece.iam.Agent;
import sn.samapiece.iam.AgentRepository;
import sn.samapiece.iam.Role;
import sn.samapiece.iam.web.LoginRequest;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ReferentielAdminIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String HORAIRES = "{\"lundi\":{\"ouvert\":true,\"debut\":\"08:00\",\"fin\":\"18:00\"}}";
    private static final String MOT_DE_PASSE_CLAIR = "MotDePasse123!";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

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

    private Poste posteDeDepart;

    @BeforeEach
    void nettoyer() {
        jdbcTemplate.update("DELETE FROM evenement_audit");
        agentRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM piece_sequence");
        posteRepository.deleteAll();
        regionRepository.deleteAll();
        Region region = regionRepository.save(new Region("Region de depart"));
        posteDeDepart = posteRepository.save(new Poste(
                region, "Poste de depart", TypePoste.POLICE, "Adresse", "+221338210000", HORAIRES, null, null));
    }

    private String token(Role role) throws Exception {
        String matricule = "PN-2024-" + role.name();
        Agent agent = new Agent(
                posteDeDepart, matricule, "Diop Awa", role, passwordEncoder.encode(MOT_DE_PASSE_CLAIR));
        agentRepository.save(agent);
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

    private ResultActions postJson(String url, String token, String corps) throws Exception {
        var requete = post(url).contentType(MediaType.APPLICATION_JSON).content(corps);
        if (token != null) {
            requete.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(requete);
    }

    private Map<String, Object> corpsPoste(UUID regionId, String nom) throws Exception {
        Map<String, Object> corps = new LinkedHashMap<>();
        corps.put("regionId", regionId);
        corps.put("nom", nom);
        corps.put("type", "POLICE");
        corps.put("adresse", "Route de Bargny");
        corps.put("telephone", "+221338360000");
        corps.put("horaires", OBJECT_MAPPER.readTree(HORAIRES));
        corps.put("latitude", 14.72);
        corps.put("longitude", -17.27);
        return corps;
    }

    private String json(Object o) throws Exception {
        return OBJECT_MAPPER.writeValueAsString(o);
    }

    private String creerRegionViaApi(String token, String nom) throws Exception {
        String reponse = postJson("/api/v1/regions", token, json(Map.of("nom", nom)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return OBJECT_MAPPER.readTree(reponse).get("id").asText();
    }

    private Long compterAudit(String action, String resultat) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM evenement_audit WHERE action = ? AND details->>'resultat' = ?",
                Long.class,
                action,
                resultat);
    }

    @Test
    void creerRegion_adminNational_shouldReturn201AvecIdEtNom() throws Exception {
        String token = token(Role.ADMIN_NATIONAL);

        postJson("/api/v1/regions", token, json(Map.of("nom", "Dakar")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.nom").value("Dakar"));

        assertThat(regionRepository.existsByNomIgnoreCase("Dakar")).isTrue();
    }

    @Test
    void creerPoste_adminNational_shouldReturn201EtPersister() throws Exception {
        String token = token(Role.ADMIN_NATIONAL);
        UUID regionId = UUID.fromString(creerRegionViaApi(token, "Dakar"));

        postJson("/api/v1/postes", token, json(corpsPoste(regionId, "Commissariat de Rufisque")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.nom").value("Commissariat de Rufisque"))
                .andExpect(jsonPath("$.type").value("POLICE"))
                .andExpect(jsonPath("$.region.id").value(regionId.toString()))
                .andExpect(jsonPath("$.region.nom").value("Dakar"));

        String typeJson = jdbcTemplate.queryForObject(
                "SELECT jsonb_typeof(horaires) FROM poste WHERE nom = 'Commissariat de Rufisque'", String.class);
        String debut = jdbcTemplate.queryForObject(
                "SELECT horaires->'lundi'->>'debut' FROM poste WHERE nom = 'Commissariat de Rufisque'",
                String.class);
        String typeSql = jdbcTemplate.queryForObject(
                "SELECT type FROM poste WHERE nom = 'Commissariat de Rufisque'", String.class);
        assertThat(typeJson).isEqualTo("object");
        assertThat(debut).isEqualTo("08:00");
        assertThat(typeSql).isEqualTo("police");
    }

    @Test
    void listerRegions_adminNational_shouldReturn200Trie() throws Exception {
        String token = token(Role.ADMIN_NATIONAL);
        creerRegionViaApi(token, "Thies");
        creerRegionViaApi(token, "Dakar");

        String reponse = mockMvc.perform(get("/api/v1/regions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        JsonNode noeuds = OBJECT_MAPPER.readTree(reponse);
        assertThat(noeuds).hasSize(3);
        assertThat(noeuds.get(0).get("nom").asText()).isEqualTo("Dakar");
        assertThat(noeuds.get(1).get("nom").asText()).isEqualTo("Region de depart");
        assertThat(noeuds.get(2).get("nom").asText()).isEqualTo("Thies");
    }

    @Test
    void getPostes_apresCreation_shouldListerLePosteSansAuthentification() throws Exception {
        String token = token(Role.ADMIN_NATIONAL);
        UUID regionId = UUID.fromString(creerRegionViaApi(token, "Dakar"));
        postJson("/api/v1/postes", token, json(corpsPoste(regionId, "Commissariat de Rufisque")))
                .andExpect(status().isCreated());

        String reponse = mockMvc.perform(get("/api/v1/postes"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(reponse).contains("Commissariat de Rufisque");
    }

    @Test
    void creerRegion_nomVide_shouldReturn400() throws Exception {
        String token = token(Role.ADMIN_NATIONAL);

        postJson("/api/v1/regions", token, json(Map.of("nom", "   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUETE_INVALIDE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"nom", "type", "adresse", "telephone", "horaires", "regionId"})
    void creerPoste_champManquant_shouldReturn400(String champ) throws Exception {
        String token = token(Role.ADMIN_NATIONAL);
        UUID regionId = UUID.fromString(creerRegionViaApi(token, "Dakar"));

        Map<String, Object> absent = corpsPoste(regionId, "Poste A");
        absent.remove(champ);
        postJson("/api/v1/postes", token, json(absent))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUETE_INVALIDE"));

        if (List.of("nom", "adresse", "telephone").contains(champ)) {
            Map<String, Object> blanc = corpsPoste(regionId, "Poste A");
            blanc.put(champ, "  ");
            postJson("/api/v1/postes", token, json(blanc))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("REQUETE_INVALIDE"));
        }
        assertThat(posteRepository.count()).isEqualTo(1);
    }

    @Test
    void creerPoste_nomTropLong_shouldReturn400() throws Exception {
        String token = token(Role.ADMIN_NATIONAL);
        UUID regionId = UUID.fromString(creerRegionViaApi(token, "Dakar"));

        postJson("/api/v1/postes", token, json(corpsPoste(regionId, "x".repeat(256))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUETE_INVALIDE"));
    }

    @Test
    void creerPoste_latitudeHorsBornes_shouldReturn400() throws Exception {
        String token = token(Role.ADMIN_NATIONAL);
        UUID regionId = UUID.fromString(creerRegionViaApi(token, "Dakar"));
        Map<String, Object> corps = corpsPoste(regionId, "Poste A");
        corps.put("latitude", 91);

        postJson("/api/v1/postes", token, json(corps))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUETE_INVALIDE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MILITAIRE", "police"})
    void creerPoste_typeInconnu_shouldReturn400(String type) throws Exception {
        String token = token(Role.ADMIN_NATIONAL);
        UUID regionId = UUID.fromString(creerRegionViaApi(token, "Dakar"));
        Map<String, Object> corps = corpsPoste(regionId, "Poste A");
        corps.put("type", type);

        postJson("/api/v1/postes", token, json(corps))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUETE_INVALIDE"));
    }

    @Test
    void creerPoste_horairesChaine_shouldReturn400() throws Exception {
        String token = token(Role.ADMIN_NATIONAL);
        UUID regionId = UUID.fromString(creerRegionViaApi(token, "Dakar"));
        Map<String, Object> corps = corpsPoste(regionId, "Poste A");
        corps.put("horaires", HORAIRES);

        postJson("/api/v1/postes", token, json(corps))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUETE_INVALIDE"));
    }

    @Test
    void creerPoste_horairesVideObjet_shouldReturn400() throws Exception {
        String token = token(Role.ADMIN_NATIONAL);
        UUID regionId = UUID.fromString(creerRegionViaApi(token, "Dakar"));
        Map<String, Object> corps = corpsPoste(regionId, "Poste A");
        corps.put("horaires", Map.of());

        postJson("/api/v1/postes", token, json(corps))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUETE_INVALIDE"));
    }

    @Test
    void creerPoste_corpsJsonInvalide_shouldReturn400() throws Exception {
        String token = token(Role.ADMIN_NATIONAL);

        postJson("/api/v1/postes", token, "{\"nom\":\"Poste A\"")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUETE_INVALIDE"))
                .andExpect(jsonPath("$.message").value("Corps de requete invalide."));
    }

    @Test
    void creerRegion_doublonInsensibleCasse_shouldReturn409() throws Exception {
        String token = token(Role.ADMIN_NATIONAL);
        creerRegionViaApi(token, "Dakar");

        postJson("/api/v1/regions", token, json(Map.of("nom", "  dAkAr ")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REGION_DEJA_EXISTANTE"));
    }

    @Test
    void creerPoste_memeNomMemeRegion_shouldReturn409() throws Exception {
        String token = token(Role.ADMIN_NATIONAL);
        UUID regionId = UUID.fromString(creerRegionViaApi(token, "Dakar"));
        postJson("/api/v1/postes", token, json(corpsPoste(regionId, "Commissariat de Rufisque")))
                .andExpect(status().isCreated());

        postJson("/api/v1/postes", token, json(corpsPoste(regionId, "commissariat DE rufisque")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POSTE_DEJA_EXISTANT"));
    }

    @Test
    void creerPoste_memeNomAutreRegion_shouldReturn201() throws Exception {
        String token = token(Role.ADMIN_NATIONAL);
        UUID dakar = UUID.fromString(creerRegionViaApi(token, "Dakar"));
        UUID thies = UUID.fromString(creerRegionViaApi(token, "Thies"));
        postJson("/api/v1/postes", token, json(corpsPoste(dakar, "Commissariat Central")))
                .andExpect(status().isCreated());

        postJson("/api/v1/postes", token, json(corpsPoste(thies, "Commissariat Central")))
                .andExpect(status().isCreated());
    }

    @Test
    void creerPoste_regionInconnue_shouldReturn404() throws Exception {
        String token = token(Role.ADMIN_NATIONAL);

        postJson("/api/v1/postes", token, json(corpsPoste(UUID.randomUUID(), "Poste A")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGION_INTROUVABLE"));
    }

    @Test
    void index_unique_shouldRejeterDoublonSQL() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO region (id, nom) VALUES (?, 'REGION DE DEPART')", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO poste (id, region_id, nom, type, adresse, horaires) "
                                + "VALUES (?, ?, 'POSTE DE DEPART', 'police', 'Adresse', '{}'::jsonb)",
                        UUID.randomUUID(),
                        posteDeDepart.getRegion().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @EnumSource(
            value = Role.class,
            names = {"AGENT", "CHEF_POSTE", "ADMIN_REGIONAL", "AUDITEUR"})
    void roleNonAutorise_shouldReturn403EtNeRienCreer(Role role) throws Exception {
        String token = token(role);
        long regions = regionRepository.count();
        long postes = posteRepository.count();
        UUID regionId = posteDeDepart.getRegion().getId();

        postJson("/api/v1/regions", token, json(Map.of("nom", "Dakar"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/regions").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        postJson("/api/v1/postes", token, json(corpsPoste(regionId, "Poste A"))).andExpect(status().isForbidden());

        assertThat(regionRepository.count()).isEqualTo(regions);
        assertThat(posteRepository.count()).isEqualTo(postes);
    }

    @Test
    void postRegionsSansJeton_shouldReturn401() throws Exception {
        postJson("/api/v1/regions", null, json(Map.of("nom", "Dakar"))).andExpect(status().isUnauthorized());
    }

    @Test
    void postPostesSansJeton_shouldReturn401() throws Exception {
        postJson("/api/v1/postes", null, json(corpsPoste(posteDeDepart.getRegion().getId(), "Poste A")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void creerRegion_shouldAuditerREGION_CREEE() throws Exception {
        String token = token(Role.ADMIN_NATIONAL);
        String id = creerRegionViaApi(token, "Dakar");

        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM evenement_audit WHERE action = 'REGION_CREEE' AND entite_cible = 'REGION' "
                        + "AND entite_cible_id = ?::uuid AND details->>'resultat' = 'SUCCES'",
                Long.class,
                id);
        assertThat(total).isEqualTo(1L);
    }

    @Test
    void creerPoste_shouldAuditerPOSTE_CREE() throws Exception {
        String token = token(Role.ADMIN_NATIONAL);
        UUID regionId = UUID.fromString(creerRegionViaApi(token, "Dakar"));
        String reponse = postJson("/api/v1/postes", token, json(corpsPoste(regionId, "Commissariat de Rufisque")))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String id = OBJECT_MAPPER.readTree(reponse).get("id").asText();

        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM evenement_audit WHERE action = 'POSTE_CREE' AND entite_cible = 'POSTE' "
                        + "AND entite_cible_id = ?::uuid AND details->>'resultat' = 'SUCCES'",
                Long.class,
                id);
        assertThat(total).isEqualTo(1L);
    }

    @Test
    void creerPoste_doublon_shouldAuditerEchec() throws Exception {
        String token = token(Role.ADMIN_NATIONAL);
        UUID regionId = UUID.fromString(creerRegionViaApi(token, "Dakar"));
        postJson("/api/v1/postes", token, json(corpsPoste(regionId, "Commissariat de Rufisque")))
                .andExpect(status().isCreated());
        postJson("/api/v1/postes", token, json(corpsPoste(regionId, "Commissariat de Rufisque")))
                .andExpect(status().isConflict());

        assertThat(compterAudit("POSTE_CREE", "ECHEC")).isEqualTo(1L);
    }
}
