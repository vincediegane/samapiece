package sn.samapiece.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import sn.samapiece.iam.jwt.JwtProperties;
import sn.samapiece.iam.jwt.JwtService;
import sn.samapiece.iam.web.LoginRequest;
import sn.samapiece.iam.web.RefreshRequest;
import sn.samapiece.referentiel.Poste;
import sn.samapiece.referentiel.PosteRepository;
import sn.samapiece.referentiel.Region;
import sn.samapiece.referentiel.RegionRepository;
import sn.samapiece.referentiel.TypePoste;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthIntegrationTest {

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    @BeforeEach
    void nettoyer() {
        agentRepository.deleteAll();
        posteRepository.deleteAll();
        regionRepository.deleteAll();
    }

    private Poste creerPoste() {
        Region region = regionRepository.save(new Region("Dakar"));
        return posteRepository.save(new Poste(
                region,
                "Commissariat Central Dakar",
                TypePoste.POLICE,
                "Place de l'Indépendance, Dakar",
                "+221338210000",
                HORAIRES,
                14.6928,
                -17.4467));
    }

    private Agent creerAgentActif(String matricule, Role role) {
        Poste poste = creerPoste();
        return agentRepository.save(
                new Agent(poste, matricule, "Diop Awa", role, passwordEncoder.encode(MOT_DE_PASSE_CLAIR)));
    }

    private void desactiverAgent(Agent agent) {
        jdbcTemplate.update("UPDATE agent SET actif = false WHERE id = ?", agent.getId());
    }

    private String loginJson(String matricule, String motDePasse) throws Exception {
        return OBJECT_MAPPER.writeValueAsString(new LoginRequest(matricule, motDePasse));
    }

    private String refreshJson(String refreshToken) throws Exception {
        return OBJECT_MAPPER.writeValueAsString(new RefreshRequest(refreshToken));
    }

    @Test
    void login_avecIdentifiantsValides_shouldRetournerAccessEtRefreshToken() throws Exception {
        creerAgentActif("PN-2024-00123", Role.CHEF_POSTE);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("PN-2024-00123", MOT_DE_PASSE_CLAIR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.role").value("CHEF_POSTE"))
                .andExpect(jsonPath("$.nom").value("Diop Awa"));
    }

    @Test
    void login_avecMotDePasseInvalide_shouldRetourner401() throws Exception {
        creerAgentActif("PN-2024-00124", Role.AGENT);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("PN-2024-00124", "mauvaisMotDePasse")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("IDENTIFIANTS_INVALIDES"));
    }

    @Test
    void login_avecMatriculeInconnu_shouldRetourner401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("PN-2024-INCONNU", MOT_DE_PASSE_CLAIR)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("IDENTIFIANTS_INVALIDES"));
    }

    @Test
    void login_avecCompteInactif_shouldRetourner401SansIncrementerCompteur() throws Exception {
        Agent agent = creerAgentActif("PN-2024-00125", Role.AGENT);
        desactiverAgent(agent);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("PN-2024-00125", MOT_DE_PASSE_CLAIR)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("IDENTIFIANTS_INVALIDES"));

        Agent relu = agentRepository.findById(agent.getId()).orElseThrow();
        assertThat(relu.getTentativesEchouees()).isZero();
    }

    @Test
    void login_apresCinqEchecsConsecutifs_shouldRetourner423DesLaCinquiemeTentative() throws Exception {
        Agent agent = creerAgentActif("PN-2024-00126", Role.AGENT);

        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson("PN-2024-00126", "mauvaisMotDePasse")))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("PN-2024-00126", "mauvaisMotDePasse")))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("COMPTE_VERROUILLE"));

        Agent relu = agentRepository.findById(agent.getId()).orElseThrow();
        assertThat(relu.getTentativesEchouees()).isZero();
        assertThat(relu.getVerrouilleJusqua()).isAfter(OffsetDateTime.now());
    }

    @Test
    void login_surCompteDejaVerrouille_shouldRetourner423MemeAvecMotDePasseCorrect() throws Exception {
        creerAgentActif("PN-2024-00127", Role.AGENT);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginJson("PN-2024-00127", "mauvaisMotDePasse")));
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("PN-2024-00127", MOT_DE_PASSE_CLAIR)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("COMPTE_VERROUILLE"));
    }

    @Test
    void refresh_avecRefreshTokenValide_shouldRetournerNouvelAccessToken() throws Exception {
        creerAgentActif("PN-2024-00128", Role.AGENT);

        String reponseLogin = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("PN-2024-00128", MOT_DE_PASSE_CLAIR)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String refreshToken = OBJECT_MAPPER.readTree(reponseLogin).get("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson(refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(900));
    }

    @Test
    void refresh_avecTokenExpire_shouldRetourner401() throws Exception {
        Agent agent = creerAgentActif("PN-2024-00129", Role.AGENT);
        Clock horlogeDansLePasse = Clock.fixed(Instant.now().minus(Duration.ofDays(8)), ZoneOffset.UTC);
        JwtService jwtServiceExpire = new JwtService(jwtProperties, horlogeDansLePasse);
        String refreshTokenExpire = jwtServiceExpire.genererRefreshToken(agent.getId(), agent.getMatricule());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson(refreshTokenExpire)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_avecAccessTokenAuLieuDeRefresh_shouldRetourner401() throws Exception {
        creerAgentActif("PN-2024-00130", Role.AGENT);

        String reponseLogin = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("PN-2024-00130", MOT_DE_PASSE_CLAIR)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String accessToken = OBJECT_MAPPER.readTree(reponseLogin).get("accessToken").asText();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson(accessToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void routeProtegee_sansJwt_shouldRetourner401() throws Exception {
        mockMvc.perform(get("/api/v1/inexistant-protege")).andExpect(status().isUnauthorized());
    }

    @Test
    void routeProtegee_avecJwtMalforme_shouldRetourner401() throws Exception {
        mockMvc.perform(get("/api/v1/inexistant-protege")
                        .header("Authorization", "Bearer token-invalide"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void routeProtegee_avecAccessTokenValide_shouldPasserLeFiltreJwt() throws Exception {
        creerAgentActif("PN-2024-00131", Role.AGENT);

        String reponseLogin = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("PN-2024-00131", MOT_DE_PASSE_CLAIR)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String accessToken = OBJECT_MAPPER.readTree(reponseLogin).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/inexistant-protege")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    @Test
    void routeProtegee_avecJwtValideMaisRoleInsuffisant_shouldRetourner403() throws Exception {
        creerAgentActif("PN-2024-00132", Role.AGENT);

        String reponseLogin = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("PN-2024-00132", MOT_DE_PASSE_CLAIR)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String accessToken = OBJECT_MAPPER.readTree(reponseLogin).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/agents")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void routeProtegee_avecAccessTokenExpire_shouldRetourner401() throws Exception {
        Agent agent = creerAgentActif("PN-2024-00133", Role.CHEF_POSTE);
        Clock horlogeDansLePasse = Clock.fixed(Instant.now().minus(Duration.ofMinutes(20)), ZoneOffset.UTC);
        JwtService jwtServiceExpire = new JwtService(jwtProperties, horlogeDansLePasse);
        String accessTokenExpire = jwtServiceExpire.genererAccessToken(
                agent.getId(), agent.getMatricule(), agent.getRole(), false);

        mockMvc.perform(get("/api/v1/agents")
                        .header("Authorization", "Bearer " + accessTokenExpire))
                .andExpect(status().isUnauthorized());
    }
}
