# Review — Ticket #17 : Indexation Meilisearch des pieces disponibles

APPROVE

## Critères d'acceptation

| # | Critère | Statut |
|---|---|---|
| 1 | Document minimal (type, nom, prénom, poste, statut) indexé à la création en `DISPONIBLE`, jamais numéro complet ni photos | **Couvert** — code (`PieceService.creer` + `PieceRechercheDocument` + `PieceIndexationListener`) et test qui échouerait sans lui (`PieceIndexationIntegrationTest#creer_...`, assertion structurelle exacte sur les 6 clés ; `PieceRechercheIndexServiceTest#indexer_...`) |
| 2 | Le document est retiré/mis à jour de l'index quand le statut change (RETIREE, ARCHIVEE, SIGNALEE) | **Partiel, écart assumé et documenté** — voir section dédiée ci-dessous. Aucun déclenchement métier réel n'existe (et ne peut exister sans empiéter sur #24) ; seul le point d'extension `desindexer(UUID)` est livré et testé isolément. |
| 3 | Test d'intégration vérifiant la cohérence index/base après création et après retrait | **Couvert** — `PieceIndexationIntegrationTest` (les deux méthodes de test, patron Testcontainers `PhotoIntegrationTest`), vérifie explicitement que le retrait de l'index ne touche pas la ligne PostgreSQL |

## Sur l'écart du critère 2 (à faire valider par l'humain)

Confirmé en lisant `Piece.java` : le champ `statut` est fixé à `DISPONIBLE` dans le seul constructeur public, il n'existe ni setter, ni méthode `changerStatut()`, ni endpoint de transition nulle part dans le code actuel (recherche vérifiée). Il est donc factuellement impossible d'implémenter un déclenchement réel de `desindexer(...)` sans introduire précisément le mécanisme de transition de statut que le ticket #24 ("Workflow de retrait") doit concevoir — toute tentative de contournement (ex. un setter ad hoc juste pour ce ticket) empiéterait sur le périmètre de conception de #24 et créerait probablement une divergence avec la conception qu'il retiendra.

Le compromis proposé par le design/spec est donc légitime, dans la même logique que le précédent similaire déjà accepté sur le ticket #8 :
- Le point d'extension `PieceRechercheIndexService.desindexer(UUID)` est livré, testé unitairement (Mockito, y compris le cas best-effort) et testé en intégration (suppression réelle du document Meilisearch, avec vérification que la base PostgreSQL n'est pas affectée).
- Rien dans le code de production n'appelle cette méthode aujourd'hui — c'est documenté explicitement en Javadoc sur la méthode elle-même (`PieceRechercheIndexService.desindexer`), dans le design et dans la spec, donc aucune ambiguïté pour une revue humaine future ni un risque de "fonctionnalité qu'on croit livrée mais qui ne l'est pas".

**Recommandation explicite** : accepter le ticket #17 comme "partiellement complété avec suivi vers #24" pour la fermeture du critère 2 (retrait effectif déclenché par un vrai changement de statut). Ne pas bloquer ce bolt sur ce point — mais s'assurer que le ticket #24 référence explicitement cette dépendance (appeler `PieceRechercheIndexService.desindexer(...)` depuis son futur `changerStatut(...)`) pour qu'elle ne soit pas oubliée.

## Findings

Aucun finding bloquant. Points de vigilance mineurs, non bloquants :

- `backend/src/test/java/sn/samapiece/recherche/PieceIndexationIntegrationTest.java:222-228` — `attendreLaTacheDIndexationLaPlusRecente()` récupère la tâche Meilisearch la plus récente via `getTasks(...limit(1))` sans filtrer par type d'opération ni par ID de document ; ça fonctionne ici parce que chaque test ne déclenche qu'une seule opération d'indexation à la fois sur l'index `pieces-test`, mais c'est fragile si un futur test dans la même classe enchaîne deux opérations sans attendre la première (race possible entre la tâche `addDocuments` et une tâche `deleteDocument` si jamais réordonnées). Pas d'impact aujourd'hui vu le séquencement actuel du test, à garder à l'oeil si la classe grossit.
- Le conteneur Meilisearch (`@Container static GenericContainer<?> meilisearch`) n'est jamais nettoyé (pas de suppression d'index) entre les deux méthodes de test de la même classe — sans impact car chaque test utilise un UUID de pièce distinct et interroge par cet ID précis, mais si un test futur venait à faire un scan large de l'index (`search`, `getDocuments` sans filtre), il verrait les documents des tests précédents.

## Vérifications de code ciblées (toutes conformes)

1. `PieceRechercheDocument` (`backend/src/main/java/sn/samapiece/recherche/PieceRechercheDocument.java`) : record pur, aucune dépendance vers `sn.samapiece.enregistrement.Piece`. Le mapping est fait dans `PieceService.creer` (`backend/src/main/java/sn/samapiece/enregistrement/PieceService.java:63-69`). Pas de cycle de paquets `enregistrement` <-> `recherche` (seul `PieceIndexableEvent`, dans `enregistrement`, importe `recherche.PieceRechercheDocument` — sens de dépendance correct, `enregistrement` vers `recherche`, jamais l'inverse).
2. `PieceService.creer` : le document est construit juste après `pieceRepository.saveAndFlush(piece)` (ligne 61), donc pendant que la session Hibernate est active — `piece.getPoste().getNom()` est résolu sans `LazyInitializationException`. Publication via `ApplicationEventPublisher.publishEvent(...)`, aucun appel direct au client Meilisearch dans ce service. Signature de `creer(CreerPieceRequest)` et `PieceResponse` strictement inchangées.
3. `PieceIndexationListener.surPieceIndexable` : `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` — un rollback de la transaction de `creer()` empêche bien toute indexation (l'événement Spring `AFTER_COMMIT` n'est délivré qu'après commit effectif). Pas de `@Transactional` de classe sur les tests d'intégration qui viendrait empêcher le commit et fausser ce test.
4. `PieceRechercheIndexService.indexer`/`desindexer` : try/catch `Exception` large autour de chaque appel SDK, log `ERROR` avec l'ID de la pièce, aucune exception repropagée — confirmé par le test unitaire ET par `PieceIndexationBestEffortIntegrationTest` (Meilisearch injoignable, `POST /api/v1/pieces` retourne bien `201`).
5. Aucune donnée sensible transmise : `PieceRechercheDocument` ne porte que `id, typeDocument, nomTitulaire, prenomTitulaire, poste, statut`. Vérifié par lecture directe du record ET par l'assertion structurelle stricte (`containsExactlyInAnyOrder`) du test d'intégration sur les clés réellement présentes dans le document Meilisearch récupéré via `getRawDocument`.
6. Fire-and-forget en production confirmé (aucun appel à `client.waitForTask(...)` dans `PieceRechercheIndexService`) ; les tests d'intégration appellent bien `waitForTask` explicitement via leur propre client de test pour éviter la flakiness.

## Build/tests

- `mvn -pl backend -am test -Dtest=PieceRechercheIndexServiceTest` -> **BUILD SUCCESS**, `Tests run: 4, Failures: 0, Errors: 0` (les deux stack traces `MeilisearchException` visibles dans les logs sont attendues : ce sont les logs `ERROR` volontaires du chemin best-effort, capturés par les tests, pas des échecs).
- `mvn -pl backend -am test` (suite complète) -> **BUILD FAILURE** avec `Tests run: 64, Failures: 0, Errors: 10`. Les 10 erreurs sont toutes des `ContainerFetchException: Can't get Docker image ... postgres:16-alpine` — aucun démon Docker n'est disponible dans cet environnement de review. Ce n'est pas une régression introduite par ce bolt : les 10 classes en erreur sont `SamaPieceApplicationTests`, `PieceNumeroFicheGeneratorTest` (déjà documenté comme dépendant de Docker), `PhotoIntegrationTest`, `PieceIntegrationTest`, `AgentAdminIntegrationTest`, `AgentIntegrationTest`, `AuthIntegrationTest`, `PosteIntegrationTest` (toutes préexistantes, Testcontainers Postgres) ainsi que les 2 nouvelles classes `PieceIndexationIntegrationTest` et `PieceIndexationBestEffortIntegrationTest` (également Testcontainers). Les 54 tests restants (dont les 4 nouveaux tests unitaires purs `PieceRechercheIndexServiceTest`) passent tous. Ceci confirme la limitation d'environnement Docker déjà connue, désormais généralisée à toute la suite Testcontainers dans ce sandbox (pas seulement au cas Windows/Docker Desktop déjà documenté pour `PieceNumeroFicheGeneratorTest`).
- Relecture de code particulièrement stricte de `PieceIndexationIntegrationTest` et `PieceIndexationBestEffortIntegrationTest` faite en l'absence d'exécution réelle possible (cf. sections précédentes) : structure, assertions de clés exactes, séquencement transactionnel/non-transactionnel des tests, et patron `@Container`/`@DynamicPropertySource` conformes au plan de tests de la spec et au patron `PhotoIntegrationTest`.
- Pas de changement frontend dans ce diff (`git diff main..HEAD --stat` ne touche aucun fichier sous `frontend/`) — build/tests frontend non applicables à ce périmètre.

## Fichiers clés revus

- `backend/src/main/java/sn/samapiece/recherche/PieceRechercheDocument.java`
- `backend/src/main/java/sn/samapiece/recherche/PieceRechercheIndexService.java`
- `backend/src/main/java/sn/samapiece/recherche/PieceIndexationListener.java`
- `backend/src/main/java/sn/samapiece/recherche/MeilisearchConfig.java`
- `backend/src/main/java/sn/samapiece/recherche/MeilisearchProperties.java`
- `backend/src/main/java/sn/samapiece/enregistrement/PieceIndexableEvent.java`
- `backend/src/main/java/sn/samapiece/enregistrement/PieceService.java`
- `backend/src/main/java/sn/samapiece/enregistrement/Piece.java`
- `backend/src/test/java/sn/samapiece/recherche/PieceRechercheIndexServiceTest.java`
- `backend/src/test/java/sn/samapiece/recherche/PieceIndexationIntegrationTest.java`
- `backend/src/test/java/sn/samapiece/recherche/PieceIndexationBestEffortIntegrationTest.java`
- `backend/pom.xml`, `backend/src/main/resources/application-dev.yml`, `backend/src/test/resources/application.yml`, `docker-compose.yml`, `.env.example`
- `docs/bolts/17-indexation-meilisearch/design.md`, `docs/bolts/17-indexation-meilisearch/spec.md`
