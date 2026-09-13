# Design — Ticket #21 : Integration passerelle SMS (Orange/Free)

## Approche

Ce ticket cree le module `sn.samapiece.notifications` (jusqu'ici vide, uniquement un `package-info.java`) avec un client HTTP generique vers une passerelle SMS configurable par variables d'environnement, expose derriere une interface stable `PasserelleSms` que les tickets futurs #22 (Alertes) et #23 (Worker RabbitMQ) appelleront tel quel. Aucun SDK proprietaire Orange/Free n'est code en dur (aucune specification d'API reelle n'est fournie) : un client REST generique (endpoint + cle API en Bearer/header configurable) suffit pour ce pilote. Le critere "retry via la file de messages" est interprete strictement comme le retry technique de l'appel HTTP lui-meme (timeout, 5xx, erreur reseau), via une file RabbitMQ interne a ce module (republication avec backoff puis dead-letter apres N tentatives) - pas le declenchement metier de l'envoi (qui appartient a #23, qui se contentera d'appeler `PasserelleSms.envoyer(...)` depuis son propre consumer). Prix de ce decoupage : deux systemes de file RabbitMQ coexisteront a terme (celui de #21, interne et technique ; celui de #23, metier) - a bien distinguer en review pour eviter toute confusion cote exploitation (voir Risques).

## Fichiers/modules impactes

Le module `notifications` (`backend/src/main/java/sn/samapiece/notifications/`) n'existe aujourd'hui que sous forme de `package-info.java` vide - c'est le premier ticket qui le peuple. Aucune dependance RabbitMQ/AMQP n'existe dans `backend/pom.xml` alors que le service `rabbitmq` est deja present dans `docker-compose.yml` (ticket #3) mais non connecte au service `backend` (pas de variables `RABBITMQ_*` dans le bloc `environment` du service `backend`, pas de `depends_on`).

A creer :
- `backend/src/main/java/sn/samapiece/notifications/PasserelleSms.java` - interface du contrat stable (voir Decisions cles).
- `backend/src/main/java/sn/samapiece/notifications/NumeroTelephone.java` - value object encapsulant le numero, normalisation basique, et masquage systematique via `toString()`.
- `backend/src/main/java/sn/samapiece/notifications/SmsProperties.java` - `@ConfigurationProperties(prefix = "samapiece.sms")`, sur le modele de `PhotoMinioProperties`.
- `backend/src/main/java/sn/samapiece/notifications/PasserelleSmsHttpClient.java` - implementation `PasserelleSms` : appelle la passerelle HTTP via `RestClient` (Spring 6 / Boot 3.3, deja disponible sans dependance supplementaire), et publie sur la file de retry en cas d'echec au lieu de propager l'exception.
- `backend/src/main/java/sn/samapiece/notifications/SmsRabbitConfig.java` - `@Configuration` declarant l'exchange, les files de backoff et la file de consommation/dead-letter (voir Decisions cles).
- `backend/src/main/java/sn/samapiece/notifications/SmsRetryMessage.java` - record serialisable (destinataire, message, nombreTentatives) transporte dans la file.
- `backend/src/main/java/sn/samapiece/notifications/SmsRetryListener.java` - `@RabbitListener` sur la file de consommation : retente l'envoi, republie sur l'etage de backoff suivant en cas de nouvel echec, ou route vers la dead-letter apres le nombre max de tentatives.
- `backend/src/test/java/sn/samapiece/notifications/PasserelleSmsHttpClientTest.java` - test unitaire du client HTTP (succes + echec) via `MockRestServiceServer`.
- `backend/src/test/java/sn/samapiece/notifications/SmsRetryIntegrationTest.java` - test d'integration bout-en-bout retry/backoff/dead-letter (voir Mecanisme de test).

A modifier :
- `backend/pom.xml` - ajouter `spring-boot-starter-amqp` (RabbitMQ), et `org.testcontainers:rabbitmq` en scope test.
- `backend/src/main/resources/application.yml` - bloc `samapiece.sms.*` (endpoint, cle API, expediteur, timeout, max-tentatives) et bloc `spring.rabbitmq.*`.
- `backend/src/main/resources/application-dev.yml` - valeurs par defaut dev pour `spring.rabbitmq.*` (host `rabbitmq` en conteneur / `localhost` en local).
- `docker-compose.yml` - ajouter au service `backend` les variables `RABBITMQ_HOST`/`RABBITMQ_PORT`/`RABBITMQ_USER`/`RABBITMQ_PASSWORD`, `SMS_API_ENDPOINT`, `SMS_API_KEY`, `SMS_SENDER_ID`, et `depends_on: rabbitmq: condition: service_healthy` (le service `rabbitmq` existe deja, healthcheck deja en place).
- `.env.example` - ajouter `SMS_API_ENDPOINT`, `SMS_API_KEY`, `SMS_SENDER_ID`, `SMS_TIMEOUT_MS`, `SMS_MAX_TENTATIVES` (les variables `RABBITMQ_DEFAULT_USER`/`RABBITMQ_DEFAULT_PASS` existent deja pour le conteneur RabbitMQ lui-meme, mais aucune variable ne les relaie au service `backend` - a completer).

## Decisions cles

1. Contrat `PasserelleSms` - interface volontairement minimale pour rester un point d'extension stable pour #22/#23 :
   ```java
   public interface PasserelleSms {
       void envoyer(NumeroTelephone destinataire, String message);
   }
   ```
   `envoyer(...)` ne leve pas d'exception pour un echec de transport (timeout, 5xx, reseau) : ces cas sont geres en interne (tentative directe puis mise en file de retry). Elle peut lever une exception non verifiee pour une erreur de programmation (numero ou message null/vide). Consequence a documenter pour #22/#23 : l'appel ne garantit pas la livraison synchrone, seulement la prise en charge (tentative immediate ou mise en file).

2. Perimetre du retry = technique, pas metier. Ce ticket ne cree ni consumer d'evenement metier ("piece deposee correspondante" = #23), ni entite `NOTIFICATION`/`ALERTE_RECHERCHE`/`CITOYEN_ALERTE` du modele de donnees (paragraphe 9 du PROJET) - ces tables et leur logique d'inscription/declenchement appartiennent a #22/#23. `PasserelleSms` est un service technique sans etat metier persistant.

3. Pas de nouvelle table PostgreSQL / migration Flyway. Le contenu de la file de retry (numero, message, compteur de tentatives) transite uniquement par RabbitMQ, jamais en base - evite de dupliquer prematurement l'entite `NOTIFICATION` du modele cible (paragraphe 9) avant que #22 ne la concoive avec son vrai contexte (alerte, piece correspondante).

4. Topologie RabbitMQ (retry par etages avec TTL + DLX), pattern standard pour eviter le head-of-line blocking d'une file TTL unique (RabbitMQ ne verifie l'expiration qu'en tete de file) :
   - Exchange direct `sms.exchange`.
   - `sms.retry.30s` (TTL 30s, dead-letter -> `sms.exchange` routing key `sms.consume`), `sms.retry.2m` (TTL 2 min), `sms.retry.10m` (TTL 10 min) : trois etages de backoff, un par tentative.
   - `sms.consume` : file consommee par `SmsRetryListener`. Sur echec, republie vers l'etage suivant (routing key `sms.retry.<n>`) avec `nombreTentatives` incremente ; au-dela de `samapiece.sms.max-tentatives` (par defaut 3), route vers `sms.dead-letter`.
   - `sms.dead-letter` : file finale sans consumer applicatif dans ce ticket (inspection via RabbitMQ management UI, deja expose par le service `rabbitmq` de `docker-compose.yml`).
   - Premiere tentative toujours synchrone et directe (pas de passage par la file) : seul un echec declenche la mise en file, pour ne pas ajouter de latence au cas nominal.

5. Format du numero : accepte le format E.164 senegalais (`+221` + 9 chiffres, ex. `+221771234567`), normalisation basique (trim, ajout du prefixe `+221` si un numero local a 9 chiffres est fourni sans indicatif) mais aucune validation stricte (regex de rejet) - conforme au ticket qui n'en demande pas. A durcir eventuellement quand #22 branchera un vrai formulaire citoyen. Un numero malforme suit simplement le chemin d'echec normal (retry puis dead-letter).

6. Masquage systematique du numero dans les logs : `NumeroTelephone` est un value object dont `toString()` retourne une forme masquee (ex. `+221XXXXXX67`, garde l'indicatif pays et les 2 derniers chiffres) ; la valeur brute n'est accessible que via une methode explicite (`valeurBrute()`) appelee uniquement au point d'appel HTTP reel. Tout log (succes, echec, retry, dead-letter) utilise l'objet `NumeroTelephone` directement (jamais une concatenation de String brute), donc jamais le numero en clair meme par erreur d'inattention lors d'un futur log ajoute.

## Mecanisme de test

- Client HTTP (succes + echec) : `PasserelleSmsHttpClientTest` utilise `MockRestServiceServer` (deja disponible via `spring-boot-starter-test`, aucune dependance supplementaire type WireMock necessaire) lie au `RestClient.Builder` du client. Deux cas : reponse 200 -> `envoyer(...)` ne publie rien sur RabbitMQ (mock `RabbitTemplate` verifie non sollicite) ; reponse 500/timeout -> `envoyer(...)` publie un `SmsRetryMessage` sur `sms.exchange`/`sms.consume` (mock `RabbitTemplate` verifie sollicite avec le bon message).
- Retry/backoff/dead-letter bout-en-bout : `SmsRetryIntegrationTest`, `@SpringBootTest` + `Testcontainers` avec `org.testcontainers.containers.RabbitMQContainer` (module `org.testcontainers:rabbitmq`, meme pattern que `PostgreSQLContainer`/`MinIOContainer` deja utilises dans `PhotoIntegrationTest`/`PieceIndexationBestEffortIntegrationTest`), et l'endpoint SMS pointe vers une URL injoignable (`http://127.0.0.1:1`, meme technique que `RecherchePubliqueMeilisearchIndisponibleIntegrationTest` pour Meilisearch) pour forcer l'echec systematique. Le test verifie que le message finit dans `sms.dead-letter` (consultation directe de la file via `RabbitAdmin`/`amqpTemplate.receive("sms.dead-letter", ...)`) apres le nombre de tentatives configure (reduire les TTL via `@DynamicPropertySource` a quelques centaines de ms pour un test rapide). Un second scenario simule un succes en second essai a l'aide d'un serveur HTTP factice minimal (`com.sun.net.httpserver.HttpServer`, deja dans le JDK, pas de dependance supplementaire) qui repond 500 puis 200, pour verifier que le message ne va pas jusqu'a la dead-letter si un etage de retry reussit.

## Risques / points d'attention

- Deux "files RabbitMQ" a ne pas confondre : celle de ce ticket est un mecanisme de retry technique interne au client SMS ; #23 introduira sa propre file/consumer metier (declenchement sur depot de piece correspondante) qui appelle `PasserelleSms.envoyer(...)` mais n'a pas a connaitre la topologie `sms.retry.*`/`sms.consume`/`sms.dead-letter`. A rappeler explicitement dans la Javadoc de `PasserelleSms` pour eviter qu'un futur ticket ne re-implemente un retry par-dessus un retry.
- Connexion RabbitMQ non encore cablee cote `backend` : `docker-compose.yml` a le service `rabbitmq` (sain) mais le service `backend` ne recoit aujourd'hui aucune variable `RABBITMQ_*` - a ajouter dans ce ticket, sinon le backend ne demarrera pas des que `spring-boot-starter-amqp` est ajoute (Spring Boot tente une connexion RabbitMQ au demarrage par defaut).
- Limite du pattern TTL+DLX par etages : avec un tres faible volume (contexte pilote, paragraphe 11.6 du PROJET), le head-of-line blocking residuel (au sein d'un meme etage) est negligeable ; a reconsiderer si le volume augmente fortement (cas non attendu avant la phase nationale).
- Aucun mecanisme anti-abus/rate-limiting sur l'envoi n'est ajoute ici - un appelant buggue de #22/#23 pourrait spammer un numero ; explicitement hors perimetre de ce ticket (le risque "cout variable a l'usage" est mentionne en paragraphe 16.2 du PROJET mais pas traite ici).
- Pas de bascule multi-operateur automatique - un seul endpoint/cle API configurable a la fois, alors que le PROJET (paragraphe 14, risque "Dependance a un operateur SMS unique") recommande a terme une integration multi-operateurs avec bascule ; explicitement hors perimetre ici (mitigation partielle : l'abstraction `PasserelleSms` permettrait de brancher une implementation multi-operateurs plus tard sans changer les appelants).
- API reelle Orange/Free inconnue : aucune information d'authentification/format de payload reelle n'est fournie par le ticket ; le client REST generique (header Bearer, JSON avec destinataire/message/expediteur) est une hypothese de travail a valider/adapter des qu'un contrat operateur existe - isole entierement dans `PasserelleSmsHttpClient`, seul fichier a modifier le cas echeant.
- Masquage a verifier en review : grep systematique de toute concatenation impliquant un numero brut dans les nouveaux fichiers, pour s'assurer qu'aucun log/exception ne contourne le masquage de `NumeroTelephone`.

## Hors perimetre

- Module Alertes (#22) : formulaire d'inscription citoyen, entites `CITOYEN_ALERTE`/`ALERTE_RECHERCHE`, envoi du lien de desinscription.
- Worker RabbitMQ metier (#23) : consumer declenchant un envoi SMS depuis l'evenement "piece deposee correspondante", entite `NOTIFICATION`.
- Canaux push et email (paragraphe 7.3 du PROJET) - uniquement SMS dans ce ticket.
- Bascule automatique multi-operateurs (Orange + Free simultanement).
- Validation stricte du format de numero de telephone.
- Rate limiting / anti-abus sur l'envoi de SMS.
- Toute nouvelle table PostgreSQL ou migration Flyway.
- Canal USSD (Phase 2/3 du PROJET, hors sujet ici).
