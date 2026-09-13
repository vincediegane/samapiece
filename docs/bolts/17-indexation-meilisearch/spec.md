# Spec — Ticket #17 : Indexation Meilisearch des pièces disponibles

## Résumé

À la création d'une `Piece` (statut `DISPONIBLE`), un document minimal (id, type, nom, prénom, poste, statut — jamais le numéro ni les photos) est poussé de manière asynchrone et best-effort dans l'index Meilisearch `pieces`, via un événement Spring publié après commit ; le retrait effectif de l'index au changement de statut n'est pas implémenté dans ce ticket (voir **Écarts identifiés**), seul le point d'extension `PieceRechercheIndexService.desindexer(UUID)` est livré et testé isolément.

## Tâches

- [ ] **1. Dépendance Maven** — `backend/pom.xml` : ajouter la dépendance `com.meilisearch.sdk:meilisearch-java` (scope par défaut, pas de scope `test`/`runtime`, elle est utilisée en production). Vérifier sur Maven Central la dernière version stable de la ligne `0.14.x` compatible avec un serveur Meilisearch `v1.10` (à la rédaction de cette spec, `0.14.4` est la dernière connue de cette ligne — figer la version exacte trouvée, ne pas laisser de range).
- [ ] **2. Document d'index** — créer `backend/src/main/java/sn/samapiece/recherche/PieceRechercheDocument.java` (record, voir contrat technique). Aucune dépendance vers `sn.samapiece.enregistrement.Piece` dans ce fichier (le mapping `Piece` → `PieceRechercheDocument` se fait dans `PieceService`, pas ici — évite un cycle de paquets `enregistrement` ↔ `recherche`).
- [ ] **3. Config Meilisearch** — créer `backend/src/main/java/sn/samapiece/recherche/MeilisearchProperties.java` (`@ConfigurationProperties(prefix = "samapiece.meilisearch")`, sur le modèle exact de `PhotoMinioProperties`).
- [ ] **4. Bean client Meilisearch** — créer `backend/src/main/java/sn/samapiece/recherche/MeilisearchConfig.java` (`@Configuration`, bean `com.meilisearch.sdk.Client`).
- [ ] **5. Service d'indexation** — créer `backend/src/main/java/sn/samapiece/recherche/PieceRechercheIndexService.java` (`indexer(PieceRechercheDocument)`, `desindexer(UUID)`, try/catch large + log ERROR, jamais de propagation).
- [ ] **6. Événement applicatif** — créer `backend/src/main/java/sn/samapiece/enregistrement/PieceIndexableEvent.java` (record portant un `PieceRechercheDocument` déjà construit).
- [ ] **7. Listener transactionnel** — créer `backend/src/main/java/sn/samapiece/recherche/PieceIndexationListener.java` (`@TransactionalEventListener(phase = AFTER_COMMIT)`, délègue à `PieceRechercheIndexService.indexer(...)`).
- [ ] **8. Câblage dans `PieceService`** — modifier `backend/src/main/java/sn/samapiece/enregistrement/PieceService.java` : injecter `ApplicationEventPublisher`, construire le `PieceRechercheDocument` juste après `pieceRepository.saveAndFlush(piece)` (session Hibernate encore active, donc `piece.getPoste().getNom()` résoluble sans `LazyInitializationException`), publier `PieceIndexableEvent`.
- [ ] **9. Config dev** — modifier `backend/src/main/resources/application-dev.yml` : ajouter le bloc `samapiece.meilisearch.*` sous le bloc `samapiece.minio.*` existant, même style de placeholders `${VAR:defaut-local}`.
- [ ] **10. docker-compose** — modifier `docker-compose.yml` : ajouter les variables Meilisearch à `environment:` du service `backend`, et ajouter `meilisearch: condition: service_healthy` à `depends_on:` du service `backend` (en plus de `minio` déjà présent).
- [ ] **11. `.env.example`** — ajouter `MEILISEARCH_INDEX_PIECES=pieces` dans la section `--- Meilisearch ---` existante (les variables `MEILI_MASTER_KEY` et `MEILISEARCH_HOST_PORT` existent déjà et sont réutilisées telles quelles pour le backend).
- [ ] **12. Test unitaire best-effort** — créer `backend/src/test/java/sn/samapiece/recherche/PieceRechercheIndexServiceTest.java` (Mockito, `Client`/`Index` mockés, vérifie qu'aucune exception n'est propagée quand Meilisearch échoue, et que `deleteDocument`/`addDocuments` sont appelés avec les bons arguments).
- [ ] **13. Test d'intégration principal** — créer `backend/src/test/java/sn/samapiece/recherche/PieceIndexationIntegrationTest.java` (Testcontainers Postgres + `GenericContainer` Meilisearch, patron `PhotoIntegrationTest`) : cohérence index/base après création, puis après désindexation directe de `PieceRechercheIndexService.desindexer(...)`.
- [ ] **14. Test d'intégration best-effort (Meilisearch injoignable)** — créer `backend/src/test/java/sn/samapiece/recherche/PieceIndexationBestEffortIntegrationTest.java` : `samapiece.meilisearch.host` pointé vers une adresse injoignable (pas de conteneur Meilisearch démarré), vérifie que `POST /api/v1/pieces` retourne quand même `201` (l'échec d'indexation ne doit jamais faire échouer la création de fiche).

## Contrat technique

### `PieceRechercheDocument` (record, package `sn.samapiece.recherche`)

```java
public record PieceRechercheDocument(
        UUID id,
        String typeDocument,
        String nomTitulaire,
        String prenomTitulaire,
        String poste,
        String statut) {
}
```

Sérialisé en JSON (via l'`ObjectMapper` Spring déjà présent dans le contexte), exactement 6 clés :

```json
{
  "id": "3f6a5e2c-...",
  "typeDocument": "CNI",
  "nomTitulaire": "Fall",
  "prenomTitulaire": "Moussa",
  "poste": "Commissariat Central Dakar",
  "statut": "DISPONIBLE"
}
```

**Interdits absolus dans ce document** (à vérifier explicitement en test, cf. plan de tests) : `numeroDocumentHash`, `numeroDocumentSel`, `numeroDocumentMasque`, `dateNaissanceTitulaire`, `remarques`, `etatDocument`, toute référence à une photo, `agentCreateur`.

### `MeilisearchProperties` (package `sn.samapiece.recherche`)

Sur le modèle exact de `PhotoMinioProperties` (`@Component @Validated @ConfigurationProperties(prefix = "samapiece.meilisearch")`, champs `@NotBlank` + getters/setters JavaBean) :

```java
private String host;        // ex. http://localhost:7700 (dev) / http://meilisearch:7700 (docker-compose)
private String apiKey;      // MEILI_MASTER_KEY
private String indexPieces; // nom de l'index, "pieces" par defaut
```

### `MeilisearchConfig` (package `sn.samapiece.recherche`)

```java
@Configuration
public class MeilisearchConfig {

    @Bean
    public Client meilisearchClient(MeilisearchProperties proprietes) {
        return new Client(new Config(proprietes.getHost(), proprietes.getApiKey()));
    }
}
```

### `PieceRechercheIndexService` (package `sn.samapiece.recherche`)

```java
@Service
public class PieceRechercheIndexService {

    private static final Logger LOG = LoggerFactory.getLogger(PieceRechercheIndexService.class);
    private static final String CLE_PRIMAIRE = "id";

    private final Client client;
    private final MeilisearchProperties proprietes;
    private final ObjectMapper objectMapper;

    public PieceRechercheIndexService(Client client, MeilisearchProperties proprietes, ObjectMapper objectMapper) {
        this.client = client;
        this.proprietes = proprietes;
        this.objectMapper = objectMapper;
    }

    /** Best-effort : toute exception (Meilisearch injoignable, timeout, etc.) est loguee en ERROR, jamais propagee. */
    public void indexer(PieceRechercheDocument document) {
        try {
            Index index = client.index(proprietes.getIndexPieces());
            String documentsJson = objectMapper.writeValueAsString(List.of(document));
            index.addDocuments(documentsJson, CLE_PRIMAIRE);
        } catch (Exception e) {
            LOG.error("Echec d'indexation Meilisearch pour la piece {}", document.id(), e);
        }
    }

    /**
     * Retire le document de l'index Meilisearch pour la piece donnee. Best-effort, memes garanties
     * que {@link #indexer(PieceRechercheDocument)}.
     *
     * <p>N'est appelee par aucun code de production a ce jour : {@code Piece} n'expose aucun
     * mecanisme de changement de statut apres construction. Prete a l'emploi pour le ticket #24
     * ("Workflow de retrait"), qui l'appellera depuis son futur {@code changerStatut(...)}.
     */
    public void desindexer(UUID pieceId) {
        try {
            Index index = client.index(proprietes.getIndexPieces());
            index.deleteDocument(pieceId.toString());
        } catch (Exception e) {
            LOG.error("Echec de desindexation Meilisearch pour la piece {}", pieceId, e);
        }
    }
}
```

Précision non couverte par le design (à la charge du codeur, choix d'implémentation) : ni `indexer` ni `desindexer` n'appellent `client.waitForTask(...)` en production — l'appel HTTP à Meilisearch est fire-and-forget une fois la tâche acceptée par le serveur, pour ne pas rallonger la réponse HTTP de `POST /api/v1/pieces` avec la latence d'indexation complète. Le test d'intégration, lui, doit appeler `waitForTask` explicitement pour éviter la flakiness (cf. plan de tests).

### `PieceIndexableEvent` (package `sn.samapiece.enregistrement`)

```java
public record PieceIndexableEvent(PieceRechercheDocument document) {
}
```

### `PieceIndexationListener` (package `sn.samapiece.recherche`)

```java
@Component
public class PieceIndexationListener {

    private final PieceRechercheIndexService indexService;

    public PieceIndexationListener(PieceRechercheIndexService indexService) {
        this.indexService = indexService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void surPieceIndexable(PieceIndexableEvent evenement) {
        indexService.indexer(evenement.document());
    }
}
```

### Modification de `PieceService.creer` (extrait)

```java
private final ApplicationEventPublisher eventPublisher; // nouveau champ, injecte au constructeur

@Transactional
public PieceResponse creer(CreerPieceRequest request) {
    // ... inchange jusqu'a la construction de `piece` ...

    pieceRepository.saveAndFlush(piece);

    PieceRechercheDocument document = new PieceRechercheDocument(
            piece.getId(),
            piece.getTypeDocument().name(),
            piece.getNomTitulaire(),
            piece.getPrenomTitulaire(),
            piece.getPoste().getNom(),
            piece.getStatut().name());
    eventPublisher.publishEvent(new PieceIndexableEvent(document));

    return PieceResponse.of(piece);
}
```

Aucun changement de signature publique de `PieceService.creer` ni de `PieceResponse` : la réponse HTTP de `POST /api/v1/pieces` est strictement inchangée.

### Configuration

`backend/src/main/resources/application-dev.yml`, sous le bloc `samapiece.minio` existant :

```yaml
samapiece:
  minio:
    # ... inchange ...
  meilisearch:
    host: ${MEILISEARCH_HOST:http://localhost:7700}
    api-key: ${MEILISEARCH_API_KEY:samapiece_dev_master_key_change_me}
    index-pieces: ${MEILISEARCH_INDEX_PIECES:pieces}
```

`docker-compose.yml`, service `backend` :

```yaml
    environment:
      # ... variables existantes inchangees ...
      MEILISEARCH_HOST: http://meilisearch:7700
      MEILISEARCH_API_KEY: ${MEILI_MASTER_KEY}
      MEILISEARCH_INDEX_PIECES: ${MEILISEARCH_INDEX_PIECES}
    depends_on:
      minio:
        condition: service_healthy
      meilisearch:
        condition: service_healthy
```

`.env.example`, section `--- Meilisearch ---` :

```
MEILI_MASTER_KEY=samapiece_dev_master_key_change_me
MEILISEARCH_HOST_PORT=7700
MEILISEARCH_INDEX_PIECES=pieces
```

Aucune migration Flyway, aucune colonne PostgreSQL supplémentaire (confirmé par le design, section "Hors périmètre").

## Plan de tests

| Critère d'acceptation du ticket | Test | Type |
|---|---|---|
| Document minimal (type, nom, prénom, poste, statut) indexé à la création en `DISPONIBLE`, jamais numéro complet ni photos | `PieceIndexationIntegrationTest#creer_commeAgent_devraitIndexerLaPieceAvecLesChampsAttendusEtSansDonneesSensibles` : `POST /api/v1/pieces` (201), `client.waitForTask(...)` pour synchroniser, puis `index.getRawDocument(pieceId, ...)` désérialisé en `Map<String,Object>` — assert `keySet()` égal exactement à `{id, typeDocument, nomTitulaire, prenomTitulaire, poste, statut}` (absence structurelle de `numeroDocumentHash`/`Sel`/`Masque`, `dateNaissanceTitulaire`, `remarques`, `etatDocument`, toute clé "photo*", `agentCreateur`) et assert des valeurs (`typeDocument="CNI"`, `nomTitulaire="Fall"`, `prenomTitulaire="Moussa"`, `poste=<nom du poste>`, `statut="DISPONIBLE"`) | Intégration (Testcontainers Postgres + GenericContainer Meilisearch) |
| Document minimal indexé à la création — construction correcte de l'appel au SDK | `PieceRechercheIndexServiceTest#indexer_devraitAppelerAddDocumentsAvecLaClePrimaireId` : mock `Client`/`Index`, vérifie `index.addDocuments(jsonContenantLesChampsAttendus, "id")` | Unitaire (Mockito) |
| Le document est retiré/mis à jour de l'index quand le statut change (RETIREE, ARCHIVEE, SIGNALEE) | **Non couvert par du code de production dans ce ticket** — voir Écarts identifiés. Seul le point d'extension est testé : `PieceIndexationIntegrationTest#desindexer_devraitSupprimerLeDocumentDeLIndexSansToucherALaBase` : après création + confirmation de présence, appel direct (dans le test) à `PieceRechercheIndexService.desindexer(pieceId)`, `client.waitForTask(...)`, puis assert que `index.getDocument(pieceId, ...)` lève une exception 404 (`MeilisearchException`), **et** assert que `pieceRepository.findById(pieceId)` reste présent en base avec son statut inchangé (la désindexation ne touche jamais PostgreSQL) | Intégration (test manuel du point d'extension, pas d'un déclenchement métier réel) |
| Le document est retiré/mis à jour de l'index quand le statut change — robustesse de la méthode | `PieceRechercheIndexServiceTest#desindexer_devraitAppelerDeleteDocumentAvecIdDeLaPiece` et `#desindexer_quandMeilisearchIndisponible_neDevraitPasPropagerException` | Unitaire (Mockito) |
| Test d'intégration vérifiant la cohérence index/base après création et après retrait | `PieceIndexationIntegrationTest` (les deux tests ci-dessus, dans la même classe, même patron `@Container`/`@DynamicPropertySource` que `PhotoIntegrationTest`) | Intégration |
| Non-fonctionnel implicite du design : un incident Meilisearch ne doit jamais faire échouer la création d'une fiche | `PieceIndexationBestEffortIntegrationTest#creer_quandMeilisearchInjoignable_devraitQuandMemeRetourner201` : `samapiece.meilisearch.host` pointé vers une adresse injoignable (aucun conteneur Meilisearch démarré dans cette classe de test), `POST /api/v1/pieces` doit retourner `201` et persister la `Piece` en base normalement | Intégration |
| Non-fonctionnel implicite : best-effort ne lève jamais d'exception au niveau du service d'indexation lui-même | `PieceRechercheIndexServiceTest#indexer_quandMeilisearchIndisponible_neDevraitPasPropagerException` : mock `Index.addDocuments(...)` levant `MeilisearchException`, assert `assertThatCode(() -> service.indexer(document)).doesNotThrowAnyException()` | Unitaire (Mockito) |

## Écarts identifiés

- **Critère d'acceptation 2 du ticket ("le document est retiré/mis à jour de l'index quand le statut change") n'est pas pleinement satisfait par ce ticket, de façon assumée.** `Piece` ne dispose aujourd'hui d'aucun mécanisme de changement de statut après construction (`statut` fixé à `DISPONIBLE` dans le constructeur, aucun setter, aucun endpoint de transition) : il est donc impossible d'implémenter un déclenchement réel de `desindexer(...)` tant que le ticket #24 ("Workflow de retrait") n'a pas introduit ce mécanisme. Le design tranche explicitement ce point et cette spec le confirme : livrer le point d'extension (`PieceRechercheIndexService.desindexer(UUID)`) + son test isolé, sans ajouter de `changerStatut()` générique sur `Piece` (qui empiéterait sur le périmètre de #24). **Décision à faire valider avant merge** : soit le ticket #17 est accepté comme "partiellement complété" avec un lien de suivi explicite vers #24 pour la fermeture du critère, soit ce critère doit être retiré du ticket #17 et déplacé formellement sur #24. Aucune alternative de contournement n'a été identifiée qui n'empiéterait pas sur le périmètre de #24 (toute simulation de changement de statut nécessiterait d'introduire précisément le mécanisme que #24 doit concevoir).
- Le 3e critère d'acceptation ("test d'intégration vérifiant la cohérence index/base après création et après retrait") est en revanche pleinement satisfiable et satisfait : il ne requiert qu'un test vérifiant le comportement de désindexation, pas un déclenchement métier réel — voir `PieceIndexationIntegrationTest` dans le plan de tests ci-dessus.
