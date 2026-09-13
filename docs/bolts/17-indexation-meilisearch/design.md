# Design — Ticket #17 : Indexation Meilisearch des pieces disponibles

## Approche

A la creation reussie d'une `Piece` en statut `DISPONIBLE` (seul point d'entree existant aujourd'hui, via `PieceService.creer`), le service publie un evenement Spring applicatif (`PieceIndexableEvent`) apres commit de la transaction (`@TransactionalEventListener(phase = AFTER_COMMIT)`), consomme par un nouveau composant `sn.samapiece.recherche` qui pousse un document minimal dans Meilisearch via un client HTTP dedie. Ce decouplage evite qu'un incident Meilisearch fasse echouer l'enregistrement d'une fiche en base (l'integrite du depot legal prime sur la recherche). En cas d'echec d'indexation, on se contente d'un best-effort avec log ERROR - pas de retry ni de file RabbitMQ dans ce ticket : le volume est faible (paragraphe 11.6 du PROJET, moins de 1000 fiches/mois en pilote), et une file de messages pour ce besoin serait de la sur-ingenierie a ce stade (RabbitMQ est deja reserve au ticket #23 pour les notifications, un besoin metier different et plus critique cote delai). Le prix de ce compromis : une incoherence index/base possible en cas de panne Meilisearch au moment de la creation, non auto-reparee - assumee et documentee ci-dessous (risques).

Le changement de statut (retrait de l'index) n'est **pas implemente** dans ce ticket : `Piece` n'expose aujourd'hui aucun mecanisme pour changer de statut apres construction (fixe a `DISPONIBLE` dans le constructeur, aucun setter). Le ticket #24 ("Workflow de retrait") introduira ce mecanisme. Ce ticket se limite donc a :
- indexer a la creation (le seul statut atteignable est `DISPONIBLE`) ;
- fournir un point d'extension pret a l'emploi (`PieceRechercheIndexService.desindexer(UUID pieceId)` et `.indexer(Piece piece)`) que le ticket #24 n'aura qu'a appeler depuis son futur `changerStatut(...)`, sans avoir a concevoir le cablage Meilisearch lui-meme.

## Fichiers/modules impactes

Le module `recherche` (`backend/src/main/java/sn/samapiece/recherche/`) n'existe aujourd'hui que sous forme de `package-info.java` vide - c'est le premier ticket qui le peuple.

**A creer :**
- `backend/src/main/java/sn/samapiece/recherche/PieceRechercheDocument.java` - record representant le document minimal indexe.
- `backend/src/main/java/sn/samapiece/recherche/PieceRechercheIndexService.java` - encapsule les appels au client Meilisearch (`indexer`, `desindexer`), gestion des erreurs (try/catch + log, jamais de propagation).
- `backend/src/main/java/sn/samapiece/recherche/MeilisearchProperties.java` - `@ConfigurationProperties(prefix = "samapiece.meilisearch")` (host, cle API/master key, nom d'index), sur le modele de `PhotoMinioProperties`.
- `backend/src/main/java/sn/samapiece/recherche/MeilisearchConfig.java` - declare le bean client Meilisearch (`com.meilisearch.sdk.Client`).
- `backend/src/main/java/sn/samapiece/enregistrement/PieceIndexableEvent.java` - evenement applicatif minimal (porte le `PieceRechercheDocument` deja construit, pour eviter toute relecture de donnees sensibles ou tout probleme de lazy-loading cote listener).
- `backend/src/main/java/sn/samapiece/recherche/PieceIndexationListener.java` - `@Component` avec `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`, appelle `PieceRechercheIndexService.indexer(...)`.
- `backend/src/test/java/sn/samapiece/recherche/PieceIndexationIntegrationTest.java` - test d'integration (voir mecanisme de test ci-dessous).

**A modifier :**
- `backend/src/main/java/sn/samapiece/enregistrement/PieceService.java` - injecter `ApplicationEventPublisher`, construire le `PieceRechercheDocument` (pendant que la session Hibernate est active) et publier `PieceIndexableEvent` apres `pieceRepository.saveAndFlush(piece)`.
- `backend/pom.xml` - ajouter la dependance client Meilisearch Java (`com.meilisearch.sdk:meilisearch-java`) ; aucune dependance Meilisearch n'existe actuellement dans le pom.
- `backend/src/main/resources/application.yml` / `application-dev.yml` - bloc `samapiece.meilisearch.*` (host/URL, cle API, nom d'index), sur le modele du bloc `samapiece.minio.*` deja present.
- `docker-compose.yml` - ajouter les variables d'environnement Meilisearch au service `backend` (le service `meilisearch` existe deja avec `MEILI_MASTER_KEY`/`MEILISEARCH_HOST_PORT` depuis le ticket #3, mais le service `backend` ne les recoit pas encore).
- `.env.example` - verifier/completer si une variable d'URL interne type `MEILISEARCH_HOST` manque pour le service backend (les variables `MEILI_MASTER_KEY` et `MEILISEARCH_HOST_PORT` existent deja).

## Decisions cles

1. **Synchronisation par evenement Spring, pas d'appel direct dans `PieceService.creer`** : decouple la persistance metier (source de verite) de l'indexation (best-effort). `@TransactionalEventListener(AFTER_COMMIT)` garantit qu'on n'indexe jamais une pièce qui a finalement ete rollback.
2. **Pas de retry ni de RabbitMQ pour ce ticket** : best-effort + log ERROR en cas d'echec Meilisearch. Un mecanisme de rattrapage (job de reconciliation periodique index/base) est explicitement hors perimetre et laisse a un ticket futur si le besoin est demontre - coherent avec le principe 11.1.3 du PROJET ("simplicite avant scalabilite prematuree").
3. **Document minimal indexe**, index Meilisearch `pieces` :

   ```json
   {
     "id": "<uuid de la Piece>",
     "typeDocument": "CNI",
     "nomTitulaire": "Fall",
     "prenomTitulaire": "Moussa",
     "poste": "Commissariat Central Dakar",
     "statut": "DISPONIBLE"
   }
   ```

   - `id` = UUID de `Piece` (necessaire pour retrouver/mettre a jour/supprimer le document plus tard, notamment par #24). Ce n'est pas une donnee sensible en soi, mais il ne doit jamais etre expose tel quel dans les reponses de l'API de recherche publique elle-meme (hors perimetre de ce ticket) - seule une reference de dossier distincte doit etre montree au citoyen (paragraphe 7.2 du PROJET).
   - `poste` = nom du poste (`piece.getPoste().getNom()`), pas son id technique, pour permettre l'affichage direct sans jointure cote recherche.
   - **Explicitement exclus** : `numeroDocumentHash`, `numeroDocumentSel`, `numeroDocumentMasque` (aucune forme du numero, meme masquee - le masquage est un affichage agent/citoyen, pas une donnee d'index), `dateNaissanceTitulaire`, `remarques`, `etatDocument`, toute reference aux photos, `agentCreateur`.
4. **Pas de methode `changerStatut()` ajoutee a `Piece` dans ce ticket** : cela appartient au ticket #24. Le service d'indexation expose deja `desindexer(UUID pieceId)`, mais rien ne l'appelle pour l'instant - documente comme preparation, pas comme fonctionnalite livree.
5. **Nom de l'index** : `pieces` (configurable via `samapiece.meilisearch.index-pieces`), cle primaire Meilisearch = `id`.

## Mecanisme de test

Il n'existe pas de module Testcontainers officiel pour Meilisearch. Le test d'integration utilise donc un `GenericContainer` avec l'image `getmeili/meilisearch:v1.10` (meme tag que `docker-compose.yml`), port 7700 expose, `Wait.forHttp("/health")` comme strategie d'attente, et `MEILI_MASTER_KEY` positionnee via variable d'environnement du conteneur. Le test interroge ensuite Meilisearch directement via le client HTTP (SDK `com.meilisearch.sdk.Client`, le meme que celui utilise en production) pour verifier :
- apres `POST /api/v1/pieces` : le document `id` correspondant existe dans l'index avec les bons champs ;
- apres suppression manuelle du document via `PieceRechercheIndexService.desindexer(...)` (appele directement dans le test, en anticipation de #24, puisque aucun endpoint de retrait n'existe encore) : le document n'existe plus dans l'index.

Suivre le patron deja en place dans `PhotoIntegrationTest` (Testcontainers PostgreSQL + `@ServiceConnection`, conteneur additionnel declare avec `@Container`, proprietes injectees via `@DynamicPropertySource`).

## Risques / points d'attention

- **Coherence eventuelle index/base** : si Meilisearch est indisponible au moment du commit, la piece existe en base mais pas dans l'index (best-effort assume). Aucun mecanisme de rattrapage automatique n'est livre ici - a surveiller en exploitation (logs ERROR) et a traiter dans un ticket dedie si le taux d'echec observe le justifie.
- **Securite des donnees minimales indexees** : meme "minimal", le triplet nom/prenom/type suffit a identifier un titulaire ; Meilisearch doit rester protege par sa `MEILI_MASTER_KEY` (deja en place cote docker-compose) et ne jamais etre expose directement au public - seul le futur module de requetage (hors perimetre ici) doit y acceder, avec sa propre anonymisation de resultats cote API (masquage prevu par le PROJET paragraphes 7.2/10.2). Ne pas ouvrir le port Meilisearch publiquement.
- **`@TransactionalEventListener` et tests** : dans un test `@SpringBootTest` avec `MockMvc`, la transaction est ouverte/fermee par le controleur (pas par le test), donc `AFTER_COMMIT` se declenche normalement ; attention a ne pas englober l'appel MockMvc dans une transaction de test (`@Transactional` sur la classe de test), ce qui empecherait le commit et donc l'evenement de se declencher.
- **Lazy-loading de `Piece.getPoste()`** : le listener s'executant apres commit (potentiellement hors session Hibernate d'origine), le `PieceRechercheDocument` doit etre construit **avant** publication de l'evenement, dans `PieceService` ou la session est encore active, plutot que de relire `piece.getPoste().getNom()` depuis le listener, sous peine de `LazyInitializationException`.
- **Sequencement avec le ticket #24** : bien documenter dans le code (Javadoc sur `desindexer`) que cette methode n'est pas encore appelee en production, pour eviter toute confusion lors de la review ou une impression de fonctionnalite incomplete.

## Hors perimetre

- Toute implementation du retrait effectif de l'index au changement de statut (RETIREE/ARCHIVEE/SIGNALEE) - appartient au ticket #24.
- Ajout d'une methode generique `changerStatut()` sur `Piece`.
- L'API de recherche publique elle-meme (`POST /api/v1/recherche-publique`), le rate limiting/CAPTCHA associes, et l'anonymisation des resultats retournes au citoyen - ce sont des tickets de requetage separes qui consommeront cet index.
- Mecanisme de retry/file de messages (RabbitMQ) pour fiabiliser l'indexation.
- Job de reconciliation periodique index/base.
- Modification du schema PostgreSQL / nouvelle migration Flyway (aucune colonne supplementaire n'est necessaire sur `piece`).
