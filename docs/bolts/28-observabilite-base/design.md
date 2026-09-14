# Design -- #28 Observabilite de base (logs structures, healthcheck, erreurs)

## Approche

Le backend est en Spring Boot **3.3.13** (`backend/pom.xml` ligne 10, `spring-boot-starter-parent`), pas 3.4+ : l'option native `logging.structured.format.console=ecs|logstash` (ajoutee en 3.4) n'est **pas disponible** sur cette branche. Le mecanisme retenu est donc le standard pre-3.4 de l'ecosysteme : dependance `net.logstash.logback:logstash-logback-encoder` + un `logback-spring.xml` custom qui remplace l'appender console par defaut par un `LogstashEncoder` (JSON), en conservant le pilotage des niveaux par `logging.level.sn.samapiece` deja present dans `application-dev.yml`/`application-prod.yml`. Cote actuator, `/actuator/health` est deja expose et deja consomme (healthcheck `docker-compose.yml` du service `backend`, test `SamaPieceApplicationTests`) : seul `/actuator/info` est reellement nouveau, alimente uniquement par le build-info Maven (pas de git-commit-id-maven-plugin dans le pom actuel, donc pas d'info Git). Sentry (backend `sentry-spring-boot-starter`, frontend `@sentry/react`) est integre mais inactif par defaut tant qu'aucun DSN n'est fourni par variable d'environnement -- aucun compte Sentry n'existe pour ce pilote, donc le cout de ce choix est qu'il restera non teste en vrai avant qu'un DSN reel soit fourni un jour. Le test de non-fuite reprend le pattern deja etabli (`ListAppender` Logback) de `MasquageNumeroLogsTest` (module `notifications`), applique cette fois a la creation de fiche (`POST /api/v1/pieces`) qui, a ce jour, ne logue strictement rien -- il faut donc ajouter un log minimal (id/numero de fiche uniquement) pour que ce critere d'acceptation soit testable, plutot que de constater un test vide vacuement vrai.

## Fichiers/modules impactes

Backend -- modifies
- `backend/pom.xml` -- ajout de `net.logstash.logback:logstash-logback-encoder` et `io.sentry:sentry-spring-boot-starter` ; ajout de l'execution `build-info` sur `spring-boot-maven-plugin` (alimente `/actuator/info`).
- `backend/src/main/resources/application.yml` -- `management.endpoints.web.exposure.include: health,info` ; ajout de `sentry.dsn: ${SENTRY_DSN:}` (vide par defaut) et `sentry.environment` ; pas de nouveau bloc `@ConfigurationProperties` custom, tout passe par les proprietes standard du starter Sentry.
- `backend/src/main/java/sn/samapiece/enregistrement/PieceService.java` (methode `creer`) -- ajout d'un unique log `LOG.info(...)` apres `saveAndFlush`, avec uniquement `piece.getId()` et `piece.getNumeroFiche()` (jamais `nomTitulaire`/`prenomTitulaire`/`numeroDocument`).
- `.env.example` (racine) -- ajout de `SENTRY_DSN=` (vide, commente) pour le backend.
- `docker-compose.yml` -- ajout de `SENTRY_DSN: ${SENTRY_DSN}` dans `environment` du service `backend` (pas de changement au `healthcheck`, qui reste sur `/actuator/health`).

Backend -- nouveaux fichiers
- `backend/src/main/resources/logback-spring.xml` -- appender console `LogstashEncoder`, actif dans tous les profils (dev/staging/prod) pour rester coherent entre environnements.
- `backend/src/test/java/sn/samapiece/enregistrement/web/PieceCreationLogsTest.java` (ou classe ajoutee dans `PieceIntegrationTest.java` existant, a trancher par le spec-writer) -- `@SpringBootTest` + Testcontainers PostgreSQL (meme squelette que `PieceIntegrationTest`), `ListAppender` attache au logger racine (`Logger.ROOT_LOGGER_NAME`) le temps d'un `POST /api/v1/pieces` authentifie, puis assertion qu'aucun evenement capture (message formate + message d'exception eventuelle) ne contient la valeur brute du numero de document ni les valeurs de `nomTitulaire`/`prenomTitulaire` utilisees dans la requete.

Frontend -- modifies
- `frontend/package.json` -- ajout de `@sentry/react`.
- `frontend/src/main.tsx` -- appel a l'initialisation Sentry avant le `createRoot(...).render(...)`.

Frontend -- nouveau fichier
- `frontend/src/observabilite/sentry.ts` -- fonction `initialiserSentry()` : lit `import.meta.env.VITE_SENTRY_DSN`, no-op silencieux si absent/vide, sinon `Sentry.init({ dsn, environment: import.meta.env.MODE })`. Premier module d'observabilite du frontend (le frontend actuel ne contient que `App.tsx`, aucune infra Sentry/monitoring preexistante).

Aucune migration Flyway (pas de changement de schema).

## Decisions cles

1. JSON via `logstash-logback-encoder` + `logback-spring.xml`, pas l'option native Spring Boot 3.4. Verifie dans le pom : version reelle = 3.3.13. Documenter explicitement ce choix pour que personne ne suppose a tort la disponibilite de `logging.structured.format.*`.
2. `/actuator/health` = deja en place, seul `/info` est nouveau. Le critere d'acceptation "exposes et utilises par le docker-compose/CI" est deja majoritairement satisfait pour `health` (healthcheck docker-compose + test existant) ; `info` est expose mais n'est consomme par aucun healthcheck ni pipeline CI (`.github/` ne reference `actuator` nulle part) -- c'est un endpoint d'inspection manuelle, pas un signal de liveness. Ne pas fabriquer artificiellement un usage CI de `/info` qui n'existe pas.
3. Contenu de `/info` minimal (build-info Maven uniquement). Pas de `git-commit-id-maven-plugin` dans le pom actuel, donc pas d'info Git tant que ce plugin n'est pas ajoute (hors perimetre ici, cf. section suivante).
4. Sentry backend et frontend inactifs sans DSN, par variable d'environnement uniquement (`SENTRY_DSN` backend, `VITE_SENTRY_DSN` frontend). Le comportement "no DSN => starter desactive" est le comportement documente du starter `sentry-spring-boot-starter`, mais n'a encore jamais ete verifie dans ce depot (aucun usage de Sentry preexistant) : a confirmer explicitement par le codeur (test simple : demarrage de l'app sans `SENTRY_DSN`, verifier absence d'appel reseau/erreur) plutot que de le supposer acquis.
5. Ajout d'un log minimal dans `PieceService.creer`. Ni `PieceController` ni `PieceService` ne loguent quoi que ce soit aujourd'hui sur ce chemin -- sans cet ajout, le critere d'acceptation 4 ("test verifiant qu'aucune donnee sensible n'apparait dans un log genere lors de la creation d'une fiche") n'a rien de concret a tester. Le log ajoute ne contient que `id`/`numeroFiche` (identifiants techniques, deja visibles dans `PieceResponse`), jamais `nomTitulaire`/`prenomTitulaire`/`numeroDocument`.
6. `ListAppender` attache au logger racine plutot qu'a un logger applicatif precis (contrairement a `MasquageNumeroLogsTest` qui cible `sn.samapiece.notifications`). Choix volontaire pour couvrir aussi un eventuel log Hibernate/Spring (ex. SQL logging, erreurs de validation) declenche par le meme appel HTTP, pas seulement le log applicatif ajoute au point 5 -- prix : plus de bruit dans les evenements captures, mais l'assertion (absence de sous-chaine) reste simple.
7. Pas de nouveau `@ConfigurationProperties`. La config Sentry (backend et frontend) passe entierement par les proprietes standard des starters (`sentry.*`, `VITE_SENTRY_DSN`) ; aucune propriete metier custom n'est necessaire pour ce ticket.

## Risques / points d'attention

- Fuite via stack trace non maitrisee : aucun `@ExceptionHandler(Exception.class)` generique n'existe dans le repo (chaque module a son propre `@RestControllerAdvice` cible, ex. `PieceExceptionHandler`) -- une exception non geree remonte donc au handler par defaut de Spring, qui peut logger le message d'exception (potentiellement porteur de valeurs de champs si une exception de validation embarque un jour la valeur rejetee). Sentry capture aussi automatiquement les exceptions non gerees cote backend : si une exception contient un jour `numeroDocument`/`nomTitulaire` dans son message, elle partirait telle quelle vers Sentry des qu'un DSN reel sera configure. A traiter par une regle de revue de code ("jamais de donnee personnelle dans un message d'exception"), pas par un filtre automatique dans ce ticket.
- `application-dev.yml` met `sn.samapiece` en `DEBUG` -- le passage en JSON ne doit pas faire remonter accidentellement plus de contenu qu'avant ; a verifier que le niveau DEBUG existant (ex. `AuditAspect` ligne 92, `log.debug`) ne devient pas plus visible/expose qu'avant, seul le format change.
- Cout de performance du logging JSON : `LogstashEncoder` serialise chaque evenement en JSON (cout CPU marginal mais non nul par rapport au `PatternLayout` texte actuel) -- negligeable au volume attendu du pilote (moins de 1000 fiches/mois, section 11.6) mais a mentionner si le sujet revient en Phase 3 (national).
- Comportement reel du starter Sentry sans DSN non verifie dans ce depot (cf. decision 4) -- risque que le codeur suppose un no-op garanti sans le tester explicitement.
- `/actuator/info` expose sans restriction d'acces specifique : `management.endpoints.web.exposure.include` couvre deja `health` sans politique d'acces documentee ; ajouter `info` suit la meme posture existante (pas de nouvelle politique a inventer ici), mais le contenu doit rester non sensible (version/build uniquement, jamais de secret).
- `enregistrement`/`iam` ne loguent aujourd'hui quasiment rien (verifie par recherche exhaustive des appels de logging dans `backend/src/main/java`) -- ce n'est donc pas une non-conformite au pattern `NumeroTelephone` (rien a masquer, faute de log existant), mais cela signifie aussi qu'aucune discipline de logging n'y est encore etablie ; le log ajoute au point 5 sert de premier precedent explicite pour ces modules.

## Hors perimetre

- Metriques/dashboards Grafana/Prometheus et alerting (section 11.8, tableaux de bord techniques) -- non demandes par les criteres d'acceptation de ce ticket.
- Agregation centralisee des logs (Loki/ELK) -- ce ticket ne fait que structurer le log en JSON sur stdout, pas son transport/stockage.
- `git-commit-id-maven-plugin` / info Git dans `/actuator/info` -- non ajoute, `/info` reste limite au build-info Maven.
- Configuration d'un compte/projet Sentry reel et de son DSN en staging/production -- hors perimetre technique d'un ticket de code ; seule l'integration desactivable est livree.
- Toute modification du journal d'audit fonctionnel (`sn.samapiece.audit`, ticket #25) -- explicitement distinct des logs techniques d'exploitation par le ticket (section 11.8), non touche ici.
- Ajout d'un `@ExceptionHandler(Exception.class)` generique pour neutraliser tout risque de stack trace -- signale en risque, pas traite dans ce ticket.
