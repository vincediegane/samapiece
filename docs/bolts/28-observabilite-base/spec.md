# Spec -- #28 Observabilite de base (logs structures, healthcheck, erreurs)

## Résumé

Le backend passe ses logs console au format JSON (Logstash), expose `/actuator/info` en plus de `/actuator/health` déjà en place, intègre Sentry (backend et frontend) inactif tant qu'aucun DSN n'est fourni, et un log minimal ajouté dans `PieceService.creer` est couvert par un test dédié garantissant l'absence de donnée personnelle en clair.

## Tâches

### Backend -- dépendances et configuration

- [ ] `backend/pom.xml` -- ajouter la dépendance `net.logstash.logback:logstash-logback-encoder:7.4` (vérifier au moment de l'implémentation que ce numéro de version reste compatible avec la version de `logback-classic` gérée par `spring-boot-starter-parent:3.3.13` ; ajuster uniquement le patch si le build échoue pour cause d'incompatibilité binaire, ne pas changer de branche majeure sans re-vérifier `logback-spring.xml`).
- [ ] `backend/pom.xml` -- ajouter la dépendance `io.sentry:sentry-spring-boot-starter-jakarta:7.14.0` (voir **Écarts identifiés** : c'est l'artifact `-jakarta`, pas `sentry-spring-boot-starter`, qui est requis pour Spring Boot 3.x). Vérifier au moment de l'implémentation qu'une version 7.x plus récente n'est pas disponible ; rester sur la branche majeure 7.x.
- [ ] `backend/pom.xml` -- ajouter une `<execution>` avec le goal `build-info` sur le plugin `spring-boot-maven-plugin` déjà déclaré (aucune configuration additionnelle, pas de `additionalProperties`, pas de plugin git-commit-id).
- [ ] `backend/src/main/resources/application.yml` -- remplacer `management.endpoints.web.exposure.include: health` par `management.endpoints.web.exposure.include: health,info` (une seule valeur scalaire séparée par virgule, comme aujourd'hui, pas de syntaxe liste YAML).
- [ ] `backend/src/main/resources/application.yml` -- ajouter à la racine (même niveau que `spring:`/`server:`/`management:`) :
  ```yaml
  sentry:
    dsn: ${SENTRY_DSN:}
    environment: ${SPRING_PROFILES_ACTIVE:dev}
  ```
  Ne pas nester ces clés sous `samapiece:` : ce sont des propriétés standard reconnues directement par `sentry-spring-boot-starter-jakarta`.
- [ ] `backend/src/main/resources/logback-spring.xml` (nouveau fichier) -- contenu exact, sans variation :
  ```xml
  <?xml version="1.0" encoding="UTF-8"?>
  <configuration>
      <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
          <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
      </appender>

      <root level="INFO">
          <appender-ref ref="CONSOLE"/>
      </root>
  </configuration>
  ```
  Ne pas ajouter d'élément `<logger name="sn.samapiece" .../>` dans ce fichier : Spring Boot applique automatiquement `logging.level.sn.samapiece` (déjà défini à `DEBUG` dans `application-dev.yml` et `INFO` dans `application-prod.yml`) par-dessus n'importe quel `logback-spring.xml`, qu'il soit personnalisé ou par défaut -- l'ajouter en dur ici referait doublon et risquerait de figer un niveau qui n'est plus piloté par le profil actif. Un seul fichier, actif dans tous les profils (dev/staging/prod), pas de `<springProfile>`.
- [ ] `backend/src/test/resources/application.yml` -- ajouter explicitement :
  ```yaml
  sentry:
    dsn: ""
    environment: test
  ```
  But : rendre l'absence de DSN en test explicite et indépendante du défaut `${SENTRY_DSN:}` de `application.yml` (défense en profondeur si ce défaut change un jour côté main).

### Backend -- log applicatif et vérification Sentry

- [ ] `backend/src/main/java/sn/samapiece/enregistrement/PieceService.java` -- ajouter en champ de classe `private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PieceService.class);`, puis dans `creer(...)`, juste après la ligne `pieceRepository.saveAndFlush(piece);` (avant la construction de `PieceRechercheDocument`) :
  ```java
  LOG.info("Piece creee id={} numeroFiche={}", piece.getId(), piece.getNumeroFiche());
  ```
  Aucune autre variable ne doit être ajoutée à ce message. `piece.getId()` est un UUID technique, `piece.getNumeroFiche()` est généré côté serveur au format `PC-<posteIdHex8>-<annee>-<sequence5>` (voir `PieceNumeroFicheGenerator`, testé par le pattern `^PC-[0-9A-F]{8}-\d{4}-\d{5}$` dans `PieceIntegrationTest`) : aucune des deux valeurs ne peut interpoler `nomTitulaire`, `prenomTitulaire` ou `numeroDocument`.
- [ ] `backend/src/test/java/sn/samapiece/SamaPieceApplicationTests.java` -- ajouter une méthode de test `infoEndpoint_shouldReturn200` :
  ```java
  @Test
  void infoEndpoint_shouldReturn200() throws Exception {
      mockMvc.perform(get("/actuator/info"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.build").exists());
  }
  ```
  (ajouter l'import statique `jsonPath` de `MockMvcResultMatchers`). Cette assertion `$.build` vérifie concrètement que l'exécution `build-info` du plugin Maven alimente bien `/actuator/info`, pas seulement que l'endpoint répond 200 avec un corps vide.
- [ ] `backend/src/test/java/sn/samapiece/SamaPieceApplicationTests.java` -- ajouter une méthode de test `sentryDoitEtreDesactiveSansDsn` qui vérifie explicitement le point non testé du design :
  ```java
  @Test
  void sentryDoitEtreDesactiveSansDsn() {
      assertThat(io.sentry.Sentry.isEnabled()).isFalse();
  }
  ```
  (ajouter l'import statique `org.assertj.core.api.Assertions.assertThat`). Ce test tourne dans le même contexte Spring déjà démarré par `@SpringBootTest` (aucun DSN dans `application.yml`/`application.yml` de test) : il transforme l'hypothèse "no DSN => starter désactivé" en assertion vérifiée, plutôt que de se contenter de constater que `contextLoads()` ne lève pas d'exception. `contextLoads()` reste néanmoins la garantie que le démarrage complet de l'application (avec la dépendance Sentry sur le classpath) ne provoque aucune erreur.
- [ ] Après ajout de `sentry-spring-boot-starter-jakarta`, relancer `SamaPieceApplicationTests.healthEndpoint_shouldReturn200` : si ce test se met à échouer (agrégat `/actuator/health` en `503`), c'est que le starter Sentry a enregistré un health indicator qui échoue sans connectivité réseau réelle -- dans ce cas seulement, ajouter dans `backend/src/main/resources/application.yml` :
  ```yaml
  management:
    health:
      sentry:
        enabled: false
  ```
  en suivant le même motif fail-open que `management.health.redis.enabled: false` déjà présent. Ne pas ajouter cette clé de manière préventive si le test passe déjà sans elle.

### Backend -- test de non-fuite (critère d'acceptation 4)

- [ ] `backend/src/test/java/sn/samapiece/enregistrement/web/PieceCreationLogsTest.java` (nouveau fichier, nouvelle classe dédiée -- ne pas ajouter ces méthodes dans `PieceIntegrationTest.java`). Structure imposée :
  - `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Testcontainers`, avec son propre `@Container @ServiceConnection static PostgreSQLContainer<?> postgres` (même convention que `PieceIntegrationTest`/`SamaPieceApplicationTests`, pas de conteneur partagé entre classes).
  - Dupliquer localement les mêmes méthodes utilitaires privées que `PieceIntegrationTest` (`HORAIRES`, `creerRegion`, `creerPoste`, `creerAgentActif`, `login`, `creerEtLoginToken`, `MOT_DE_PASSE_CLAIR`) -- ce codebase n'a pas de classe de base de fixtures partagée pour ces tests, ne pas en introduire une dans ce ticket.
  - Constantes de test dédiées, **distinctes** de celles de `PieceIntegrationTest` pour éviter toute collision fortuite avec le nom complet de l'agent (`"Diop Awa"` dans `creerAgentActif`) : par exemple `NOM_TITULAIRE = "Ndiaye"`, `PRENOM_TITULAIRE = "Coumba"`, `NUMERO_DOCUMENT_CLAIR = "9988776655443"`.
  - `@BeforeEach` : (a) nettoyage de la base dans le même ordre que `PieceIntegrationTest` (`pieceRepository.deleteAll()`, puis `jdbcTemplate.update("DELETE FROM piece_sequence")` **avant** `posteRepository.deleteAll()` -- FK `piece_sequence` -> `poste` --, puis `agentRepository.deleteAll()`, `posteRepository.deleteAll()`, `regionRepository.deleteAll()` ; pas de `retraitRepository` nécessaire si aucun retrait n'est créé dans cette classe) ; (b) attacher le `ListAppender` :
    ```java
    loggerRacine = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    appender = new ListAppender<>();
    appender.start();
    loggerRacine.addAppender(appender);
    ```
  - `@AfterEach` : `loggerRacine.detachAppender(appender);` -- indispensable pour ne pas laisser l'appender attaché au logger racine au-delà de la méthode de test : Maven Surefire réutilise la même JVM entre classes de test par défaut (`reuseForks=true`) dans ce module, donc un appender non détaché continuerait de capturer les logs de toutes les classes de test suivantes exécutées dans le même processus et fausserait potentiellement leurs propres assertions ou ferait fuiter de la mémoire.
  - Un unique test `creer_avecDonneesValides_neDevraitJamaisLogguerDeDonneePersonnelle` : login en `Role.AGENT`, `POST /api/v1/pieces` avec un payload JSON construit à la main (mêmes champs que `creerPieceRequestJson` dans `PieceIntegrationTest`, mais avec `NOM_TITULAIRE`/`PRENOM_TITULAIRE`/`NUMERO_DOCUMENT_CLAIR` dédiés), assertion `status().isCreated()`, puis pour chaque `ILoggingEvent` de `appender.list` : `assertThat(evenement.getFormattedMessage()).doesNotContain(NUMERO_DOCUMENT_CLAIR).doesNotContain(NOM_TITULAIRE).doesNotContain(PRENOM_TITULAIRE)`, et si `evenement.getThrowableProxy() != null`, la même assertion sur `evenement.getThrowableProxy().getMessage()` (reprendre exactement le pattern de `MasquageNumeroLogsTest.assertAucunLogNeContientLeNumeroEnClair()`). Terminer par `assertThat(appender.list).isNotEmpty()` pour garantir que le test n'est pas vide de sens (au moins le log ajouté dans `PieceService.creer` doit apparaître).
  - Si le test a besoin de lire le corps de la réponse HTTP (ce n'est pas nécessaire pour l'assertion ci-dessus, l'id de la pièce n'étant pas requis), utiliser `getContentAsString(StandardCharsets.UTF_8)`, jamais `getContentAsString()` sans argument.

### Frontend -- intégration Sentry

- [ ] `frontend/package.json` -- ajouter `"@sentry/react": "^8.36.0"` dans `dependencies` (pas `devDependencies`, c'est du code exécuté en production).
- [ ] `frontend/src/observabilite/sentry.ts` (nouveau fichier) :
  ```ts
  import * as Sentry from '@sentry/react';

  export function initialiserSentry(): void {
    const dsn = import.meta.env.VITE_SENTRY_DSN;
    if (!dsn) {
      return;
    }
    Sentry.init({ dsn, environment: import.meta.env.MODE });
  }
  ```
  Le garde `if (!dsn) return;` doit précéder tout appel à `Sentry.init` : ne pas se reposer sur le comportement interne du SDK Sentry face à un DSN vide, le rendre explicite et testable ici.
- [ ] `frontend/src/main.tsx` -- appeler `initialiserSentry()` (import depuis `./observabilite/sentry`) avant `createRoot(...).render(...)`.
- [ ] `frontend/src/observabilite/sentry.test.ts` (nouveau fichier, colocalisé, convention Vitest déjà utilisée pour `RecherchePubliquePage.test.tsx`/`EnregistrementPiecePage.test.tsx`) -- test dédié à la vérification demandée par le ticket :
  ```ts
  import { describe, expect, it, vi, beforeEach } from 'vitest';

  vi.mock('@sentry/react', () => ({ init: vi.fn() }));

  describe('initialiserSentry', () => {
    beforeEach(() => {
      vi.stubEnv('VITE_SENTRY_DSN', '');
    });

    it('ne doit rien faire si VITE_SENTRY_DSN est absent', async () => {
      const Sentry = await import('@sentry/react');
      const { initialiserSentry } = await import('./sentry');
      initialiserSentry();
      expect(Sentry.init).not.toHaveBeenCalled();
    });
  });
  ```
  Adapter la syntaxe exacte de mock/stub d'env Vitest si nécessaire (`vi.stubEnv` + `vi.unstubAllEnvs()` en teardown), l'important est l'assertion `Sentry.init` non appelé sans DSN.

### Fichiers d'environnement et docker-compose

- [ ] `.env.example` (racine) -- ajouter une nouvelle section :
  ```
  # --- Suivi d'erreurs applicatif Sentry (ticket #28) ---
  # Laisser vide en developpement local (aucun compte Sentry pour ce pilote) : le SDK backend
  # (sentry-spring-boot-starter-jakarta) et le SDK frontend (@sentry/react) restent desactives
  # (no-op) tant qu'aucun DSN n'est fourni.
  SENTRY_DSN=
  VITE_SENTRY_DSN=
  ```
- [ ] `frontend/.env.example` (nouveau fichier -- le frontend n'a aujourd'hui aucune variable d'environnement, Vite lit ses fichiers `.env*` depuis la racine `frontend/`, pas depuis le `.env` racine du monorepo) :
  ```
  # Copier en .env.local pour `npm run dev`/`npm run build` en local. Laisser vide : aucun
  # compte Sentry pour ce pilote (voir aussi la variable SENTRY_DSN cote backend, .env.example
  # racine, et VITE_SENTRY_DSN dans docker-compose.yml pour le build de l'image frontend).
  VITE_SENTRY_DSN=
  ```
- [ ] `docker-compose.yml` -- service `backend` : ajouter `SENTRY_DSN: ${SENTRY_DSN}` dans son bloc `environment` existant (ne pas toucher au `healthcheck`, qui reste sur `/actuator/health`).
- [ ] `docker-compose.yml` -- service `frontend` : Vite intègre les variables `VITE_*` **au moment du build** (`npm run build` exécuté dans le Dockerfile), pas au runtime du conteneur nginx -- un simple ajout dans `environment:` du service `frontend` serait donc un no-op silencieux. Ajouter à la place un bloc `build.args` :
  ```yaml
  frontend:
    build:
      context: ./frontend
      args:
        VITE_SENTRY_DSN: ${VITE_SENTRY_DSN}
  ```
- [ ] `frontend/Dockerfile` -- dans le stage `build`, avant `RUN npm run build`, ajouter :
  ```dockerfile
  ARG VITE_SENTRY_DSN
  ENV VITE_SENTRY_DSN=$VITE_SENTRY_DSN
  ```
  (à placer après le `COPY . .` existant et avant `RUN npm run build`, pour que la variable soit visible du process `vite build`).

## Contrat technique

- Endpoint `GET /actuator/info` -- nouveau, exposé via `management.endpoints.web.exposure.include: health,info`. Code 200, corps JSON contenant au minimum la clé `build` (objet avec `version`, `artifact`, `name`, `group`, `time`) alimentée par l'exécution Maven `build-info`. Pas d'authentification spécifique différente de `/actuator/health` (posture existante, non modifiée par ce ticket).
- Log applicatif ajouté : niveau `INFO`, logger `sn.samapiece.enregistrement.PieceService`, message `"Piece creee id={} numeroFiche={}"` avec pour arguments `piece.getId()` (UUID) et `piece.getNumeroFiche()` (String, format `PC-XXXXXXXX-YYYY-NNNNN`). Format de sortie console : JSON (`LogstashEncoder`), un événement par ligne.
- Propriétés Sentry backend : `sentry.dsn` (défaut `""` via `${SENTRY_DSN:}`), `sentry.environment` (défaut `dev` via `${SPRING_PROFILES_ACTIVE:dev}`). Aucune propriété custom `samapiece.sentry.*`.
- Variable frontend : `VITE_SENTRY_DSN`, lue via `import.meta.env.VITE_SENTRY_DSN` dans `frontend/src/observabilite/sentry.ts`, propagée au build Docker via `docker-compose.yml` (`build.args`) et `frontend/Dockerfile` (`ARG`/`ENV`).
- Dépendance backend : `io.sentry:sentry-spring-boot-starter-jakarta` (variante Jakarta EE requise pour Spring Boot 3.x, cf. Écarts identifiés), pas `io.sentry:sentry-spring-boot-starter`.

## Plan de tests

| Critère d'acceptation | Couverture |
|---|---|
| Logs applicatifs structurés (JSON), sans donnée personnelle en clair | Format JSON : validation manuelle (`docker-compose up backend` ou `mvn -pl backend spring-boot:run`, inspection visuelle du stdout -- chaque ligne doit être un objet JSON valide, ex. via `\| jq .`) ; un `logback-spring.xml` mal formé casserait de toute façon le démarrage de **tous** les tests `@SpringBootTest` existants (`SamaPieceApplicationTests`, `PieceIntegrationTest`, etc.), ce qui constitue déjà un garde-fou automatique indirect. Absence de donnée personnelle : `PieceCreationLogsTest` (nouveau, automatisé, JUnit/`ListAppender`). |
| `/actuator/health` et `/actuator/info` exposés et utilisés par le docker-compose/CI | `health` : déjà couvert (test existant `SamaPieceApplicationTests.healthEndpoint_shouldReturn200` + `healthcheck` déjà présent dans `docker-compose.yml`) -- vérifier que ce test passe toujours après ajout du starter Sentry (cf. tâche dédiée). `info` : nouveau test `SamaPieceApplicationTests.infoEndpoint_shouldReturn200` (asserte 200 + présence de `$.build`). Note : conformément à la décision de l'architecte, `/info` n'est consommé par aucun healthcheck ni pipeline CI (contrairement à `/health`) -- c'est un choix assumé, pas un test manquant à inventer. |
| Intégration d'un tracker d'erreurs applicatif (Sentry) côté backend et frontend | Backend : `SamaPieceApplicationTests.sentryDoitEtreDesactiveSansDsn` (nouveau, assertion `Sentry.isEnabled() == false`) + `contextLoads()` existant (démarrage sans erreur avec le starter sur le classpath). Frontend : `frontend/src/observabilite/sentry.test.ts` (nouveau, `Sentry.init` non appelé sans `VITE_SENTRY_DSN`) + jobs CI existants `.github/workflows/frontend.yml` (`npm test`, `npm run build`) qui tournent déjà sans `VITE_SENTRY_DSN` défini et doivent continuer à passer. Intégration réelle avec un DSN de production : hors périmètre (aucun compte Sentry pour ce pilote), non testable ici. |
| Test vérifiant qu'aucune donnée sensible n'apparaît dans un log généré lors de la création d'une fiche | `PieceCreationLogsTest` (nouveau fichier dédié, `backend/src/test/java/sn/samapiece/enregistrement/web/PieceCreationLogsTest.java`). |

## Écarts identifiés

- **Artifact Sentry incorrect dans le design** : `design.md` mentionne `sentry-spring-boot-starter` sans qualificatif. Or Spring Boot 3.x utilise le namespace Jakarta EE 9+ (`jakarta.*`), et le SDK Sentry Java publie deux artifacts distincts : `sentry-spring-boot-starter` (Spring Boot 2.x / `javax.*`) et `sentry-spring-boot-starter-jakarta` (Spring Boot 3.x / `jakarta.*`). Avec `spring-boot-starter-parent:3.3.13`, c'est impérativement `io.sentry:sentry-spring-boot-starter-jakarta` qu'il faut déclarer, sous peine d'échec d'auto-configuration au démarrage. Corrigé dans la tâche pom.xml ci-dessus.
- **Plomberie Dockerfile/docker-compose pour `VITE_SENTRY_DSN` absente du design** : `design.md` ajoute `SENTRY_DSN` au service `backend` de `docker-compose.yml` mais ne mentionne aucune modification de `frontend/Dockerfile`/`docker-compose.yml` pour le frontend. Or Vite embarque les variables `VITE_*` au moment du build (`npm run build`), pas au runtime du conteneur nginx final -- sans `ARG`/`ENV` dans `frontend/Dockerfile` et sans `build.args` dans `docker-compose.yml`, une éventuelle valeur de `VITE_SENTRY_DSN` posée dans `.env` ne serait jamais réellement embarquée dans l'image `samapiece-frontend:dev`. Le frontend n'a par ailleurs aujourd'hui aucun fichier `.env*` ni aucun usage de `import.meta.env` : ce ticket introduit ce premier précédent, d'où l'ajout de `frontend/.env.example`. Comblé dans les tâches ci-dessus.
