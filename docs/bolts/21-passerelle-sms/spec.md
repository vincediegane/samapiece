# Spec — Ticket #21 : Intégration passerelle SMS (Orange/Free)

## Résumé

Livrer le module `sn.samapiece.notifications` avec un contrat stable `PasserelleSms` implémenté par un client HTTP générique configurable par variables d'environnement, un mécanisme de retry technique par file RabbitMQ à étages (TTL + DLX) sur échec de l'appel HTTP, un masquage systématique du numéro dans tous les logs, et les tests unitaires/intégration correspondants.

## Tâches

- [ ] `backend/pom.xml` — ajouter `spring-boot-starter-amqp` (dépendances principales) et `org.testcontainers:rabbitmq` (scope `test`, avec `${testcontainers.version}` comme pour `org.testcontainers:minio`).
- [ ] `backend/src/main/java/sn/samapiece/notifications/PasserelleSms.java` — créer l'interface (contrat stable pour #22/#23), avec la Javadoc documentant explicitement que le retry technique est déjà géré en interne (aucun retry à ré-implémenter côté appelant).
- [ ] `backend/src/main/java/sn/samapiece/notifications/NumeroTelephone.java` — créer le value object : normalisation basique, masquage systématique via `toString()`, accès explicite à la valeur brute via `valeurBrute()`.
- [ ] `backend/src/main/java/sn/samapiece/notifications/SmsProperties.java` — créer `@ConfigurationProperties(prefix = "samapiece.sms")` sur le modèle de `PhotoMinioProperties`/`MeilisearchProperties`.
- [ ] `backend/src/main/java/sn/samapiece/notifications/SmsRabbitConfig.java` — créer la topologie RabbitMQ (exchange, 3 files de backoff, file de consommation, file dead-letter, bindings, `MessageConverter` JSON).
- [ ] `backend/src/main/java/sn/samapiece/notifications/SmsRetryMessage.java` — créer le record transporté dans la file.
- [ ] `backend/src/main/java/sn/samapiece/notifications/PasserelleSmsHttpClient.java` — créer l'implémentation `PasserelleSms` (appel HTTP via `RestClient`, tentative directe puis mise en file sur échec).
- [ ] `backend/src/main/java/sn/samapiece/notifications/SmsRetryListener.java` — créer le `@RabbitListener` qui retente l'envoi, republie vers l'étage suivant ou route vers la dead-letter.
- [ ] `backend/src/main/resources/application.yml` — ajouter le bloc `samapiece.sms.*` et le bloc `spring.rabbitmq.*`.
- [ ] `backend/src/main/resources/application-dev.yml` — ajouter `spring.rabbitmq.host: ${RABBITMQ_HOST:localhost}` (valeur par défaut dev, même schéma que `DB_HOST`).
- [ ] `docker-compose.yml` — ajouter au service `backend` les variables `RABBITMQ_HOST`/`RABBITMQ_PORT`/`RABBITMQ_USER`/`RABBITMQ_PASSWORD`/`SMS_API_ENDPOINT`/`SMS_API_KEY`/`SMS_SENDER_ID`/`SMS_TIMEOUT_MS`/`SMS_MAX_TENTATIVES` et `depends_on.rabbitmq.condition: service_healthy`.
- [ ] `.env.example` — ajouter `SMS_API_ENDPOINT`, `SMS_API_KEY`, `SMS_SENDER_ID`, `SMS_TIMEOUT_MS`, `SMS_MAX_TENTATIVES`.
- [ ] `backend/src/test/java/sn/samapiece/notifications/NumeroTelephoneTest.java` — créer le test unitaire de normalisation et de masquage.
- [ ] `backend/src/test/java/sn/samapiece/notifications/PasserelleSmsHttpClientTest.java` — créer le test unitaire du client HTTP (succès + échec) via `MockRestServiceServer`, avec `RabbitTemplate` mocké.
- [ ] `backend/src/test/java/sn/samapiece/notifications/MasquageNumeroLogsTest.java` — créer le test vérifiant qu'aucun message loggué ne contient le numéro en clair.
- [ ] `backend/src/test/java/sn/samapiece/notifications/SmsRetryIntegrationTest.java` — créer le test d'intégration bout-en-bout (Testcontainers RabbitMQ + serveur HTTP factice) : republication d'étage en étage, dead-letter après le nombre max de tentatives, succès au 2ᵉ essai sans dead-letter.

## Contrat technique

### `PasserelleSms` (interface)

```java
package sn.samapiece.notifications;

/**
 * Contrat stable d'envoi de SMS, destiné aux futurs modules #22 (Alertes) et #23
 * (worker métier). L'implémentation gère déjà en interne la résilience de transport
 * (timeout, erreurs 5xx, erreurs réseau) via une file RabbitMQ technique dédiée
 * (sms.retry.30s / sms.retry.2m / sms.retry.10m / sms.consume / sms.dead-letter),
 * invisible aux appelants. Un appelant ne doit PAS ré-implémenter son propre retry
 * technique par-dessus cette interface.
 *
 * envoyer(...) ne garantit pas une livraison synchrone : seulement la prise en
 * charge (tentative immédiate, ou mise en file de retry si la tentative immédiate
 * échoue).
 */
public interface PasserelleSms {

    /**
     * @throws IllegalArgumentException si destinataire est null, ou si message est
     *         null/vide (erreur de programmation de l'appelant — jamais pour un
     *         échec de transport, qui est géré en interne).
     */
    void envoyer(NumeroTelephone destinataire, String message);
}
```

### `NumeroTelephone` (value object)

Classe finale (pas de `record`, pour ne pas exposer d'accesseur brut auto-généré) :

```java
package sn.samapiece.notifications;

public final class NumeroTelephone {

    private static final java.util.regex.Pattern LOCAL_NEUF_CHIFFRES =
            java.util.regex.Pattern.compile("^\\d{9}$");

    private final String valeur;

    private NumeroTelephone(String valeur) {
        this.valeur = valeur;
    }

    /** Normalise (trim, ajout du préfixe +221 si 9 chiffres locaux sans indicatif). */
    public static NumeroTelephone de(String saisie) {
        if (saisie == null || saisie.isBlank()) {
            throw new IllegalArgumentException("Le numero de telephone ne peut pas etre vide.");
        }
        String nettoye = saisie.trim();
        if (LOCAL_NEUF_CHIFFRES.matcher(nettoye).matches()) {
            nettoye = "+221" + nettoye;
        }
        return new NumeroTelephone(nettoye);
    }

    /** Accès explicite à la valeur brute — n'appeler qu'au point d'appel HTTP réel. */
    public String valeurBrute() {
        return valeur;
    }

    @Override
    public String toString() {
        if (valeur.length() <= 6) {
            return "***";
        }
        String prefixe = valeur.substring(0, 4);
        String suffixe = valeur.substring(valeur.length() - 2);
        String masque = "X".repeat(valeur.length() - 4 - 2);
        return prefixe + masque + suffixe;
    }

    @Override
    public boolean equals(Object autre) { /* basé sur valeur */ }

    @Override
    public int hashCode() { /* basé sur valeur */ }
}
```

**Format exact du masquage** : garder les 4 premiers caractères (indicatif, ex. `+221`) et les 2 derniers caractères tels quels, remplacer tout le reste par des `X`.
Exemple : `"+221771234567"` (13 caractères) → préfixe `"+221"` + masque `"XXXXXXX"` (7 X, pour les 7 caractères d'index 4 à 10) + suffixe `"67"` → **`"+221XXXXXXX67"`**.

Règle d'implémentation à respecter dans tout le module : **tout appel de log (succès, échec, retry, dead-letter) doit passer un `NumeroTelephone` directement en argument `{}` du logger SLF4J** (jamais `numero.valeurBrute()`, jamais une concaténation de `String`, jamais le champ `destinataire` brut d'un `SmsRetryMessage` sans l'avoir d'abord enveloppé via `NumeroTelephone.de(...)`).

### `SmsProperties`

```java
package sn.samapiece.notifications;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "samapiece.sms")
public class SmsProperties {

    @NotBlank private String apiEndpoint;
    @NotBlank private String apiKey;
    @NotBlank private String senderId;
    @Positive private int timeoutMs;
    @Min(1) private int maxTentatives;
    @Positive private long retryTtl30sMs;
    @Positive private long retryTtl2mMs;
    @Positive private long retryTtl10mMs;

    // getters/setters classiques pour chaque champ
}
```

Les trois champs `retryTtlXxxMs` sont une précision de spec (non détaillée dans `design.md`) nécessaire pour rendre les étages de backoff réductibles en test via `@DynamicPropertySource` (cf. Plan de tests) — voir **Écarts identifiés**.

Bloc `application.yml` à ajouter (sous `samapiece:`, au même niveau que `jwt`/`photo`) :

```yaml
samapiece:
  sms:
    api-endpoint: ${SMS_API_ENDPOINT}
    api-key: ${SMS_API_KEY}
    sender-id: ${SMS_SENDER_ID}
    timeout-ms: ${SMS_TIMEOUT_MS:5000}
    max-tentatives: ${SMS_MAX_TENTATIVES:3}
    retry-ttl-30s-ms: 30000
    retry-ttl-2m-ms: 120000
    retry-ttl-10m-ms: 600000

spring:
  rabbitmq:
    host: ${RABBITMQ_HOST}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER}
    password: ${RABBITMQ_PASSWORD}
```

`application-dev.yml` (ajouter `rabbitmq:` sous le `spring:` existant, à côté de `datasource:`) :

```yaml
spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
```

### Topologie RabbitMQ (`SmsRabbitConfig`)

| Élément | Nom | Détail |
|---|---|---|
| Exchange | `sms.exchange` | `DirectExchange`, durable |
| Queue | `sms.retry.30s` | `x-message-ttl` = `samapiece.sms.retry-ttl-30s-ms` (défaut 30000 ms) ; `x-dead-letter-exchange` = `sms.exchange` ; `x-dead-letter-routing-key` = `sms.consume` |
| Queue | `sms.retry.2m` | `x-message-ttl` = `samapiece.sms.retry-ttl-2m-ms` (défaut 120000 ms) ; mêmes DLX/DLK que ci-dessus |
| Queue | `sms.retry.10m` | `x-message-ttl` = `samapiece.sms.retry-ttl-10m-ms` (défaut 600000 ms) ; mêmes DLX/DLK que ci-dessus |
| Queue | `sms.consume` | pas de TTL ; consommée par `SmsRetryListener` |
| Queue | `sms.dead-letter` | pas de TTL, pas de consumer applicatif ; publication explicite par `SmsRetryListener` (pas de DLX automatique) |
| Binding | `sms.exchange` → chaque queue | routing key = nom de la queue (`sms.retry.30s`, `sms.retry.2m`, `sms.retry.10m`, `sms.consume`, `sms.dead-letter`) |
| `MessageConverter` | bean unique | `Jackson2JsonMessageConverter` — utilisé automatiquement par le `RabbitTemplate` et le `SimpleRabbitListenerContainerFactory` auto-configurés par Spring Boot |

Constantes publiques à exposer dans `SmsRabbitConfig` (utilisées par `PasserelleSmsHttpClient`, `SmsRetryListener` et les tests) : `EXCHANGE`, `QUEUE_RETRY_30S`, `QUEUE_RETRY_2M`, `QUEUE_RETRY_10M`, `QUEUE_CONSUME`, `QUEUE_DEAD_LETTER` (les routing keys sont identiques aux noms de queue, pas de constantes séparées nécessaires).

Exemple de bean de queue (répéter pour les 2 autres étages avec le TTL correspondant) :

```java
@Bean
public Queue smsRetry30s(SmsProperties proprietes) {
    return QueueBuilder.durable(QUEUE_RETRY_30S)
            .ttl((int) proprietes.getRetryTtl30sMs())
            .deadLetterExchange(EXCHANGE)
            .deadLetterRoutingKey(QUEUE_CONSUME)
            .build();
}
```

### `SmsRetryMessage` (record)

```java
package sn.samapiece.notifications;

public record SmsRetryMessage(String destinataire, String message, int nombreTentatives) {}
```

`destinataire` est la valeur **brute** (`NumeroTelephone.valeurBrute()`) — nécessaire pour que `SmsRetryListener` puisse réellement rappeler la passerelle. Cela ne contrevient pas au critère d'acceptation (qui porte sur les **logs applicatifs**, pas sur le corps des messages AMQP) : `SmsRetryListener` doit reconstruire un `NumeroTelephone` via `NumeroTelephone.de(messageRetry.destinataire())` **avant tout log**, et ne jamais logger `messageRetry.destinataire()` directement.

Sémantique de `nombreTentatives` : nombre de tentatives déjà effectuées **en file** (n'inclut pas la tentative directe initiale). La valeur `1` correspond à la première republication (étage `sms.retry.30s`).

### `PasserelleSmsHttpClient`

```java
package sn.samapiece.notifications;

@Service
public class PasserelleSmsHttpClient implements PasserelleSms {

    private final RestClient restClient;
    private final RabbitTemplate rabbitTemplate;

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
        this.senderId = proprietes.getSenderId(); // stocké pour construire le corps
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

    /** Package-private : réutilisé par SmsRetryListener pour retenter sans repartir de l'étage 1. */
    boolean tenterEnvoiDirect(NumeroTelephone destinataire, String message) {
        try {
            restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new RequeteSms(destinataire.valeurBrute(), message, senderId))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Envoi SMS reussi pour {}", destinataire);
            return true;
        } catch (RestClientException exception) {
            log.warn("Echec de l'appel HTTP vers la passerelle SMS pour {}", destinataire, exception);
            return false;
        }
    }

    private record RequeteSms(String destinataire, String message, String expediteur) {}
}
```

**Payload JSON envoyé à la passerelle** (`POST {samapiece.sms.api-endpoint}`, header `Authorization: Bearer {samapiece.sms.api-key}`, `Content-Type: application/json`) :

```json
{
  "destinataire": "+221771234567",
  "message": "Contenu du message SMS",
  "expediteur": "SamaPiece"
}
```

Toute réponse 2xx = succès. Tout le reste (4xx, 5xx, timeout, erreur de connexion — toutes remontent en `RestClientException` via `RestClient`) = échec → mise en file.

### `SmsRetryListener`

```java
package sn.samapiece.notifications;

@Component
public class SmsRetryListener {

    private final PasserelleSmsHttpClient passerelleSmsHttpClient;
    private final RabbitTemplate rabbitTemplate;
    private final SmsProperties proprietes;

    @RabbitListener(queues = SmsRabbitConfig.QUEUE_CONSUME)
    public void consommer(SmsRetryMessage messageRetry) {
        NumeroTelephone destinataire = NumeroTelephone.de(messageRetry.destinataire());
        boolean succes = passerelleSmsHttpClient.tenterEnvoiDirect(destinataire, messageRetry.message());
        if (succes) {
            log.info("SMS envoye avec succes apres {} tentative(s) en file pour {}",
                    messageRetry.nombreTentatives(), destinataire);
            return;
        }
        int tentativeSuivante = messageRetry.nombreTentatives() + 1;
        if (tentativeSuivante > proprietes.getMaxTentatives()) {
            log.error("Nombre maximal de tentatives ({}) atteint pour {}, envoi vers sms.dead-letter",
                    proprietes.getMaxTentatives(), destinataire);
            rabbitTemplate.convertAndSend(SmsRabbitConfig.EXCHANGE, SmsRabbitConfig.QUEUE_DEAD_LETTER,
                    new SmsRetryMessage(messageRetry.destinataire(), messageRetry.message(), tentativeSuivante));
            return;
        }
        String routingKeySuivant = routingKeyPourTentative(tentativeSuivante);
        log.warn("Nouvel echec pour {}, republication vers {} (tentative {})",
                destinataire, routingKeySuivant, tentativeSuivante);
        rabbitTemplate.convertAndSend(SmsRabbitConfig.EXCHANGE, routingKeySuivant,
                new SmsRetryMessage(messageRetry.destinataire(), messageRetry.message(), tentativeSuivante));
    }

    private String routingKeyPourTentative(int tentative) {
        return switch (tentative) {
            case 1 -> SmsRabbitConfig.QUEUE_RETRY_30S;
            case 2 -> SmsRabbitConfig.QUEUE_RETRY_2M;
            default -> SmsRabbitConfig.QUEUE_RETRY_10M; // couvre aussi max-tentatives > 3
        };
    }
}
```

Ce mapping tolère un `samapiece.sms.max-tentatives` différent de 3 (la topologie a exactement 3 étages) : au-delà de la tentative 3, toute republication supplémentaire réutilise l'étage `sms.retry.10m` jusqu'à atteindre `max-tentatives`, puis dead-letter. Avec la valeur par défaut (`3`), le nombre total d'appels HTTP pour un numéro toujours en échec est **4** (1 tentative directe + 3 tentatives en file, une par étage).

### `docker-compose.yml` — service `backend`

Ajouter dans `environment:` :

```yaml
RABBITMQ_HOST: rabbitmq
RABBITMQ_PORT: 5672
RABBITMQ_USER: ${RABBITMQ_DEFAULT_USER}
RABBITMQ_PASSWORD: ${RABBITMQ_DEFAULT_PASS}
SMS_API_ENDPOINT: ${SMS_API_ENDPOINT}
SMS_API_KEY: ${SMS_API_KEY}
SMS_SENDER_ID: ${SMS_SENDER_ID}
SMS_TIMEOUT_MS: ${SMS_TIMEOUT_MS}
SMS_MAX_TENTATIVES: ${SMS_MAX_TENTATIVES}
```

Ajouter dans `depends_on:` (à côté de `minio`/`meilisearch`) :

```yaml
  rabbitmq:
    condition: service_healthy
```

### `.env.example`

```
# --- Passerelle SMS (ticket #21) ---
SMS_API_ENDPOINT=http://localhost:1
SMS_API_KEY=changez_moi_avec_la_cle_api_de_la_passerelle_sms
SMS_SENDER_ID=SamaPiece
SMS_TIMEOUT_MS=5000
SMS_MAX_TENTATIVES=3
```

(Pas de nouvelles variables `RABBITMQ_HOST`/`RABBITMQ_PORT` : elles sont câblées en dur dans `docker-compose.yml`, comme `MINIO_ENDPOINT: http://minio:9000`, en réutilisant `RABBITMQ_DEFAULT_USER`/`RABBITMQ_DEFAULT_PASS` déjà présents.)

## Plan de tests

| Critère d'acceptation (ticket #21) | Test | Détail |
|---|---|---|
| Client HTTP configurable par variables d'environnement | `PasserelleSmsHttpClientTest` | Le client est construit à partir de `SmsProperties` (elle-même liée à `SMS_API_ENDPOINT`/`SMS_API_KEY`/`SMS_SENDER_ID`/`SMS_TIMEOUT_MS`) ; `MockRestServiceServer` vérifie l'URL, la méthode, le header `Authorization: Bearer ...` et le corps JSON exact envoyés. |
| Client HTTP configurable — vérification manuelle | Manuel | Démarrage `docker-compose up` avec `.env` renseigné, `docker-compose logs backend` sans erreur de démarrage (connexion RabbitMQ + config SMS chargées) — pas d'automatisation pertinente pour un vrai appel Orange/Free (aucune API réelle disponible). |
| Gestion des erreurs de la passerelle (timeout, échec) avec retry via la file de messages | `PasserelleSmsHttpClientTest#envoyer_avecReponse500_doitPublierSurRabbitPourRetry` | Réponse 500 du `MockRestServiceServer` → vérifie `rabbitTemplate.convertAndSend(EXCHANGE, QUEUE_RETRY_30S, new SmsRetryMessage(numero, message, 1))` appelé exactement une fois. |
| Gestion des erreurs — retry technique bout-en-bout | `SmsRetryIntegrationTest#numeroTouoursEnEchec_devraitFinirEnDeadLetterApresMaxTentatives` | Endpoint SMS factice qui répond toujours 500 pour un numéro dédié ; TTL réduits à ~200 ms via `@DynamicPropertySource` ; vérifie via `amqpTemplate.receive("sms.dead-letter", ...)` (avec attente `Awaitility`) qu'un message y apparaît après republication successive sur `sms.retry.30s` → `sms.retry.2m` → `sms.retry.10m`, avec `nombreTentatives` final = `max-tentatives + 1`. |
| Gestion des erreurs — republication d'étage en étage (pas de perte prématurée) | `SmsRetryIntegrationTest#numeroEnEchecPuisSucces_neDoitJamaisAtteindreLaDeadLetter` | Endpoint SMS factice qui répond 500 au 1er appel puis 200 au 2ᵉ appel pour un second numéro dédié ; vérifie (a) le message n'apparaît jamais sur `sms.dead-letter` (poll `Awaitility` sur une fenêtre couvrant le temps de cascade), (b) exactement 2 appels HTTP reçus pour ce numéro. |
| Numéro jamais loggé en clair | `NumeroTelephoneTest#toString_devraitMasquerLeNumero` | Assertion exacte sur le format masqué (`+221771234567` → `+221XXXXXXX67`) et sur la normalisation (`771234567` → `+221771234567`). |
| Numéro jamais loggé en clair | `MasquageNumeroLogsTest` | `ListAppender` Logback attaché au logger `sn.samapiece.notifications` ; déclenche un envoi en échec (via `PasserelleSmsHttpClient` avec `MockRestServiceServer` renvoyant 500) puis un appel direct à `SmsRetryListener#consommer` (succès et échec) ; vérifie qu'aucun message loggué (formaté, y compris arguments et stack trace) ne contient la sous-chaîne masquée du numéro (les chiffres remplacés par `X`, ex. `"771234"` absent de tous les logs capturés). |
| Test d'intégration avec un mock de la passerelle SMS (succès) | `PasserelleSmsHttpClientTest#envoyer_avecReponse200_neDoitPasPublierSurRabbit` | Réponse 200 → `serveurMock.verify()` + `verifyNoInteractions(rabbitTemplateMock)`. |
| Test d'intégration avec un mock de la passerelle SMS (échec) | `PasserelleSmsHttpClientTest#envoyer_avecReponse500_doitPublierSurRabbitPourRetry` | Voir ligne retry ci-dessus (couvre aussi ce critère). |
| Test d'intégration avec un mock de la passerelle SMS — bout-en-bout avec vrai broker | `SmsRetryIntegrationTest` | `RabbitMQContainer` (Testcontainers, `@ServiceConnection`) + serveur HTTP factice `com.sun.net.httpserver.HttpServer` (couvre les deux scénarios dead-letter et succès-au-2e-essai ci-dessus). |
| Erreur de programmation (numéro/message null ou vide) | `PasserelleSmsHttpClientTest#envoyer_avecMessageVide_devraitLeverIllegalArgumentException` (+ variante destinataire null) | `assertThatThrownBy(...).isInstanceOf(IllegalArgumentException.class)`, aucune interaction avec `RestClient`/`RabbitTemplate`. |

Détails d'implémentation `SmsRetryIntegrationTest` :
- `@Container @ServiceConnection static RabbitMQContainer rabbitmq = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management-alpine"));` (même image que `docker-compose.yml`).
- Un unique `HttpServer` factice démarré dans la méthode `@DynamicPropertySource` (avant que le contexte Spring ne démarre), avec deux numéros réservés : un qui répond toujours 500, un qui répond 500 puis 200 (compteur d'appels par numéro extrait du corps JSON reçu).
- `@DynamicPropertySource` fixe aussi `samapiece.sms.retry-ttl-30s-ms`, `retry-ttl-2m-ms`, `retry-ttl-10m-ms` à ~200 ms et `samapiece.sms.max-tentatives` à `3`.
- Utiliser `Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(...)` pour les assertions asynchrones (poll `RabbitTemplate.receive("sms.dead-letter", timeoutMs)`), pas de `Thread.sleep` fixe.

## Écarts identifiés

- **TTL de backoff non paramétrés dans `design.md`** : le design mentionne "réduire les TTL via `@DynamicPropertySource`" pour le test, mais ne précise pas les clés de configuration permettant cette réduction (les TTL n'étaient pas explicitement présentés comme des propriétés externalisées). Cette spec ajoute trois champs à `SmsProperties` (`retryTtl30sMs`, `retryTtl2mMs`, `retryTtl10mMs`, valeurs par défaut 30000/120000/600000 ms conformes à `design.md`) pour rendre `SmsRabbitConfig` testable sans dupliquer la topologie. Point à confirmer en review : cette externalisation n'est pas exposée via `docker-compose.yml`/`.env.example` (les valeurs par défaut suffisent en dev/prod), seul le test les surcharge.
- **Mapping étage de backoff ↔ `max-tentatives` configurable** : le design décrit 3 étages fixes (`sms.retry.30s`/`2m`/`10m`) mais rend `max-tentatives` configurable par variable d'environnement, sans préciser le comportement si cette valeur diffère de 3. Cette spec tranche : toute tentative au-delà de la 3ᵉ réutilise l'étage `sms.retry.10m` jusqu'à `max-tentatives`, puis dead-letter (cf. `routingKeyPourTentative`). À valider en review si un comportement différent est attendu (ex. rejeter une configuration `max-tentatives` incohérente au démarrage).
- Aucun autre écart entre `design.md` et les critères d'acceptation du ticket #21 n'a été identifié — le périmètre (retry technique, pas de déclenchement métier) couvre les 4 critères d'acceptation listés.
