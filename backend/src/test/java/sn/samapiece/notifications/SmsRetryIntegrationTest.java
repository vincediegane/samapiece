package sn.samapiece.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Test d'integration bout-en-bout du retry technique de la passerelle SMS : vrai broker
 * RabbitMQ (Testcontainers) et serveur HTTP factice (JDK) simulant la passerelle SMS. Les TTL
 * de backoff sont reduits a ~200 ms via {@link DynamicPropertySource} pour un test rapide.
 */
@SpringBootTest
@Testcontainers
class SmsRetryIntegrationTest {

    private static final String NUMERO_TOUJOURS_EN_ECHEC = "+221770000001";
    private static final String NUMERO_ECHEC_PUIS_SUCCES = "+221770000002";

    private static final ConcurrentHashMap<String, AtomicInteger> COMPTEUR_APPELS_PAR_NUMERO =
            new ConcurrentHashMap<>();

    private static HttpServer serveurSmsFactice;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitmq = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management-alpine"));

    @DynamicPropertySource
    static void proprietesSms(DynamicPropertyRegistry registry) throws IOException {
        serveurSmsFactice = demarrerServeurSmsFactice();
        int port = serveurSmsFactice.getAddress().getPort();

        registry.add("samapiece.sms.api-endpoint", () -> "http://localhost:" + port);
        registry.add("samapiece.sms.api-key", () -> "peu-importe");
        registry.add("samapiece.sms.sender-id", () -> "SamaPiece");
        registry.add("samapiece.sms.timeout-ms", () -> "2000");
        registry.add("samapiece.sms.max-tentatives", () -> "3");
        registry.add("samapiece.sms.retry-ttl-30s-ms", () -> "200");
        registry.add("samapiece.sms.retry-ttl-2m-ms", () -> "200");
        registry.add("samapiece.sms.retry-ttl-10m-ms", () -> "200");
    }

    @AfterAll
    static void arreterServeurFactice() {
        if (serveurSmsFactice != null) {
            serveurSmsFactice.stop(0);
        }
    }

    private static HttpServer demarrerServeurSmsFactice() throws IOException {
        HttpServer serveur = HttpServer.create(new InetSocketAddress(0), 0);
        ObjectMapper objectMapper = new ObjectMapper();
        serveur.createContext("/", echange -> {
            byte[] corps = echange.getRequestBody().readAllBytes();
            JsonNode json = objectMapper.readTree(corps);
            String destinataire = json.get("destinataire").asText();
            int numeroAppel = COMPTEUR_APPELS_PAR_NUMERO
                    .computeIfAbsent(destinataire, cle -> new AtomicInteger(0))
                    .incrementAndGet();

            int statut;
            if (NUMERO_TOUJOURS_EN_ECHEC.equals(destinataire)) {
                statut = 500;
            } else if (NUMERO_ECHEC_PUIS_SUCCES.equals(destinataire)) {
                statut = numeroAppel == 1 ? 500 : 200;
            } else {
                statut = 200;
            }
            echange.sendResponseHeaders(statut, -1);
            echange.close();
        });
        serveur.start();
        return serveur;
    }

    @Autowired
    private PasserelleSms passerelleSms;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @BeforeEach
    void nettoyerLesFiles() {
        amqpAdmin.purgeQueue(SmsRabbitConfig.QUEUE_RETRY_30S, true);
        amqpAdmin.purgeQueue(SmsRabbitConfig.QUEUE_RETRY_2M, true);
        amqpAdmin.purgeQueue(SmsRabbitConfig.QUEUE_RETRY_10M, true);
        amqpAdmin.purgeQueue(SmsRabbitConfig.QUEUE_CONSUME, true);
        amqpAdmin.purgeQueue(SmsRabbitConfig.QUEUE_DEAD_LETTER, true);
    }

    @Test
    void numeroTouoursEnEchec_devraitFinirEnDeadLetterApresMaxTentatives() {
        passerelleSms.envoyer(NumeroTelephone.de(NUMERO_TOUJOURS_EN_ECHEC), "Contenu du message SMS");

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Object messageRecu = rabbitTemplate.receiveAndConvert(SmsRabbitConfig.QUEUE_DEAD_LETTER, 500);
            assertThat(messageRecu).isInstanceOf(SmsRetryMessage.class);
            SmsRetryMessage messageRetry = (SmsRetryMessage) messageRecu;
            assertThat(messageRetry.destinataire()).isEqualTo(NUMERO_TOUJOURS_EN_ECHEC);
            assertThat(messageRetry.nombreTentatives()).isEqualTo(4);
        });
    }

    @Test
    void numeroEnEchecPuisSucces_neDoitJamaisAtteindreLaDeadLetter() {
        passerelleSms.envoyer(NumeroTelephone.de(NUMERO_ECHEC_PUIS_SUCCES), "Contenu du message SMS");

        Awaitility.await()
                .pollDelay(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    assertThat(rabbitTemplate.receiveAndConvert(SmsRabbitConfig.QUEUE_DEAD_LETTER, 200))
                            .isNull();
                    assertThat(COMPTEUR_APPELS_PAR_NUMERO.get(NUMERO_ECHEC_PUIS_SUCCES))
                            .hasValue(2);
                });
    }
}
