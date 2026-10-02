package sn.samapiece.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MasquageNumeroLogsTest {

    private static final String API_ENDPOINT = "http://passerelle-sms-test";
    private static final String NUMERO_CLAIR = "+221771234567";
    private static final String SOUS_CHAINE_A_NE_JAMAIS_LOGGUER = "771234";

    private Logger loggerNotifications;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void initialiser() {
        loggerNotifications = (Logger) LoggerFactory.getLogger("sn.samapiece.notifications");
        appender = new ListAppender<>();
        appender.start();
        loggerNotifications.addAppender(appender);

    }

    @AfterEach
    void nettoyer() {
        loggerNotifications.detachAppender(appender);
    }

    private PasserelleSmsHttpClient nouveauClientLie(MockRestServiceServer[] serveurSortie) {
        RestClient.Builder builder = RestClient.builder().baseUrl(API_ENDPOINT);
        serveurSortie[0] = MockRestServiceServer.bindTo(builder).build();
        return new PasserelleSmsHttpClient(builder.build(), "SamaPiece");
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

        assertThatThrownBy(() -> client.envoyer(NumeroTelephone.de(NUMERO_CLAIR), "Contenu du message SMS"))
                .isInstanceOf(EnvoiSmsException.class)
                .hasMessageNotContaining(SOUS_CHAINE_A_NE_JAMAIS_LOGGUER);

        assertAucunLogNeContientLeNumeroEnClair();
    }

    @Test
    void envoiReussi_neDevraitJamaisLogguerLeNumeroEnClair() {
        MockRestServiceServer[] serveur = new MockRestServiceServer[1];
        PasserelleSmsHttpClient client = nouveauClientLie(serveur);
        serveur[0].expect(requestTo(API_ENDPOINT)).andRespond(withSuccess());

        client.envoyer(NumeroTelephone.de(NUMERO_CLAIR), "Contenu du message SMS");

        assertAucunLogNeContientLeNumeroEnClair();
    }
}
