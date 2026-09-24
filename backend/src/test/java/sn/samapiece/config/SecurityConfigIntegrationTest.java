package sn.samapiece.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import sn.samapiece.iam.Agent;
import sn.samapiece.iam.AgentRepository;
import sn.samapiece.iam.Role;
import sn.samapiece.iam.web.LoginRequest;
import sn.samapiece.iam.web.LoginResponse;
import sn.samapiece.referentiel.Poste;
import sn.samapiece.referentiel.PosteRepository;
import sn.samapiece.referentiel.Region;
import sn.samapiece.referentiel.RegionRepository;
import sn.samapiece.referentiel.TypePoste;

/**
 * Contrairement à {@code AuthIntegrationTest} (MockMvc), ces tests démarrent un serveur embarqué
 * réel ({@code webEnvironment = RANDOM_PORT}) et exercent la pile servlet complète, y compris le
 * forward conteneur (dispatcher ERROR) déclenché par {@code response.sendError(...)}. C'est ce
 * forward, retraversant la chaîne de filtres avec un {@code SecurityContext} anonyme, qui causait
 * le bug #60 (403 réécrit en 401) — un bug que MockMvc ne peut pas détecter puisqu'il ne simule
 * pas ce forward. Voir Javadoc de {@link SecurityConfig}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SecurityConfigIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String HORAIRES = "{\"lundi\":{\"ouvert\":true,\"debut\":\"08:00\",\"fin\":\"18:00\"}}";
    private static final String MOT_DE_PASSE_CLAIR = "MotDePasse123!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private PosteRepository posteRepository;

    @Autowired
    private RegionRepository regionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void nettoyer() {
        agentRepository.deleteAll();
        posteRepository.deleteAll();
        regionRepository.deleteAll();
    }

    private void creerAgentActif(String matricule, Role role) {
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
        agentRepository.save(
                new Agent(poste, matricule, "Diop Awa", role, passwordEncoder.encode(MOT_DE_PASSE_CLAIR)));
    }

    private String login(String matricule) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<LoginRequest> requete = new HttpEntity<>(new LoginRequest(matricule, MOT_DE_PASSE_CLAIR), headers);
        ResponseEntity<LoginResponse> reponse =
                restTemplate.postForEntity("/api/v1/auth/login", requete, LoginResponse.class);
        return reponse.getBody().accessToken();
    }

    @Test
    void routeProtegee_avecJwtValideMaisRoleInsuffisant_shouldRetourner403SurServeurReel() {
        creerAgentActif("PN-2024-00200", Role.AGENT);
        String accessToken = login("PN-2024-00200");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<String> reponse = restTemplate.exchange(
                "/api/v1/agents", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void routeProtegee_sansJwt_shouldRetourner401SurServeurReel() {
        ResponseEntity<String> reponse = restTemplate.getForEntity("/api/v1/agents", String.class);

        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void routeProtegee_avecJwtMalforme_shouldRetourner401SurServeurReel() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer token-invalide");
        ResponseEntity<String> reponse = restTemplate.exchange(
                "/api/v1/agents", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
