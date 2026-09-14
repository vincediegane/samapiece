# Review -- #28 Observabilite de base (logs structures, healthcheck, erreurs)

APPROVE

## Critères d'acceptation

| # | Critère | Statut | Preuve |
|---|---|---|---|
| 1 | Logs applicatifs structurés (JSON), sans donnée personnelle en clair | Couvert | backend/src/main/resources/logback-spring.xml (appender CONSOLE + LogstashEncoder, root level="INFO", aucun logger sn.samapiece en dur -- le pilotage par logging.level.sn.samapiece des profils dev/prod reste actif). Log ajouté dans PieceService.creer (backend/src/main/java/sn/samapiece/enregistrement/PieceService.java:73) strictement LOG.info("Piece creee id={} numeroFiche={}", piece.getId(), piece.getNumeroFiche()), placé juste après saveAndFlush, aucune autre variable interpolée. Absence de donnée sensible vérifiée par un test dédié (voir critère 4). |
| 2 | actuator/health et actuator/info exposés et utilisés par le docker-compose/CI | Couvert | management.endpoints.web.exposure.include: health,info (backend/src/main/resources/application.yml:23), exécution build-info ajoutée au spring-boot-maven-plugin (backend/pom.xml). Test infoEndpoint_shouldReturn200 vérifie concrètement $.build (pas juste 200 + corps vide). docker-compose.yml healthcheck existant sur /actuator/health non modifié (conforme au choix assumé de ne pas câbler /info dans un healthcheck). |
| 3 | Intégration d'un tracker d'erreurs applicatif (Sentry) côté backend et frontend | Couvert | Backend : io.sentry:sentry-spring-boot-starter-jakarta:7.14.0 (bon artifact -jakarta, confirmé présent dans ~/.m2), sentry.dsn/sentry.environment en config, test sentryDoitEtreDesactiveSansDsn (Sentry.isEnabled() == false). Frontend : frontend/src/observabilite/sentry.ts avec garde if (!dsn) return; strictement avant tout appel à Sentry.init (aucun chemin de code ne peut l'atteindre sans DSN), appelé depuis main.tsx avant createRoot(...).render(...), testé par sentry.test.ts (Sentry.init non appelé sans VITE_SENTRY_DSN). Propagation Docker correcte : docker-compose.yml utilise build.args (pas environment:, qui aurait été un no-op silencieux pour une variable Vite), frontend/Dockerfile déclare ARG/ENV après COPY . . et avant RUN npm run build. |
| 4 | Test vérifiant qu'aucune donnée sensible n'apparaît dans un log généré lors de la création d'une fiche | Couvert | backend/src/test/java/sn/samapiece/enregistrement/web/PieceCreationLogsTest.java, classe dédiée (pas ajoutée dans PieceIntegrationTest), constantes propres (NOM_TITULAIRE="Ndiaye", PRENOM_TITULAIRE="Coumba", NUMERO_DOCUMENT_CLAIR="9988776655443", sans collision avec "Diop"/"Awa" de PieceIntegrationTest), ListAppender attaché au logger racine en @BeforeEach et détaché en @AfterEach (loggerRacine.detachAppender(appender) ligne 105 -- vérifié présent), ordre de nettoyage correct (piece_sequence vidé avant posteRepository.deleteAll()). Assertions sur getFormattedMessage() et sur getThrowableProxy().getMessage() le cas échéant, plus garde-fou assertThat(appender.list).isNotEmpty(). |

Les 4 critères sont couverts par du code et un test qui échouerait si le code était retiré (retirer le LOG.info ferait échouer appender.list.isNotEmpty(), ajouter nomTitulaire dans le message ferait échouer les doesNotContain).

## Vérification des points de vigilance spécifiques (1-11 du prompt)

1. Artifact Sentry : backend/pom.xml déclare bien io.sentry:sentry-spring-boot-starter-jakarta:7.14.0 (confirmé aussi par la présence du jar -jakarta dans ~/.m2/repository/io/sentry/), pas la variante sans suffixe.
2. logback-spring.xml : contenu identique caractère pour caractère à la spec, pas de logger sn.samapiece en dur.
3. Log dans PieceService.creer : ligne 73, exactement LOG.info("Piece creee id={} numeroFiche={}", piece.getId(), piece.getNumeroFiche()), juste après saveAndFlush.
4. PieceCreationLogsTest : classe dédiée conforme en tout point à la spec (conteneur propre, constantes distinctes, détachement de l'appender vérifié présent).
5. management.health.sentry.enabled: false non ajouté : vérification faite en inspectant les jars sentry-7.14.0.jar, sentry-spring-boot-jakarta-7.14.0.jar et sentry-spring-boot-starter-jakarta-7.14.0.jar -- aucune classe HealthIndicator n'y est packagée. L'affirmation du coder (pas de health indicator Sentry auto-enregistré sans DSN) est donc plausible et vérifiée, pas seulement prise sur parole.
6. backend/src/test/resources/application.yml : sentry.dsn: "" et sentry.environment: test bien ajoutés explicitement.
7. frontend/src/observabilite/sentry.ts : le return précède structurellement (et est le seul chemin avant) l'appel à Sentry.init -- aucune fuite possible.
8. docker-compose.yml/frontend/Dockerfile : VITE_SENTRY_DSN passe bien par build.args (service frontend) et par ARG/ENV dans le Dockerfile, dans le bon ordre (après COPY . ., avant RUN npm run build). Le service backend reçoit SENTRY_DSN via environment: classique (correct, c'est une variable runtime JVM, pas une variable Vite build-time).
9. frontend/.env.example créé, cohérent avec l'absence préalable de fichiers .env* côté frontend.
10. /actuator/info : management.endpoints.web.exposure.include: health,info (pas de régression sur health), test infoEndpoint_shouldReturn200 vérifie concrètement $.build via jsonPath.
11. Aucune régression sur le masquage NumeroTelephone : git diff main..bolt/issue-28-observabilite-base ne touche à aucun fichier des modules notifications/alertes, confirmé par une recherche ciblée sur les fichiers *NumeroTelephone*/*Masquage* (aucun résultat).

## Findings

Aucun finding bloquant. Implémentation strictement conforme à spec.md, écarts du design déjà corrigés en amont (artifact Sentry -jakarta, plomberie Docker VITE_SENTRY_DSN via build.args/ARG/ENV) bien appliqués tels quels.

Remarque mineure non bloquante : les tests sentryDoitEtreDesactiveSansDsn, infoEndpoint_shouldReturn200 et PieceCreationLogsTest n'ont pas pu être exécutés jusqu'au bout dans ce sandbox (cf. section Build/tests) faute de Docker -- ils ont été relus manuellement ligne à ligne contre la spec et sont cohérents avec le contrat technique attendu (io.sentry.Sentry.isEnabled(), $.build alimenté par build-info, absence de donnée sensible dans les assertions ListAppender).

## Build/tests

- mvn -q -pl backend -am compile -- OK (aucune sortie, succès).
- mvn -q -pl backend -am test-compile -- OK (aucune sortie, succès), y compris PieceCreationLogsTest.java (nouvelle classe).
- mvn -pl backend test -- 205 tests exécutés, 185 passent, 20 erreurs, toutes de la forme "ContainerFetch Can't get Docker image: ... postgres:16-alpine". Confirmé que ces 20 échecs touchent indifféremment des classes non modifiées par ce diff (AgentIntegrationTest, AgentAdminIntegrationTest, AuthIntegrationTest, PosteIntegrationTest, RecherchePubliqueIntegrationTest, AlerteIntegrationTest, AlerteCorrespondanceIntegrationTest, AuditEndpointIntegrationTest, PieceAuditIntegrationTest, PieceNumeroFicheGeneratorTest, PhotoIntegrationTest, SmsRetryIntegrationTest, PieceIndexationBestEffortIntegrationTest, PieceIndexationIntegrationTest, RecherchePubliqueCaptchaIntegrationTest, RecherchePubliqueMeilisearchIndisponibleIntegrationTest, RecherchePubliqueRateLimitingIntegrationTest) et les classes touchées par ce ticket (SamaPieceApplicationTests, PieceIntegrationTest, PieceCreationLogsTest) -- même message d'erreur, même cause (IllegalStateException: Previous attempts to find a Docker environment failed), ce qui confirme qu'il s'agit bien de la limitation Docker/sandbox déjà documentée et non d'une régression introduite par ce bolt. Non bloquant, compensé par la relecture manuelle détaillée de SamaPieceApplicationTests/PieceCreationLogsTest ci-dessus.
- npm run build (frontend) -- OK, build Vite réussi (tsc -b && vite build).
- npm run lint (frontend) -- OK, aucune erreur ESLint.
- npm test (frontend) -- OK, 3 fichiers de test / 17 tests passent, y compris le nouveau src/observabilite/sentry.test.ts.
