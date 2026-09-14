package sn.samapiece.alertes.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import sn.samapiece.alertes.AlerteDesinscriptionTokenRepository;
import sn.samapiece.alertes.AlerteRepository;
import sn.samapiece.alertes.FakePasserelleSms;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AlerteIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CONTACT_CLAIR = "+221771234567";
    private static final String NUMERO_DOCUMENT = "1234567890123";

    @TestConfiguration
    static class PasserelleSmsTestConfiguration {
        @Bean
        @Primary
        FakePasserelleSms fakePasserelleSms() {
            return new FakePasserelleSms();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AlerteRepository alerteRepository;

    @Autowired
    private AlerteDesinscriptionTokenRepository tokenRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FakePasserelleSms passerelleSms;

    @BeforeEach
    void nettoyer() {
        tokenRepository.deleteAll();
        alerteRepository.deleteAll();
        passerelleSms.envois.clear();
    }

    private String creerAlerteJson(
            String typeDocument, String nomTitulaire, String numeroDocument, String contact) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"typeDocument\":").append(typeDocument == null ? "null" : "\"" + typeDocument + "\"").append(',');
        json.append("\"nomTitulaire\":").append(nomTitulaire == null ? "null" : "\"" + nomTitulaire + "\"").append(',');
        json.append("\"prenomTitulaire\":\"Moussa\",");
        json.append("\"numeroDocument\":").append(numeroDocument == null ? "null" : "\"" + numeroDocument + "\"").append(',');
        json.append("\"dateNaissanceTitulaire\":null,");
        json.append("\"contact\":").append(contact == null ? "null" : "\"" + contact + "\"");
        json.append('}');
        return json.toString();
    }

    private String extraireTokenDuMessage(String message) {
        int index = message.indexOf("token=");
        return message.substring(index + "token=".length());
    }

    @Test
    void creerAlerte_avecCriteresSuffisants_devraitEnregistrerEtEnvoyerUnSms() throws Exception {
        String reponse = mockMvc.perform(post("/api/v1/alertes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerAlerteJson("CNI", "Fall", NUMERO_DOCUMENT, CONTACT_CLAIR)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode corps = OBJECT_MAPPER.readTree(reponse);

        assertThat(corps.get("message").asText())
                .isEqualTo("Alerte enregistrée. Un lien de désinscription a été envoyé par SMS.");
        assertThat(corps.fieldNames()).toIterable().containsExactly("message");

        assertThat(alerteRepository.findAll()).hasSize(1);
        byte[] contactChiffre = jdbcTemplate.queryForObject(
                "SELECT contact_chiffre FROM alerte", byte[].class);
        assertThat(contactChiffre).isNotNull();
        assertThat(contactChiffre).isNotEqualTo(CONTACT_CLAIR.getBytes(StandardCharsets.UTF_8));
        assertThat(new String(contactChiffre, StandardCharsets.UTF_8)).doesNotContain(CONTACT_CLAIR);
        boolean actif = jdbcTemplate.queryForObject("SELECT active FROM alerte", Boolean.class);
        assertThat(actif).isTrue();

        assertThat(passerelleSms.envois).hasSize(1);
        assertThat(passerelleSms.dernierEnvoi().message()).contains("?token=");
    }

    @Test
    void creerAlerte_avecCriteresInsuffisants_devraitRenvoyer400() throws Exception {
        String reponse = mockMvc.perform(post("/api/v1/alertes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerAlerteJson(null, "Fall", null, CONTACT_CLAIR)))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode corps = OBJECT_MAPPER.readTree(reponse);

        assertThat(corps.get("code").asText()).isEqualTo("CRITERES_INSUFFISANTS");
    }

    @Test
    void creerAlerte_avecContactVide_devraitRenvoyer400ContactInvalide() throws Exception {
        String reponse = mockMvc.perform(post("/api/v1/alertes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerAlerteJson("CNI", "Fall", NUMERO_DOCUMENT, null)))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode corps = OBJECT_MAPPER.readTree(reponse);

        assertThat(corps.get("code").asText()).isEqualTo("CONTACT_INVALIDE");
    }

    @Test
    void desinscrire_avecJetonValide_devraitDesactiverEtEffacerLeContact() throws Exception {
        mockMvc.perform(post("/api/v1/alertes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerAlerteJson("CNI", "Fall", NUMERO_DOCUMENT, CONTACT_CLAIR)))
                .andExpect(status().isCreated());
        String tokenBrut = extraireTokenDuMessage(passerelleSms.dernierEnvoi().message());

        mockMvc.perform(delete("/api/v1/alertes/{id}", tokenBrut))
                .andExpect(status().isNoContent());

        boolean actif = jdbcTemplate.queryForObject("SELECT active FROM alerte", Boolean.class);
        assertThat(actif).isFalse();
        byte[] contactChiffre = jdbcTemplate.queryForObject(
                "SELECT contact_chiffre FROM alerte", byte[].class);
        assertThat(contactChiffre).isNull();
        String contactIv = jdbcTemplate.queryForObject("SELECT contact_iv FROM alerte", String.class);
        assertThat(contactIv).isNull();
        Object consommeLe = jdbcTemplate.queryForObject(
                "SELECT consomme_le FROM alerte_desinscription_token", Object.class);
        assertThat(consommeLe).isNotNull();
    }

    @Test
    void desinscrire_avecJetonDejaConsomme_devraitRenvoyer404() throws Exception {
        mockMvc.perform(post("/api/v1/alertes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerAlerteJson("CNI", "Fall", NUMERO_DOCUMENT, CONTACT_CLAIR)))
                .andExpect(status().isCreated());
        String tokenBrut = extraireTokenDuMessage(passerelleSms.dernierEnvoi().message());
        mockMvc.perform(delete("/api/v1/alertes/{id}", tokenBrut)).andExpect(status().isNoContent());

        String reponse = mockMvc.perform(delete("/api/v1/alertes/{id}", tokenBrut))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode corps = OBJECT_MAPPER.readTree(reponse);

        assertThat(corps.get("code").asText()).isEqualTo("JETON_INTROUVABLE");
    }

    @Test
    void desinscrire_avecJetonInconnu_devraitRenvoyer404() throws Exception {
        String reponse = mockMvc.perform(delete("/api/v1/alertes/{id}", "un-jeton-qui-nexiste-pas"))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode corps = OBJECT_MAPPER.readTree(reponse);

        assertThat(corps.get("code").asText()).isEqualTo("JETON_INTROUVABLE");
    }
}
