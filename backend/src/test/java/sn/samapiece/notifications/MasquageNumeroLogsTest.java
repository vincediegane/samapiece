package sn.samapiece.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MasquageNumeroLogsTest {

    private static final String API_ENDPOINT = "http://passerelle-sms-test";
    private static final String NUMERO_CLAIR = "+221771234567";
    private static final String SOUS_CHAINE_A_NE_JAMAIS_LOGGUER = "771234";

    private Logger loggerNotifications;
    private ListAppender<ILoggingEvent> appender;
    private RabbitTemplate rabbitTemplate;
    private SmsProperties proprietes;

    @BeforeEach
    void initialiser() {
        loggerNotifications = (Logger) LoggerFactory.getLogger("sn.samapiece.notifications");
        appender = new ListAppender<>();
        appender.start();
        loggerNotifications.addAppender(appender);

        rabbitTemplate = mock(RabbitTemplate.class);
        proprietes = new SmsProperties();
        proprietes.setMaxTentatives(3);
    }

    @AfterEach
    void nettoyer() {
        loggerNotifications.detachAppender(appender);
    }

    private PasserelleSmsHttpClient nouveauClientLie(MockRestServiceServer[] serveurSortie) {
        RestClient.Builder builder = RestClient.builder().baseUrl(API_ENDPOINT);
        serveurSortie[0] = MockRestServiceServer.bindTo(builder).build();
        return new PasserelleSmsHttpClient(builder.build(), rabbitTemplate, "SamaPiece");
    }

    private void assertAucunLogNeContientLeNumeroEnClair() {
        for (ILoggingEvent evenement : appender.list) {
            assertThat(evenement.getFormattedMessage()).doesNotContain(SOUS_CHAINE_A_NE_JAMAIS_LOGGUER);
            if (evenement.getThrowableProxy() != null) {
                assertThat(evenement.getThrowableProxy().getMessage())
                        .as("message de l'exception loguee")
                        .doesNotContain(SOUS_CHAINE_A_NE_JAMAIS_LOGGUER);
            }
        }
        assertThat(appender.list).isNotEmpty();
    }

    @Test
    void envoiEnEchec_neDevraitJamaisLogguerLeNumeroEnClair() {
        MockRestServiceServer[] serveur = new MockRestServiceServer[1];
        PasserelleSmsHttpClient client = nouveauClientLie(serveur);
        serveur[0].expect(requestTo(API_ENDPOINT)).andRespond(withServerError());

        client.envoyer(NumeroTelephone.de(NUMERO_CLAIR), "Contenu du message SMS");

        assertAucunLogNeContientLeNumeroEnClair();
    }

    @Test
    void listenerConsommerAvecEchec_neDevraitJamaisLogguerLeNumeroEnClair() {
        MockRestServiceServer[] serveur = new MockRestServiceServer[1];
        PasserelleSmsHttpClient client = nouveauClientLie(serveur);
        serveur[0].expect(requestTo(API_ENDPOINT)).andRespond(withServerError());
        SmsRetryListener listener = new SmsRetryListener(client, rabbitTemplate, proprietes);

        listener.consommer(new SmsRetryMessage(NUMERO_CLAIR, "Contenu du message SMS", 1));

        assertAucunLogNeContientLeNumeroEnClair();
    }

    @Test
    void listenerConsommerAvecSucces_neDevraitJamaisLogguerLeNumeroEnClair() {
        MockRestServiceServer[] serveur = new MockRestServiceServer[1];
        PasserelleSmsHttpClient client = nouveauClientLie(serveur);
        serveur[0].expect(requestTo(API_ENDPOINT)).andRespond(withSuccess());
        SmsRetryListener listener = new SmsRetryListener(client, rabbitTemplate, proprietes);

        listener.consommer(new SmsRetryMessage(NUMERO_CLAIR, "Contenu du message SMS", 1));

        assertAucunLogNeContientLeNumeroEnClair();
    }

    @Test
    void listenerConsommerAuDelaDuMaxTentatives_neDevraitJamaisLogguerLeNumeroEnClair() {
        MockRestServiceServer[] serveur = new MockRestServiceServer[1];
        PasserelleSmsHttpClient client = nouveauClientLie(serveur);
        serveur[0].expect(requestTo(API_ENDPOINT)).andRespond(withServerError());
        SmsRetryListener listener = new SmsRetryListener(client, rabbitTemplate, proprietes);

        listener.consommer(new SmsRetryMessage(NUMERO_CLAIR, "Contenu du message SMS", 3));

        assertAucunLogNeContientLeNumeroEnClair();
        List<ILoggingEvent> evenementsErreur = appender.list;
        assertThat(evenementsErreur)
                .anyMatch(evenement -> evenement.getFormattedMessage().contains("dead-letter"));
    }
}
