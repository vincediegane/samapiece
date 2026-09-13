package sn.samapiece.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private List<String> matriculesDe(String reponseJson) throws Exception {
        List<String> matricules = new ArrayList<>();
        for (JsonNode noeud : OBJECT_MAPPER.readTree(reponseJson)) {
            matricules.add(noeud.get("matricule").asText());
        }
        return matricules;
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

    @Test
    void lister_commeChefPoste_shouldRetournerAgentsDeSonPosteUniquement() throws Exception {
        Region region = creerRegion("Dakar");
        Poste poste1 = creerPoste(region, "Poste 1");
        Poste poste2 = creerPoste(region, "Poste 2");
        String tokenChef = creerEtLoginToken("PN-2024-00400", Role.CHEF_POSTE, poste1);
        creerAgentActif(poste1, "PN-2024-00401", Role.AGENT);
        creerAgentActif(poste2, "PN-2024-00402", Role.AGENT);

        String reponse = mockMvc.perform(get("/api/v1/agents")
                        .header("Authorization", "Bearer " + tokenChef))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(matriculesDe(reponse)).containsExactlyInAnyOrder("PN-2024-00400", "PN-2024-00401");
    }

    @Test
    void lister_commeAdminRegional_shouldRetournerAgentsDeSaRegionUniquement() throws Exception {
        Region regionA = creerRegion("Region A");
        Region regionB = creerRegion("Region B");
        Poste posteA = creerPoste(regionA, "Poste A");
        Poste posteB = creerPoste(regionB, "Poste B");
        String tokenAdminRegional = creerEtLoginToken("PN-2024-00403", Role.ADMIN_REGIONAL, posteA);
        creerAgentActif(posteA, "PN-2024-00404", Role.AGENT);
        creerAgentActif(posteB, "PN-2024-00405", Role.AGENT);

        String reponse = mockMvc.perform(get("/api/v1/agents")
                        .header("Authorization", "Bearer " + tokenAdminRegional))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(matriculesDe(reponse)).containsExactlyInAnyOrder("PN-2024-00403", "PN-2024-00404");
    }

    @Test
    void lister_commeAdminNational_shouldRetournerTousLesAgents() throws Exception {
        Region regionA = creerRegion("Region A");
        Region regionB = creerRegion("Region B");
        Poste posteA = creerPoste(regionA, "Poste A");
        Poste posteB = creerPoste(regionB, "Poste B");
        String tokenAdminNational = creerEtLoginToken("PN-2024-00406", Role.ADMIN_NATIONAL, posteA);
        creerAgentActif(posteA, "PN-2024-00407", Role.AGENT);
        creerAgentActif(posteB, "PN-2024-00408", Role.AGENT);

        String reponse = mockMvc.perform(get("/api/v1/agents")
                        .header("Authorization", "Bearer " + tokenAdminNational))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(matriculesDe(reponse))
                .containsExactlyInAnyOrder("PN-2024-00406", "PN-2024-00407", "PN-2024-00408");
    }

    @Test
    void lister_commeAgent_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00409", Role.AGENT, poste);

        mockMvc.perform(get("/api/v1/agents")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void lister_commeAuditeur_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00410", Role.AUDITEUR, poste);

        mockMvc.perform(get("/api/v1/agents")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void creer_commeChefPoste_versSonPropresPoste_avecRoleAgent_shouldRetourner201() throws Exception {
        Poste poste = creerPoste();
        String tokenChef = creerEtLoginToken("PN-2024-00411", Role.CHEF_POSTE, poste);

        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + tokenChef)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerAgentJson(poste.getId(), "PN-2024-00412", "Nouvel Agent", Role.AGENT)))
                .andExpect(status().isCreated());
    }

    @Test
    void creer_commeChefPoste_versAutrePoste_shouldRetourner403() throws Exception {
        Region region = creerRegion("Dakar");
        Poste posteChef = creerPoste(region, "Poste Chef");
        Poste autrePoste = creerPoste(region, "Autre Poste");
        String tokenChef = creerEtLoginToken("PN-2024-00413", Role.CHEF_POSTE, posteChef);

        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + tokenChef)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerAgentJson(autrePoste.getId(), "PN-2024-00414", "Nouvel Agent", Role.AGENT)))
                .andExpect(status().isForbidden());
    }

    @Test
    void creer_commeChefPoste_avecRoleChefPoste_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        String tokenChef = creerEtLoginToken("PN-2024-00415", Role.CHEF_POSTE, poste);

        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + tokenChef)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerAgentJson(poste.getId(), "PN-2024-00416", "Nouveau Chef", Role.CHEF_POSTE)))
                .andExpect(status().isForbidden());
    }

    @Test
    void creer_commeAdminRegional_versPosteDeSaRegion_avecRoleChefPoste_shouldRetourner201() throws Exception {
        Region region = creerRegion("Dakar");
        Poste posteAdmin = creerPoste(region, "Poste Admin");
        Poste posteCible = creerPoste(region, "Poste Cible");
        String tokenAdminRegional = creerEtLoginToken("PN-2024-00417", Role.ADMIN_REGIONAL, posteAdmin);

        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + tokenAdminRegional)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerAgentJson(posteCible.getId(), "PN-2024-00418", "Nouveau Chef", Role.CHEF_POSTE)))
                .andExpect(status().isCreated());
    }

    @Test
    void creer_commeAdminRegional_versPosteHorsRegion_shouldRetourner403() throws Exception {
        Region regionA = creerRegion("Region A");
        Region regionB = creerRegion("Region B");
        Poste posteAdmin = creerPoste(regionA, "Poste Admin");
        Poste posteHorsRegion = creerPoste(regionB, "Poste Hors Region");
        String tokenAdminRegional = creerEtLoginToken("PN-2024-00419", Role.ADMIN_REGIONAL, posteAdmin);

        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + tokenAdminRegional)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerAgentJson(posteHorsRegion.getId(), "PN-2024-00420", "Nouvel Agent", Role.AGENT)))
                .andExpect(status().isForbidden());
    }

    @Test
    void creer_commeAdminRegional_avecRoleAdminNational_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        String tokenAdminRegional = creerEtLoginToken("PN-2024-00421", Role.ADMIN_REGIONAL, poste);

        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + tokenAdminRegional)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerAgentJson(poste.getId(), "PN-2024-00422", "Nouvel Admin", Role.ADMIN_NATIONAL)))
                .andExpect(status().isForbidden());
    }

    @Test
    void creer_commeAdminNational_avecRoleAdminNational_shouldRetourner201() throws Exception {
        Poste poste = creerPoste();
        String tokenAdminNational = creerEtLoginToken("PN-2024-00423", Role.ADMIN_NATIONAL, poste);

        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + tokenAdminNational)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerAgentJson(poste.getId(), "PN-2024-00424", "Nouvel Admin", Role.ADMIN_NATIONAL)))
                .andExpect(status().isCreated());
    }

    @Test
    void creer_commeAuditeur_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        String tokenAuditeur = creerEtLoginToken("PN-2024-00425", Role.AUDITEUR, poste);

        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + tokenAuditeur)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerAgentJson(poste.getId(), "PN-2024-00426", "Nouvel Agent", Role.AGENT)))
                .andExpect(status().isForbidden());
    }

    @Test
    void modifier_commeChefPoste_agentDeSonPoste_shouldRetourner200() throws Exception {
        Poste poste = creerPoste();
        String tokenChef = creerEtLoginToken("PN-2024-00427", Role.CHEF_POSTE, poste);
        Agent agentCible = creerAgentActif(poste, "PN-2024-00428", Role.AGENT);

        mockMvc.perform(patch("/api/v1/agents/" + agentCible.getId())
                        .header("Authorization", "Bearer " + tokenChef)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(modifierAgentJson("Nouveau Nom", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nom").value("Nouveau Nom"));
    }

    @Test
    void modifier_commeChefPoste_agentDAutrePoste_shouldRetourner403() throws Exception {
        Region region = creerRegion("Dakar");
        Poste posteChef = creerPoste(region, "Poste Chef");
        Poste autrePoste = creerPoste(region, "Autre Poste");
        String tokenChef = creerEtLoginToken("PN-2024-00429", Role.CHEF_POSTE, posteChef);
        Agent agentCible = creerAgentActif(autrePoste, "PN-2024-00430", Role.AGENT);

        mockMvc.perform(patch("/api/v1/agents/" + agentCible.getId())
                        .header("Authorization", "Bearer " + tokenChef)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(modifierAgentJson("Nouveau Nom", null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void modifier_commeChefPoste_versAutrePoste_shouldRetourner403() throws Exception {
        Region region = creerRegion("Dakar");
        Poste posteChef = creerPoste(region, "Poste Chef");
        Poste autrePoste = creerPoste(region, "Autre Poste");
        String tokenChef = creerEtLoginToken("PN-2024-00431", Role.CHEF_POSTE, posteChef);
        Agent agentCible = creerAgentActif(posteChef, "PN-2024-00432", Role.AGENT);

        mockMvc.perform(patch("/api/v1/agents/" + agentCible.getId())
                        .header("Authorization", "Bearer " + tokenChef)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(modifierAgentJson(null, autrePoste.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void modifier_commeAdminRegional_agentDeSaRegion_shouldRetourner200() throws Exception {
        Region region = creerRegion("Dakar");
        Poste posteAdmin = creerPoste(region, "Poste Admin");
        Poste autrePosteMemeRegion = creerPoste(region, "Autre Poste Meme Region");
        String tokenAdminRegional = creerEtLoginToken("PN-2024-00433", Role.ADMIN_REGIONAL, posteAdmin);
        Agent agentCible = creerAgentActif(autrePosteMemeRegion, "PN-2024-00434", Role.AGENT);

        mockMvc.perform(patch("/api/v1/agents/" + agentCible.getId())
                        .header("Authorization", "Bearer " + tokenAdminRegional)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(modifierAgentJson("Nouveau Nom", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nom").value("Nouveau Nom"));
    }

    @Test
    void modifier_commeAdminRegional_agentHorsRegion_shouldRetourner403() throws Exception {
        Region regionA = creerRegion("Region A");
        Region regionB = creerRegion("Region B");
        Poste posteAdmin = creerPoste(regionA, "Poste Admin");
        Poste posteHorsRegion = creerPoste(regionB, "Poste Hors Region");
        String tokenAdminRegional = creerEtLoginToken("PN-2024-00435", Role.ADMIN_REGIONAL, posteAdmin);
        Agent agentCible = creerAgentActif(posteHorsRegion, "PN-2024-00436", Role.AGENT);

        mockMvc.perform(patch("/api/v1/agents/" + agentCible.getId())
                        .header("Authorization", "Bearer " + tokenAdminRegional)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(modifierAgentJson("Nouveau Nom", null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void modifier_commeAdminRegional_versPosteHorsRegion_shouldRetourner403() throws Exception {
        Region regionA = creerRegion("Region A");
        Region regionB = creerRegion("Region B");
        Poste posteAdmin = creerPoste(regionA, "Poste Admin");
        Poste posteHorsRegion = creerPoste(regionB, "Poste Hors Region");
        String tokenAdminRegional = creerEtLoginToken("PN-2024-00437", Role.ADMIN_REGIONAL, posteAdmin);
        Agent agentCible = creerAgentActif(posteAdmin, "PN-2024-00438", Role.AGENT);

        mockMvc.perform(patch("/api/v1/agents/" + agentCible.getId())
                        .header("Authorization", "Bearer " + tokenAdminRegional)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(modifierAgentJson(null, posteHorsRegion.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void modifier_commeAgent_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        String tokenAgent = creerEtLoginToken("PN-2024-00439", Role.AGENT, poste);

        mockMvc.perform(patch("/api/v1/agents/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + tokenAgent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(modifierAgentJson("Nouveau Nom", null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void modifier_commeChefPoste_avecIdInconnu_shouldRetourner404() throws Exception {
        Poste poste = creerPoste();
        String tokenChef = creerEtLoginToken("PN-2024-00440", Role.CHEF_POSTE, poste);

        mockMvc.perform(patch("/api/v1/agents/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + tokenChef)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(modifierAgentJson("Nouveau Nom", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AGENT_INTROUVABLE"));
    }

    @Test
    void desactiver_commeChefPoste_agentDeSonPoste_shouldRetourner204() throws Exception {
        Poste poste = creerPoste();
        String tokenChef = creerEtLoginToken("PN-2024-00441", Role.CHEF_POSTE, poste);
        Agent agentCible = creerAgentActif(poste, "PN-2024-00442", Role.AGENT);

        mockMvc.perform(delete("/api/v1/agents/" + agentCible.getId())
                        .header("Authorization", "Bearer " + tokenChef))
                .andExpect(status().isNoContent());
    }

    @Test
    void desactiver_commeChefPoste_agentDAutrePoste_shouldRetourner403() throws Exception {
        Region region = creerRegion("Dakar");
        Poste posteChef = creerPoste(region, "Poste Chef");
        Poste autrePoste = creerPoste(region, "Autre Poste");
        String tokenChef = creerEtLoginToken("PN-2024-00443", Role.CHEF_POSTE, posteChef);
        Agent agentCible = creerAgentActif(autrePoste, "PN-2024-00444", Role.AGENT);

        mockMvc.perform(delete("/api/v1/agents/" + agentCible.getId())
                        .header("Authorization", "Bearer " + tokenChef))
                .andExpect(status().isForbidden());
    }

    @Test
    void desactiver_commeAdminRegional_agentDeSaRegion_shouldRetourner204() throws Exception {
        Region region = creerRegion("Dakar");
        Poste posteAdmin = creerPoste(region, "Poste Admin");
        Poste autrePosteMemeRegion = creerPoste(region, "Autre Poste Meme Region");
        String tokenAdminRegional = creerEtLoginToken("PN-2024-00445", Role.ADMIN_REGIONAL, posteAdmin);
        Agent agentCible = creerAgentActif(autrePosteMemeRegion, "PN-2024-00446", Role.AGENT);

        mockMvc.perform(delete("/api/v1/agents/" + agentCible.getId())
                        .header("Authorization", "Bearer " + tokenAdminRegional))
                .andExpect(status().isNoContent());
    }

    @Test
    void desactiver_commeAdminRegional_agentHorsRegion_shouldRetourner403() throws Exception {
        Region regionA = creerRegion("Region A");
        Region regionB = creerRegion("Region B");
        Poste posteAdmin = creerPoste(regionA, "Poste Admin");
        Poste posteHorsRegion = creerPoste(regionB, "Poste Hors Region");
        String tokenAdminRegional = creerEtLoginToken("PN-2024-00447", Role.ADMIN_REGIONAL, posteAdmin);
        Agent agentCible = creerAgentActif(posteHorsRegion, "PN-2024-00448", Role.AGENT);

        mockMvc.perform(delete("/api/v1/agents/" + agentCible.getId())
                        .header("Authorization", "Bearer " + tokenAdminRegional))
                .andExpect(status().isForbidden());
    }

    @Test
    void desactiver_commeAuditeur_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        String tokenAuditeur = creerEtLoginToken("PN-2024-00449", Role.AUDITEUR, poste);

        mockMvc.perform(delete("/api/v1/agents/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + tokenAuditeur))
                .andExpect(status().isForbidden());
    }

    @Test
    void desactiver_commeChefPoste_avecIdInconnu_shouldRetourner404() throws Exception {
        Poste poste = creerPoste();
        String tokenChef = creerEtLoginToken("PN-2024-00450", Role.CHEF_POSTE, poste);

        mockMvc.perform(delete("/api/v1/agents/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + tokenChef))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AGENT_INTROUVABLE"));
    }

    @Test
    void lister_commeChefPosteDesactiveApresEmissionDuToken_shouldRetourner403AccesRefuse() throws Exception {
        Poste poste = creerPoste();
        String tokenAdmin = creerEtLoginToken("PN-2024-00451", Role.ADMIN_NATIONAL, poste);
        Agent chef = creerAgentActif(poste, "PN-2024-00452", Role.CHEF_POSTE);
        String tokenChef = login("PN-2024-00452", MOT_DE_PASSE_CLAIR);

        mockMvc.perform(delete("/api/v1/agents/" + chef.getId())
                        .header("Authorization", "Bearer " + tokenAdmin))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/agents")
                        .header("Authorization", "Bearer " + tokenChef))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCES_REFUSE"));
    }

    @Test
    void listerPostes_commeAgent_shouldRetourner200() throws Exception {
        Poste poste = creerPoste();
        String tokenAgent = creerEtLoginToken("PN-2024-00453", Role.AGENT, poste);

        mockMvc.perform(get("/api/v1/postes")
                        .header("Authorization", "Bearer " + tokenAgent))
                .andExpect(status().isOk());
    }

    @Test
    void listerPostes_commeAuditeur_shouldRetourner200() throws Exception {
        Poste poste = creerPoste();
        String tokenAuditeur = creerEtLoginToken("PN-2024-00454", Role.AUDITEUR, poste);

        mockMvc.perform(get("/api/v1/postes")
                        .header("Authorization", "Bearer " + tokenAuditeur))
                .andExpect(status().isOk());
    }
}
