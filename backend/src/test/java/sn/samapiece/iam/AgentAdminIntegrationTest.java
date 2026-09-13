package sn.samapiece.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import sn.samapiece.iam.web.CreerAgentRequest;
import sn.samapiece.iam.web.LoginRequest;
import sn.samapiece.iam.web.ModifierAgentRequest;
import sn.samapiece.iam.web.RefreshRequest;
import sn.samapiece.referentiel.Poste;
import sn.samapiece.referentiel.PosteRepository;
import sn.samapiece.referentiel.Region;
import sn.samapiece.referentiel.RegionRepository;
import sn.samapiece.referentiel.TypePoste;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AgentAdminIntegrationTest {

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

    private String creerAgentJson(UUID posteId, String matricule, String nom, Role role) throws Exception {
        return OBJECT_MAPPER.writeValueAsString(new CreerAgentRequest(posteId, matricule, nom, role));
    }

    private String modifierAgentJson(String nom, UUID posteId) throws Exception {
        return OBJECT_MAPPER.writeValueAsString(new ModifierAgentRequest(nom, posteId));
    }

    @Test
    void creer_avecRoleAgent_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00200", Role.AGENT, poste);

        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerAgentJson(poste.getId(), "PN-2024-00201", "Nouvel Agent", Role.AGENT)))
                .andExpect(status().isForbidden());
    }

    @Test
    void patch_avecRoleAuditeur_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00202", Role.AUDITEUR, poste);

        mockMvc.perform(patch("/api/v1/agents/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(modifierAgentJson("Nouveau Nom", null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void desactiver_avecRoleAgent_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00203", Role.AGENT, poste);

        mockMvc.perform(delete("/api/v1/agents/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void creer_avecDonneesValides_shouldRetourner201EtMotDePasseTemporaire() throws Exception {
        Poste poste = creerPoste();
        String tokenAdmin = creerEtLoginToken("PN-2024-00300", Role.ADMIN_NATIONAL, poste);

        String reponse = mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerAgentJson(poste.getId(), "PN-2024-00301", "Fall Moussa", Role.AGENT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.matricule").value("PN-2024-00301"))
                .andExpect(jsonPath("$.motDePasseTemporaire").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String motDePasseTemporaire = OBJECT_MAPPER.readTree(reponse).get("motDePasseTemporaire").asText();
        assertThat(motDePasseTemporaire.length()).isGreaterThanOrEqualTo(12);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsString(
                                new LoginRequest("PN-2024-00301", motDePasseTemporaire))))
                .andExpect(status().isOk());
    }

    @Test
    void creer_avecMatriculeDejaUtilise_shouldRetourner409() throws Exception {
        Poste poste = creerPoste();
        String tokenAdmin = creerEtLoginToken("PN-2024-00302", Role.ADMIN_NATIONAL, poste);
        creerAgentActif(poste, "PN-2024-00303", Role.AGENT);

        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerAgentJson(poste.getId(), "PN-2024-00303", "Fall Moussa", Role.AGENT)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MATRICULE_DEJA_UTILISE"));
    }

    @Test
    void creer_avecPosteInconnu_shouldRetourner404() throws Exception {
        Poste poste = creerPoste();
        String tokenAdmin = creerEtLoginToken("PN-2024-00304", Role.ADMIN_NATIONAL, poste);

        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerAgentJson(UUID.randomUUID(), "PN-2024-00305", "Fall Moussa", Role.AGENT)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POSTE_INTROUVABLE"));
    }

    @Test
    void modifier_avecPosteInconnu_shouldRetourner404() throws Exception {
        Poste poste = creerPoste();
        String tokenAdmin = creerEtLoginToken("PN-2024-00306", Role.ADMIN_NATIONAL, poste);
        Agent agent = creerAgentActif(poste, "PN-2024-00307", Role.AGENT);

        mockMvc.perform(patch("/api/v1/agents/" + agent.getId())
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(modifierAgentJson(null, UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POSTE_INTROUVABLE"));
    }

    @Test
    void modifier_avecIdInconnu_shouldRetourner404() throws Exception {
        Poste poste = creerPoste();
        String tokenAdmin = creerEtLoginToken("PN-2024-00308", Role.ADMIN_NATIONAL, poste);

        mockMvc.perform(patch("/api/v1/agents/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(modifierAgentJson("Nouveau Nom", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AGENT_INTROUVABLE"));
    }

    @Test
    void desactiver_avecIdInconnu_shouldRetourner404() throws Exception {
        Poste poste = creerPoste();
        String tokenAdmin = creerEtLoginToken("PN-2024-00309", Role.ADMIN_NATIONAL, poste);

        mockMvc.perform(delete("/api/v1/agents/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + tokenAdmin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AGENT_INTROUVABLE"));
    }

    @Test
    void modifier_avecNomEtPoste_shouldMettreAJourEtConserverHashEtRole() throws Exception {
        Poste poste = creerPoste();
        Poste autrePoste = posteRepository.save(new Poste(
                poste.getRegion(), "Commissariat Secondaire", TypePoste.POLICE,
                "Autre adresse", "+221338210001", HORAIRES, 14.7, -17.5));
        String tokenAdmin = creerEtLoginToken("PN-2024-00310", Role.ADMIN_NATIONAL, poste);
        Agent agent = creerAgentActif(poste, "PN-2024-00311", Role.AGENT);
        String hashAvant = agent.getHashMotDePasse();

        mockMvc.perform(patch("/api/v1/agents/" + agent.getId())
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(modifierAgentJson("Fall Moussa", autrePoste.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nom").value("Fall Moussa"))
                .andExpect(jsonPath("$.posteId").value(autrePoste.getId().toString()));

        Agent relu = agentRepository.findById(agent.getId()).orElseThrow();
        assertThat(relu.getNom()).isEqualTo("Fall Moussa");
        assertThat(relu.getPoste().getId()).isEqualTo(autrePoste.getId());
        assertThat(relu.getHashMotDePasse()).isEqualTo(hashAvant);
        assertThat(relu.getRole()).isEqualTo(Role.AGENT);
    }

    @Test
    void desactiver_appeleDeuxFois_shouldRetourner204LesDeuxFois() throws Exception {
        Poste poste = creerPoste();
        String tokenAdmin = creerEtLoginToken("PN-2024-00312", Role.ADMIN_NATIONAL, poste);
        Agent agent = creerAgentActif(poste, "PN-2024-00313", Role.AGENT);

        mockMvc.perform(delete("/api/v1/agents/" + agent.getId())
                        .header("Authorization", "Bearer " + tokenAdmin))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/agents/" + agent.getId())
                        .header("Authorization", "Bearer " + tokenAdmin))
                .andExpect(status().isNoContent());

        Agent relu = agentRepository.findById(agent.getId()).orElseThrow();
        assertThat(relu.isActif()).isFalse();
    }

    @Test
    void desactiver_puisLogin_shouldRetourner401() throws Exception {
        Poste poste = creerPoste();
        String tokenAdmin = creerEtLoginToken("PN-2024-00314", Role.ADMIN_NATIONAL, poste);
        Agent agent = creerAgentActif(poste, "PN-2024-00315", Role.AGENT);

        login("PN-2024-00315", MOT_DE_PASSE_CLAIR);

        mockMvc.perform(delete("/api/v1/agents/" + agent.getId())
                        .header("Authorization", "Bearer " + tokenAdmin))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsString(
                                new LoginRequest("PN-2024-00315", MOT_DE_PASSE_CLAIR))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("IDENTIFIANTS_INVALIDES"));
    }

    @Test
    void desactiver_puisRefresh_shouldRetourner401Immediatement() throws Exception {
        Poste poste = creerPoste();
        String tokenAdmin = creerEtLoginToken("PN-2024-00316", Role.ADMIN_NATIONAL, poste);
        Agent agent = creerAgentActif(poste, "PN-2024-00317", Role.AGENT);

        String reponseLogin = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsString(
                                new LoginRequest("PN-2024-00317", MOT_DE_PASSE_CLAIR))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String refreshToken = OBJECT_MAPPER.readTree(reponseLogin).get("refreshToken").asText();

        mockMvc.perform(delete("/api/v1/agents/" + agent.getId())
                        .header("Authorization", "Bearer " + tokenAdmin))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsString(new RefreshRequest(refreshToken))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void desactiver_accessTokenDejaEmis_shouldResterValideJusquaExpiration() throws Exception {
        Poste poste = creerPoste();
        String tokenAdmin = creerEtLoginToken("PN-2024-00318", Role.ADMIN_NATIONAL, poste);
        Agent agent = creerAgentActif(poste, "PN-2024-00319", Role.AGENT);

        String reponseLogin = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsString(
                                new LoginRequest("PN-2024-00319", MOT_DE_PASSE_CLAIR))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String accessTokenAgentDesactive = OBJECT_MAPPER.readTree(reponseLogin).get("accessToken").asText();

        mockMvc.perform(delete("/api/v1/agents/" + agent.getId())
                        .header("Authorization", "Bearer " + tokenAdmin))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/inexistant-protege")
                        .header("Authorization", "Bearer " + accessTokenAgentDesactive))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }
}
