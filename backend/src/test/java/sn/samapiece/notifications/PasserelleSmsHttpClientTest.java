package sn.samapiece.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PasserelleSmsHttpClientTest {

    private static final String API_ENDPOINT = "http://passerelle-sms-test";
    private static final String API_KEY = "cle-api-de-test";

    private RabbitTemplate rabbitTemplate;
    private MockRestServiceServer serveurMock;
    private PasserelleSmsHttpClient client;

    @BeforeEach
    void initialiser() {
        rabbitTemplate = mock(RabbitTemplate.class);

        RestClient.Builder restClientBuilder =
                RestClient.builder().baseUrl(API_ENDPOINT).defaultHeader("Authorization", "Bearer " + API_KEY);
        serveurMock = MockRestServiceServer.bindTo(restClientBuilder).build();
        client = new PasserelleSmsHttpClient(restClientBuilder.build(), rabbitTemplate, "SamaPiece");
    }

    @Test
    void envoyer_avecReponse200_neDoitPasPublierSurRabbit() {
        serveurMock
                .expect(requestTo(API_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content()
                        .json(
                                """
                                {"destinataire":"+221771234567","message":"Contenu du message SMS","expediteur":"SamaPiece"}
                                """))
                .andRespond(withSuccess());

        client.envoyer(NumeroTelephone.de("+221771234567"), "Contenu du message SMS");

        serveurMock.verify();
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void envoyer_avecReponse500_doitPublierSurRabbitPourRetry() {
        serveurMock.expect(requestTo(API_ENDPOINT)).andRespond(withServerError());

        client.envoyer(NumeroTelephone.de("+221771234567"), "Contenu du message SMS");

        serveurMock.verify();
        verify(rabbitTemplate)
                .convertAndSend(
                        SmsRabbitConfig.EXCHANGE,
                        SmsRabbitConfig.QUEUE_RETRY_30S,
                        new SmsRetryMessage("+221771234567", "Contenu du message SMS", 1));
    }

    @Test
    void envoyer_avecDestinataireNull_devraitLeverIllegalArgumentException() {
        assertThatThrownBy(() -> client.envoyer(null, "Contenu du message SMS"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void envoyer_avecMessageVide_devraitLeverIllegalArgumentException() {
        NumeroTelephone destinataire = NumeroTelephone.de("+221771234567");

        assertThatThrownBy(() -> client.envoyer(destinataire, "")).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void envoyer_avecMessageNull_devraitLeverIllegalArgumentException() {
        NumeroTelephone destinataire = NumeroTelephone.de("+221771234567");

        assertThatThrownBy(() -> client.envoyer(destinataire, null)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void tenterEnvoiDirect_avecReponse200_devraitRenvoyerVrai() {
        serveurMock.expect(requestTo(API_ENDPOINT)).andRespond(withSuccess());

        boolean succes = client.tenterEnvoiDirect(NumeroTelephone.de("+221771234567"), "Contenu du message SMS");

        assertThat(succes).isTrue();
    }

    @Test
    void tenterEnvoiDirect_avecReponse500_devraitRenvoyerFaux() {
        serveurMock.expect(requestTo(API_ENDPOINT)).andRespond(withServerError());

        boolean succes = client.tenterEnvoiDirect(NumeroTelephone.de("+221771234567"), "Contenu du message SMS");

        assertThat(succes).isFalse();
    }
}
