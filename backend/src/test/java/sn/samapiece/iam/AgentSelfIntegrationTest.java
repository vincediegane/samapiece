package sn.samapiece.iam;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
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
import sn.samapiece.iam.web.ChangerMotDePasseRequest;
import sn.samapiece.iam.web.LoginRequest;
import sn.samapiece.referentiel.Poste;
import sn.samapiece.referentiel.PosteRepository;
import sn.samapiece.referentiel.Region;
import sn.samapiece.referentiel.RegionRepository;
import sn.samapiece.referentiel.TypePoste;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AgentSelfIntegrationTest {

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

    private Agent creerAgentAvecDoitChangerMotDePasse(Poste poste, String matricule, Role role) {
        return agentRepository.save(new Agent(
                poste, matricule, "Diop Awa", role, passwordEncoder.encode(MOT_DE_PASSE_CLAIR), true));
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

    @Test
    void moi_commeAgentActif_shouldRetourner200AvecPosteIdEtPosteNom() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00700", Role.AGENT, poste);

        mockMvc.perform(get("/api/v1/agents/moi")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matricule").value("PN-2024-00700"))
                .andExpect(jsonPath("$.posteId").value(poste.getId().toString()))
                .andExpect(jsonPath("$.posteNom").value(poste.getNom()));
    }

    @Test
    void moi_commeAuditeur_shouldRetourner200() throws Exception {
        Poste poste = creerPoste();
        String token = creerEtLoginToken("PN-2024-00701", Role.AUDITEUR, poste);

        mockMvc.perform(get("/api/v1/agents/moi")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void moi_sansToken_shouldRetourner401() throws Exception {
        mockMvc.perform(get("/api/v1/agents/moi"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void moi_commeAgentDesactiveApresEmissionDuToken_shouldRetourner403AccesRefuse() throws Exception {
        Poste poste = creerPoste();
        String tokenAdmin = creerEtLoginToken("PN-2024-00702", Role.ADMIN_NATIONAL, poste);
        Agent agent = creerAgentActif(poste, "PN-2024-00703", Role.AGENT);
        String token = login("PN-2024-00703", MOT_DE_PASSE_CLAIR);

        mockMvc.perform(delete("/api/v1/agents/" + agent.getId())
                        .header("Authorization", "Bearer " + tokenAdmin))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/agents/moi")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCES_REFUSE"));
    }

    @Test
    void premiereConnexionAdminBootstrap_avantChangementMotDePasse_shouldRetourner403SurRoutesProtegees()
            throws Exception {
        Poste poste = creerPoste();
        creerAgentAvecDoitChangerMotDePasse(poste, "PN-2024-00800", Role.ADMIN_NATIONAL);
        String token = login("PN-2024-00800", MOT_DE_PASSE_CLAIR);

        mockMvc.perform(get("/api/v1/agents")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MOT_DE_PASSE_TEMPORAIRE_NON_CHANGE"));
    }

    @Test
    void moi_avantChangementMotDePasse_shouldRetourner200() throws Exception {
        Poste poste = creerPoste();
        creerAgentAvecDoitChangerMotDePasse(poste, "PN-2024-00801", Role.ADMIN_NATIONAL);
        String token = login("PN-2024-00801", MOT_DE_PASSE_CLAIR);

        mockMvc.perform(get("/api/v1/agents/moi")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void changerMotDePasse_avecMotDePasseActuelValide_shouldRetourner200EtLeverEnforcement() throws Exception {
        Poste poste = creerPoste();
        creerAgentAvecDoitChangerMotDePasse(poste, "PN-2024-00802", Role.ADMIN_NATIONAL);
        String token = login("PN-2024-00802", MOT_DE_PASSE_CLAIR);

        String corps = OBJECT_MAPPER.writeValueAsString(
                new ChangerMotDePasseRequest(MOT_DE_PASSE_CLAIR, "NouveauMotDePasse456!"));
        String reponse = mockMvc.perform(put("/api/v1/agents/moi/mot-de-passe")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        String nouveauAccessToken = OBJECT_MAPPER.readTree(reponse).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/agents")
                        .header("Authorization", "Bearer " + nouveauAccessToken))
                .andExpect(status().isOk());
    }

    @Test
    void changerMotDePasse_avecMotDePasseActuelInvalide_shouldRetourner400() throws Exception {
        Poste poste = creerPoste();
        creerAgentAvecDoitChangerMotDePasse(poste, "PN-2024-00803", Role.ADMIN_NATIONAL);
        String token = login("PN-2024-00803", MOT_DE_PASSE_CLAIR);

        String corps = OBJECT_MAPPER.writeValueAsString(
                new ChangerMotDePasseRequest("MauvaisMotDePasse!", "NouveauMotDePasse456!"));

        mockMvc.perform(put("/api/v1/agents/moi/mot-de-passe")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MOT_DE_PASSE_ACTUEL_INVALIDE"));
    }
}
