# Review — Ticket #21 : Intégration passerelle SMS (Orange/Free)

APPROVE

## Critères d'acceptation

| Critère | Statut | Preuve |
|---|---|---|
| Client HTTP vers l'API SMS retenue, configurable par variables d'environnement (clé API, endpoint) | Couvert | `PasserelleSmsHttpClient` construit son `RestClient` a partir de `SmsProperties` (`api-endpoint`, `api-key`, `sender-id`, `timeout-ms`, tous lies a `SMS_API_ENDPOINT`/`SMS_API_KEY`/`SMS_SENDER_ID`/`SMS_TIMEOUT_MS`) ; `.env.example` et `docker-compose.yml` propagent bien ces variables au service `backend`. Teste par `PasserelleSmsHttpClientTest` (URL, methode, header `Authorization`, corps JSON exact). |
| Gestion des erreurs de la passerelle (timeout, echec) avec retry via la file de messages | Couvert | `tenterEnvoiDirect` catch `RestClientException` (couvre timeout/5xx/erreurs reseau via `RestClient`) puis `envoyer(...)` publie sur `sms.retry.30s` au premier echec ; `SmsRetryListener` republie d'etage en etage (30s -> 2m -> 10m) puis route vers `sms.dead-letter` au-dela de `max-tentatives`. Teste unitairement (`PasserelleSmsHttpClientTest#envoyer_avecReponse500_doitPublierSurRabbitPourRetry`) et bout-en-bout par `SmsRetryIntegrationTest` (dead-letter apres epuisement, pas de dead-letter en cas de succes au 2e essai). |
| Numero de telephone du destinataire jamais logue en clair dans les logs applicatifs | Couvert | Verifie par lecture exhaustive : les appels `LOG.info/warn/error` de `PasserelleSmsHttpClient` et `SmsRetryListener` passent tous un objet `NumeroTelephone` (jamais `.valeurBrute()`, jamais `messageRetry.destinataire()` brut, jamais de concatenation `String`). `SmsRetryListener#consommer` reconstruit systematiquement `NumeroTelephone.de(messageRetry.destinataire())` avant tout log. Couvert par `NumeroTelephoneTest` (masquage exact `+221XXXXXXX67`) et `MasquageNumeroLogsTest` (4 scenarios : echec direct, echec en file, succes en file, dead-letter -- `ListAppender` Logback verifie qu'aucun message/exception logue ne contient la sous-chaine en clair). |
| Test d'integration avec un mock de la passerelle SMS (succes + echec) | Couvert | `PasserelleSmsHttpClientTest` (`MockRestServiceServer`, succes 200 / echec 500) et `SmsRetryIntegrationTest` (Testcontainers RabbitMQ + `HttpServer` JDK factice, scenario "toujours en echec" et "echec puis succes au 2e essai"). |

## Verifications ciblees

- Masquage systematique : grep de tous les appels `log.info/warn/error` dans `PasserelleSmsHttpClient.java` et `SmsRetryListener.java` confirme que l'argument passe est toujours le `NumeroTelephone` reconstruit, jamais `messageRetry.destinataire()` brut ni une concatenation. Aucune fuite trouvee. `MasquageNumeroLogsTest` execute reellement (voir Build/tests) confirme empiriquement l'absence de la sous-chaine en clair dans le message formate ET dans le message de l'exception loguee.

- Constructeur de test package-private de `PasserelleSmsHttpClient` : le contournement (`RestClient` deja construit) est documente en Javadoc et legitime (conflit `MockRestServiceServer.bindTo(builder)` face a `requestFactory(...)` sur le meme `Builder`). Limite reelle : le cablage du timeout (`SimpleClientHttpRequestFactory.setConnectTimeout/setReadTimeout`) du constructeur public n'est exerce par aucun test unitaire ni assertion explicite -- seul `SmsRetryIntegrationTest` (Testcontainers, non executable dans cet environnement) instancie le bean via le vrai constructeur Spring, sans assertion sur le timeout lui-meme. Ce n'est pas un defaut fonctionnel observe (le code de cablage est trivial et a sens unique), mais c'est un angle mort de test a garder en tete pour un futur ticket touchant ce fichier -- non bloquant.

- Topologie RabbitMQ (`SmsRabbitConfig`) : conforme a la spec. Trois files de backoff avec TTL propres et `deadLetterExchange(EXCHANGE)` / `deadLetterRoutingKey(QUEUE_CONSUME)`, `sms.consume` sans TTL, `sms.dead-letter` sans TTL et sans consumer applicatif, bindings 1:1 par routing key = nom de queue. `routingKeyPourTentative` retombe bien sur `QUEUE_RETRY_10M` pour toute tentative superieure a 2 (`default ->`), ce qui couvre un `max-tentatives` different de 3 comme prevu par la spec.

- Premiere tentative synchrone : confirme -- `envoyer(...)` appelle `tenterEnvoiDirect(...)` avant toute publication RabbitMQ ; `verifyNoInteractions(rabbitTemplate)` en cas de succes direct.

- Healthcheck RabbitMQ : `management.health.rabbit.enabled: false` present a la fois dans `backend/src/main/resources/application.yml` et `backend/src/test/resources/application.yml` (piege deja rencontre sur le ticket #19/Redis, evite ici).

- `docker-compose.yml` : variables `RABBITMQ_HOST/PORT/USER/PASSWORD` et `SMS_API_ENDPOINT/API_KEY/SENDER_ID/TIMEOUT_MS/MAX_TENTATIVES` bien propagees au service `backend` ; `depends_on.rabbitmq.condition: service_healthy` present a cote de `minio`/`meilisearch`.

- Aucune nouvelle entite/migration PostgreSQL : confirme par `git diff --stat main..HEAD` (aucun fichier sous `db/migration` ni nouvelle entite JPA touche).

## Build/tests

- `mvn -q -pl backend -am test -Dtest=NumeroTelephoneTest,PasserelleSmsHttpClientTest,MasquageNumeroLogsTest` -> 11 + 7 + 4 = 22 tests, 0 echec, 0 erreur (confirme via `surefire-reports`).
- `mvn -q -pl backend -am test -Dtest=SamaPieceApplicationTests` -> echec : `ContainerFetchException` (Testcontainers ne trouve pas de demon Docker fonctionnel dans ce sandbox, bien que Docker Desktop soit installe). Limitation d'environnement deja documentee sur des tickets precedents, pas un defaut du code.
- `mvn -q -pl backend -am test -Dtest=SmsRetryIntegrationTest` -> meme echec Docker/Testcontainers. Relecture de code stricte effectuee en compensation, comparaison ligne a ligne avec le plan de tests de `spec.md` : structure (2 conteneurs Testcontainers, serveur HTTP factice JDK, `@DynamicPropertySource` reduisant les 3 TTL a 200 ms, purge des files en `@BeforeEach`, assertions `Awaitility` sur `sms.dead-letter` avec `nombreTentatives == 4` pour le scenario "toujours en echec", absence de message en dead-letter et exactement 2 appels HTTP pour le scenario "echec puis succes") correspond exactement au plan de tests de la spec. Aucune anomalie de logique relevee a la lecture.
- Suite complete hors tests Testcontainers (commande avec exclusions `SamaPieceApplicationTests`, `SmsRetryIntegrationTest`, `*IntegrationTest`, `PieceNumeroFicheGeneratorTest`) -> 93 tests, 0 echec, 0 erreur, aucune regression sur les tests preexistants.
- `PieceNumeroFicheGeneratorTest` echoue pour la meme raison Docker/Testcontainers ; confirme pre-existant et hors perimetre de cette branche (commit e6c3148, non touche par le diff main..HEAD).

## Conclusion

Le code correspond fidelement a `spec.md` (aucune tache omise, aucun ecart non justifie), les 4 criteres d'acceptation sont couverts par du code et des tests qui echoueraient sans lui, le masquage du numero est applique systematiquement et verifie par un test dedie, la topologie RabbitMQ et le cablage docker-compose/healthcheck sont corrects. Les tests executables passent tous sans regression ; les tests Testcontainers ne peuvent pas s'executer dans cet environnement (limitation Docker locale, non liee au code) et ont fait l'objet d'une relecture de code renforcee qui n'a revele aucune anomalie.
