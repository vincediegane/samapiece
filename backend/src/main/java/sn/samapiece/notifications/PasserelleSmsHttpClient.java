package sn.samapiece.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Implementation {@link PasserelleSms} : appel HTTP generique vers la passerelle SMS
 * configuree (endpoint + cle API), avec tentative directe puis mise en file de retry
 * technique sur echec (timeout, erreur reseau, reponse non-2xx).
 */
@Service
public class PasserelleSmsHttpClient implements PasserelleSms {

    private static final Logger LOG = LoggerFactory.getLogger(PasserelleSmsHttpClient.class);

    private final RestClient restClient;
    private final RabbitTemplate rabbitTemplate;
    private final String senderId;

    public PasserelleSmsHttpClient(
            RestClient.Builder restClientBuilder, RabbitTemplate rabbitTemplate, SmsProperties proprietes) {
        this.rabbitTemplate = rabbitTemplate;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(proprietes.getTimeoutMs());
        requestFactory.setReadTimeout(proprietes.getTimeoutMs());
        this.restClient = restClientBuilder
                .baseUrl(proprietes.getApiEndpoint())
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + proprietes.getApiKey())
                .build();
        this.senderId = proprietes.getSenderId();
    }

    /**
     * Reserve aux tests : injecte un {@link RestClient} deja construit (ex. lie a un
     * {@code MockRestServiceServer}), pour eviter que la configuration du timeout via
     * {@code requestFactory(...)} du constructeur public n'ecrase la factory de test partageant
     * le meme {@link RestClient.Builder}.
     */
    PasserelleSmsHttpClient(RestClient restClient, RabbitTemplate rabbitTemplate, String senderId) {
        this.restClient = restClient;
        this.rabbitTemplate = rabbitTemplate;
        this.senderId = senderId;
    }

    @Override
    public void envoyer(NumeroTelephone destinataire, String message) {
        if (destinataire == null) {
            throw new IllegalArgumentException("Le destinataire ne peut pas etre null.");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Le message ne peut pas etre vide.");
        }
        if (tenterEnvoiDirect(destinataire, message)) {
            return;
        }
        rabbitTemplate.convertAndSend(
                SmsRabbitConfig.EXCHANGE,
                SmsRabbitConfig.QUEUE_RETRY_30S,
                new SmsRetryMessage(destinataire.valeurBrute(), message, 1));
    }

    /** Package-private : reutilise par SmsRetryListener pour retenter sans repartir de l'etage 1. */
    boolean tenterEnvoiDirect(NumeroTelephone destinataire, String message) {
        try {
            restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new RequeteSms(destinataire.valeurBrute(), message, senderId))
                    .retrieve()
                    .toBodilessEntity();
            LOG.info("Envoi SMS reussi pour {}", destinataire);
            return true;
        } catch (RestClientException exception) {
            LOG.warn("Echec de l'appel HTTP vers la passerelle SMS pour {}", destinataire, exception);
            return false;
        }
    }

    private record RequeteSms(String destinataire, String message, String expediteur) {}
}
