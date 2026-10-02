package sn.samapiece.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Implementation {@link PasserelleSms} : appel HTTP generique vers la passerelle SMS
 * configuree (endpoint + cle API). Leve {@link EnvoiSmsException} sur echec (timeout, erreur
 * reseau, reponse non-2xx) ; le retry est la responsabilite de l'appelant.
 */
@Service
public class PasserelleSmsHttpClient implements PasserelleSms {

    private static final Logger LOG = LoggerFactory.getLogger(PasserelleSmsHttpClient.class);

    private final RestClient restClient;
    private final String senderId;

    @Autowired
    public PasserelleSmsHttpClient(
            RestClient.Builder restClientBuilder, SmsProperties proprietes) {
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
    PasserelleSmsHttpClient(RestClient restClient, String senderId) {
        this.restClient = restClient;
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
        try {
            restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new RequeteSms(destinataire.valeurBrute(), message, senderId))
                    .retrieve()
                    .toBodilessEntity();
            LOG.info("Envoi SMS reussi pour {}", destinataire);
        } catch (RestClientException exception) {
            LOG.warn("Echec de l'appel HTTP vers la passerelle SMS pour {}", destinataire, exception);
            throw new EnvoiSmsException(destinataire, exception);
        }
    }

    private record RequeteSms(String destinataire, String message, String expediteur) {}
}
