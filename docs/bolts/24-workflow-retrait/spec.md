# Spec — Ticket #24 : Workflow de retrait avec double vérification d'identité

## Résumé

Ajouter à `PieceService`/`PieceController` trois endpoints REST (`retrait`, `signaler`, `debloquer`) qui font transiter une `Piece` entre `DISPONIBLE`/`RECLAMEE` et `RETIREE`/`LITIGE`/`SIGNALEE`, avec traçabilité du retrait dans une nouvelle table `retrait` et RBAC/périmètre poste, via la migration Flyway `V9`.

## Décisions fermées (points ouverts de l'architecte)

1. **Double vérification d'identité** : pas de rapprochement automatique `nomReclamant` vs `nomTitulaire`/`prenomTitulaire`, pas de rejet 409/422 en cas de non-concordance. `nomReclamant` et `pieceJustificativePresentee` sont enregistrés tels que saisis par l'agent, en texte libre, sur la seule foi de son jugement. Confirmé : interprétation de l'architecte retenue telle quelle, sans sur-ingénierie.
2. **AC2 vs AC3** : confirmé — tout statut différent de `DISPONIBLE`/`RECLAMEE` bloque le retrait (`RETIREE`, `ARCHIVEE`, `LITIGE`, `SIGNALEE`, et par extension `DETRUITE`, cf. Écarts identifiés #1) et exige un déblocage préalable par un rôle habilité.
3. **RBAC** : rôles vérifiés dans `sn.samapiece.iam.Role` (`AGENT`, `CHEF_POSTE`, `ADMIN_REGIONAL`, `ADMIN_NATIONAL`, `AUDITEUR`).
   - `retrait` / `signaler` : `hasAnyRole('AGENT','CHEF_POSTE')`, périmètre = comparaison directe `appelant.getPoste().getId().equals(piece.getPoste().getId())` (même pattern que `PhotoService.uploader`, pas de `PerimetrePoste`).
   - `debloquer` : `hasAnyRole('CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')`, périmètre via `PerimetrePoste.estDansPerimetre(appelant, piece.getPoste())` (même pattern que `PhotoService.telecharger`).
4. **Signatures et DTOs** : fixées ci-dessous (Contrat technique). Pas de `RetraitResponse` dédié — réutilisation de `PieceResponse.of(piece)` pour les 3 endpoints (200), cohérent avec le principe de minimisation du changement : les détails du retrait/signalement/déblocage (réclamant, motif, auteur) sont vérifiables en base via les repositories dans les tests, pas exposés par l'API (aucun AC ne le demande).
5. **Migration** : `V8__index_alerte_correspondance.sql` est la dernière migration sur `main` → la nouvelle migration est bien `V9__create_retrait_et_signalement.sql`. Schéma figé ci-dessous.

## Écarts identifiés

1. **Statut `DETRUITE` non traité explicitement par le design ni par les AC** : `DETRUITE` existe dans `StatutPiece`/le `CHECK` SQL mais n'apparaît dans aucune liste de l'architecte (ni AC2, ni la liste des statuts débloquables de l'endpoint `debloquer`). Décision : `DETRUITE` bloque le retrait comme tout statut ≠ `DISPONIBLE`/`RECLAMEE` (409), mais **n'est volontairement pas inclus** dans les statuts éligibles au déblocage (`RETIREE`, `ARCHIVEE`, `LITIGE`, `SIGNALEE` uniquement) — une pièce détruite ne redevient pas disponible. Une tentative de `debloquer` sur une pièce `DETRUITE` doit donc aussi retourner 409 (`TransitionStatutInterditeException`). À valider par le reviewer si un statut encore plus explicite est souhaité, mais aucun AC ne couvre ce cas : pas de test automatisé dédié requis, seulement la cohérence de la précondition dans `Piece.debloquer(...)`.
2. **Re-indexation recherche non mentionnée par le design** : `PieceService.creer` publie un `PieceIndexableEvent` pour indexer `PieceRechercheDocument.statut` dans le module `recherche`. Le design ne prévoit aucune republication de cet évènement lors des transitions de retrait/signalement/déblocage, ce qui laisserait l'index de recherche afficher un statut `disponible` obsolète après un retrait réel — incohérent avec l'objectif même du ticket (une pièce retirée ne doit plus apparaître comme disponible). Décision : chaque transition (`retirer`, `signaler`, `debloquer`) republie un `PieceIndexableEvent` avec le `PieceRechercheDocument` à jour, en réutilisant le pattern déjà en place dans `PieceService.creer` (aucune nouvelle dépendance : `ApplicationEventPublisher` est déjà injecté). Aucun AC ne le demande explicitement, mais c'est nécessaire à la cohérence fonctionnelle du workflow ; à signaler au reviewer comme ajout au périmètre du design.
3. **Colonne `date_retrait` supprimée par simplification** : le design listait `date_retrait` et `cree_le` comme deux colonnes distinctes sur `retrait`. Ce ticket ne couvre pas la saisie offline (cf. Hors périmètre du design), donc un retrait est toujours enregistré au moment où il se produit : les deux valeurs seraient toujours identiques. Décision : une seule colonne `cree_le` (non modifiable, `DEFAULT now()`), suivant exactement le pattern de `Photo.creeLe`. Pas de perte d'information vis-à-vis des AC.

## Rappels de vigilance (mémoire projet, à respecter par le codeur)

- Ne pas ajouter de `@ExceptionHandler(PieceIntrouvableException.class)` ni de `@ExceptionHandler(AccesRefuseException.class)` dans le nouveau `PieceExceptionHandler` : déjà gérés globalement par `PhotoExceptionHandler` (404) et `AgentAdminExceptionHandler` (403). Un doublon d'`@ExceptionHandler` sur un même type casse le démarrage Spring. Le nouveau `PieceExceptionHandler` ne doit gérer **que** `TransitionStatutInterditeException` (409). Une `IllegalArgumentException` (ex. `statutCible` invalide dans `signaler`) est déjà mappée en 400 par `AgentAdminExceptionHandler` — ne pas la re-gérer non plus.
- Toute assertion MockMvc sur un corps de réponse contenant du texte accentué (messages d'erreur en français) doit utiliser `getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)`, jamais `getContentAsString()` seul.
- Le `@SpringBootTest` étendu (`PieceIntegrationTest`) crée déjà des `Piece`/`Poste` et vide `piece_sequence` avant `posteRepository.deleteAll()` dans `@BeforeEach nettoyer()` — si de nouvelles entités (`Retrait`) sont créées dans les nouveaux tests, ajouter leur nettoyage (`retraitRepository.deleteAll()`) **avant** `pieceRepository.deleteAll()` dans cette même méthode (FK `retrait.piece_id`), sans perturber l'ordre existant `piece_sequence` → `Poste`.
- Pas de `@Version` (verrou optimiste) sur `Piece` ni `Retrait` pour ce ticket — la relecture du statut dans la transaction `@Transactional` de `PieceService` suffit, ne pas sur-ingénierer la concurrence.
- `Piece` reste sans setter générique : `retirer()`, `signaler(...)`, `debloquer(...)` sont des méthodes métier qui valident la transition en interne et lancent `TransitionStatutInterditeException` le cas échéant, suivant le pattern `Agent.desactiver()`/`Agent.modifierInformations(...)`.

## Contrat technique

### Migration `backend/src/main/resources/db/migration/V9__create_retrait_et_signalement.sql`

```sql
CREATE TABLE retrait (
    id                             UUID PRIMARY KEY,
    piece_id                       UUID NOT NULL REFERENCES piece(id),
    agent_validateur_id            UUID NOT NULL REFERENCES agent(id),
    nom_reclamant                  VARCHAR(255) NOT NULL,
    piece_justificative_presentee  TEXT NOT NULL,
    cree_le                        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_retrait_piece_id ON retrait(piece_id);
CREATE INDEX idx_retrait_agent_validateur_id ON retrait(agent_validateur_id);

ALTER TABLE piece
    ADD COLUMN signale_par_id    UUID REFERENCES agent(id),
    ADD COLUMN signale_le        TIMESTAMPTZ,
    ADD COLUMN motif_signalement TEXT,
    ADD COLUMN debloque_par_id   UUID REFERENCES agent(id),
    ADD COLUMN debloque_le       TIMESTAMPTZ,
    ADD COLUMN motif_deblocage   TEXT;
```

Pas de modification du `CHECK` sur `piece.statut` (V4) : `RETIREE`, `LITIGE`, `ARCHIVEE`, `SIGNALEE` y sont déjà présents en minuscules via `StatutPieceConverter`.

### `Piece.java` — nouvelles colonnes et méthodes

Colonnes ajoutées (nullable, mêmes conventions `@ManyToOne(fetch = FetchType.LAZY)` + `@JoinColumn` que `agentCreateur`/`poste`) :
`signalePar` (Agent, `signale_par_id`), `signaleLe` (OffsetDateTime, `signale_le`), `motifSignalement` (String, `motif_signalement`), `debloquePar` (Agent, `debloque_par_id`), `debloqueLe` (OffsetDateTime, `debloque_le`), `motifDeblocage` (String, `motif_deblocage`), avec getters correspondants.

Méthodes métier (aucun setter générique) :

```java
public void retirer() {
    if (statut != StatutPiece.DISPONIBLE && statut != StatutPiece.RECLAMEE) {
        throw new TransitionStatutInterditeException(id, statut, "retrait");
    }
    this.statut = StatutPiece.RETIREE;
}

public void signaler(StatutPiece statutCible, String motif, Agent signalePar) {
    if (statutCible != StatutPiece.LITIGE && statutCible != StatutPiece.SIGNALEE) {
        throw new IllegalArgumentException("statutCible doit etre LITIGE ou SIGNALEE.");
    }
    if (statut != StatutPiece.DISPONIBLE && statut != StatutPiece.RECLAMEE) {
        throw new TransitionStatutInterditeException(id, statut, "signalement");
    }
    this.statut = statutCible;
    this.signalePar = signalePar;
    this.signaleLe = OffsetDateTime.now();
    this.motifSignalement = motif;
}

public void debloquer(String motif, Agent debloquePar) {
    if (statut != StatutPiece.RETIREE && statut != StatutPiece.ARCHIVEE
            && statut != StatutPiece.LITIGE && statut != StatutPiece.SIGNALEE) {
        throw new TransitionStatutInterditeException(id, statut, "deblocage");
    }
    this.statut = StatutPiece.DISPONIBLE;
    this.debloquePar = debloquePar;
    this.debloqueLe = OffsetDateTime.now();
    this.motifDeblocage = motif;
}
```

Le rapprochement `nomReclamant` (donnée du retrait) vs `nomTitulaire`/`prenomTitulaire` n'est **pas** implémenté (cf. Décisions fermées #1).

### Nouvelle entité `backend/src/main/java/sn/samapiece/enregistrement/Retrait.java`

Table `retrait`, pattern identique à `Photo.java` (id généré, `protected Retrait() {}`, constructeur complet, getters, `equals`/`hashCode` par id) :
`id`, `piece` (`@ManyToOne` obligatoire, `piece_id`), `agentValidateur` (`@ManyToOne` obligatoire, `agent_validateur_id`), `nomReclamant` (String, non null), `pieceJustificativePresentee` (String, non null), `creeLe` (OffsetDateTime, `insertable = false, updatable = false`).

Constructeur : `Retrait(Piece piece, Agent agentValidateur, String nomReclamant, String pieceJustificativePresentee)`.

### Nouveau `backend/src/main/java/sn/samapiece/enregistrement/RetraitRepository.java`

```java
public interface RetraitRepository extends JpaRepository<Retrait, UUID> {
    List<Retrait> findByPieceIdOrderByCreeLeDesc(UUID pieceId);
}
```

### Nouvelle exception `backend/src/main/java/sn/samapiece/enregistrement/TransitionStatutInterditeException.java`

```java
public class TransitionStatutInterditeException extends RuntimeException {
    public TransitionStatutInterditeException(UUID pieceId, StatutPiece statutActuel, String action) {
        super("Transition refusee pour la piece " + pieceId + " : action=" + action
                + ", statut actuel=" + statutActuel);
    }
}
```

### `PieceService.java` — nouvelles méthodes

```java
@Transactional
public PieceResponse retirer(UUID pieceId, RetraitRequest request);

@Transactional
public PieceResponse signaler(UUID pieceId, SignalerRequest request);

@Transactional
public PieceResponse debloquer(UUID pieceId, DeblocageRequest request);
```

Chacune : `Agent appelant = appelantCourant();` → `Piece piece = pieceRepository.findById(pieceId).orElseThrow(() -> new PieceIntrouvableException(pieceId));` → vérification de périmètre (voir RBAC ci-dessus, lève `AccesRefuseException` si hors périmètre, déjà géré globalement en 403) → transition via la méthode métier de `Piece` (qui lève `TransitionStatutInterditeException` si le statut ne l'autorise pas) → pour `retirer` uniquement : construction et `retraitRepository.saveAndFlush(new Retrait(piece, appelant, request.nomReclamant(), request.pieceJustificativePresentee()))` → republication d'un `PieceIndexableEvent` avec un `PieceRechercheDocument` reconstruit à partir de la `piece` à jour (cf. Écarts identifiés #2) → retour `PieceResponse.of(piece)`.

`appelantCourant()` : réutiliser tel quel (méthode privée déjà existante, inchangée).

### DTOs (`backend/src/main/java/sn/samapiece/enregistrement/web/`)

```java
public record RetraitRequest(
        @NotBlank String nomReclamant,
        @NotBlank String pieceJustificativePresentee) {}

public record SignalerRequest(
        @NotNull StatutPiece statutCible,
        @NotBlank String motif) {}

public record DeblocageRequest(
        @NotBlank String motif) {}
```

Pas de champ `commentaire` optionnel (retiré du design initial : non demandé par les AC, évite d'élargir le contrat sans besoin).

### `PieceController.java` — nouveaux endpoints

```java
@PostMapping("/{id}/retrait")
@PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE')")
public ResponseEntity<PieceResponse> retirer(@PathVariable UUID id, @Valid @RequestBody RetraitRequest request);
// 200 PieceResponse (statut = RETIREE) ; 409 si statut courant ∉ {DISPONIBLE, RECLAMEE} ;
// 403 si hors périmètre poste ; 400 si nomReclamant/pieceJustificativePresentee absents ;
// 404 si piece introuvable.

@PostMapping("/{id}/signaler")
@PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE')")
public ResponseEntity<PieceResponse> signaler(@PathVariable UUID id, @Valid @RequestBody SignalerRequest request);
// 200 PieceResponse (statut = LITIGE ou SIGNALEE) ; 409 si statut courant ∉ {DISPONIBLE, RECLAMEE} ;
// 400 si statutCible ∉ {LITIGE, SIGNALEE} ou motif absent ; 403 hors périmètre ; 404 piece introuvable.

@PostMapping("/{id}/debloquer")
@PreAuthorize("hasAnyRole('CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')")
public ResponseEntity<PieceResponse> debloquer(@PathVariable UUID id, @Valid @RequestBody DeblocageRequest request);
// 200 PieceResponse (statut = DISPONIBLE) ; 409 si statut courant ∉ {RETIREE, ARCHIVEE, LITIGE, SIGNALEE} ;
// 400 si motif absent ; 403 hors périmètre (poste ou région) ; 404 piece introuvable.
```

### Nouveau `backend/src/main/java/sn/samapiece/enregistrement/web/PieceExceptionHandler.java`

```java
@RestControllerAdvice
public class PieceExceptionHandler {
    public record ErreurReponse(String code, String message) {}

    @ExceptionHandler(TransitionStatutInterditeException.class)
    public ResponseEntity<ErreurReponse> gererTransitionInterdite(TransitionStatutInterditeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErreurReponse("TRANSITION_STATUT_INTERDITE", ex.getMessage()));
    }
}
```

Aucun autre `@ExceptionHandler` dans cette classe (cf. Rappels de vigilance).

## Tâches

- [ ] `backend/src/main/resources/db/migration/V9__create_retrait_et_signalement.sql` — créer la table `retrait` + index, `ALTER TABLE piece` pour les 6 colonnes signalement/déblocage, selon le schéma figé ci-dessus.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/Piece.java` — ajouter les 6 champs/colonnes, leurs getters, et les méthodes `retirer()`, `signaler(StatutPiece, String, Agent)`, `debloquer(String, Agent)` avec les préconditions figées ci-dessus.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/Retrait.java` (nouveau) — entité JPA `retrait`, pattern `Photo.java`.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/RetraitRepository.java` (nouveau) — `JpaRepository<Retrait, UUID>` + `findByPieceIdOrderByCreeLeDesc`.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/TransitionStatutInterditeException.java` (nouveau) — exception 409, porte `pieceId`, `statutActuel`, `action`.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/web/RetraitRequest.java`, `SignalerRequest.java`, `DeblocageRequest.java` (nouveaux) — records de validation figés ci-dessus.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/PieceService.java` — ajouter `retirer`, `signaler`, `debloquer` (signatures et logique ci-dessus), y compris la republication `PieceIndexableEvent` après chaque transition.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/web/PieceController.java` — 3 nouveaux endpoints `POST /{id}/retrait`, `POST /{id}/signaler`, `POST /{id}/debloquer` avec les `@PreAuthorize` figés.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/web/PieceExceptionHandler.java` (nouveau) — `@RestControllerAdvice` gérant uniquement `TransitionStatutInterditeException` → 409, sans redéclarer les handlers déjà globaux.
- [ ] `backend/src/test/java/sn/samapiece/enregistrement/PieceServiceTest.java` — étendre : tests unitaires Mockito pour `retirer`/`signaler`/`debloquer` (transition valide, transition refusée par précondition, contrôle de périmètre poste, absence de rapprochement de nom).
- [ ] `backend/src/test/java/sn/samapiece/enregistrement/web/PieceIntegrationTest.java` — étendre : ajouter le nettoyage `retraitRepository.deleteAll()` dans `@BeforeEach nettoyer()` (avant `pieceRepository.deleteAll()`), puis les tests d'intégration MockMvc listés dans le Plan de tests.

## Plan de tests

| AC / cas | Test | Emplacement |
|---|---|---|
| AC1 — retrait valide enregistre réclamant/pièce justificative/agent validateur et passe `RETIREE` | `retirer_commeAgentDuPosteDeLaPiece_avecPieceDisponible_shouldRetourner200EtPasserRetiree` : POST `/retrait`, vérifie `status 200`, `$.statut == "RETIREE"`, puis recharge la `Piece` (statut) et l'unique `Retrait` via `retraitRepository.findByPieceIdOrderByCreeLeDesc` pour vérifier `nomReclamant`, `pieceJustificativePresentee`, `agentValidateur.getId()` | `PieceIntegrationTest` (intégration) |
| AC1 (unitaire) — `PieceService.retirer` construit et persiste le `Retrait` avec les bons champs | `retirer_avecStatutDisponible_shouldPersisterRetraitEtPasserStatutARetiree` (Mockito : capture de l'argument passé à `retraitRepository.saveAndFlush`) | `PieceServiceTest` |
| AC2 — retrait impossible sur pièce déjà `RETIREE`/`ARCHIVEE`/`LITIGE` sans déblocage | `retirer_survienneSurPieceDejaRetiree_shouldRetourner409` (piece créée directement en base avec statut `RETIREE` via `pieceRepository.save`, tentative de retrait → 409, corps `$.code == "TRANSITION_STATUT_INTERDITE"`) + variantes paramétrées ou tests dédiés pour `ARCHIVEE` et `LITIGE` | `PieceIntegrationTest` |
| AC2 (unitaire) — précondition de `Piece.retirer()`/`Piece.debloquer(...)` | Tests unitaires directs sur `Piece` (sans passer par le service) pour chaque statut non autorisé, vérifiant la levée de `TransitionStatutInterditeException` ; test dédié confirmant que `DETRUITE` est refusé aussi bien en retrait qu'en déblocage (cf. Écarts identifiés #1) | Nouveau `backend/src/test/java/sn/samapiece/enregistrement/PieceTest.java` (ou classe de test dédiée à l'entité si elle n'existe pas déjà — vérifier son absence avant création) |
| AC3 — signalement marque `LITIGE` ou `SIGNALEE` et bloque un retrait ultérieur | `signaler_commeAgentDuPoste_avecStatutCibleLitige_shouldRetourner200EtPasserLitige` puis, à la suite, `retirer_survienneApresSignalement_shouldRetourner409` (deux appels MockMvc successifs sur la même pièce) ; idem pour `SIGNALEE` | `PieceIntegrationTest` |
| AC3 (validation) — `statutCible` invalide | `signaler_avecStatutCibleInvalide_shouldRetourner400` (`statutCible = "DISPONIBLE"` par ex.) | `PieceIntegrationTest` |
| AC4 — déblocage permettant un nouveau retrait | `debloquer_commeChefPoste_survienneSurPieceRetiree_shouldRetourner200PuisPermettreNouveauRetrait` : retrait → 200, déblocage → 200 (`$.statut == "DISPONIBLE"`), nouveau retrait → 200 | `PieceIntegrationTest` |
| RBAC retrait/signaler — `ADMIN_REGIONAL`/`ADMIN_NATIONAL`/`AUDITEUR` refusés | `retirer_commeAdminRegional_shouldRetourner403`, `signaler_commeAuditeur_shouldRetourner403` (pattern identique à `creer_commeAdminRegional_shouldRetourner403` existant) | `PieceIntegrationTest` |
| RBAC déblocage — `AGENT` refusé, `CHEF_POSTE`/`ADMIN_REGIONAL`/`ADMIN_NATIONAL` autorisés | `debloquer_commeAgent_shouldRetourner403`, `debloquer_commeAdminRegionalHorsRegion_shouldRetourner403`, `debloquer_commeAdminRegionalDeLaMemeRegion_shouldRetourner200` | `PieceIntegrationTest` |
| Périmètre poste — agent d'un autre poste refusé sur retrait/signalement | `retirer_commeAgentDunAutrePoste_shouldRetourner403` | `PieceIntegrationTest` |
| Validation des champs requis | `retirer_sansNomReclamant_shouldRetourner400`, `retirer_sansPieceJustificativePresentee_shouldRetourner400`, `signaler_sansMotif_shouldRetourner400`, `debloquer_sansMotif_shouldRetourner400` | `PieceIntegrationTest` |
| Non sur-ingénierie — pas de blocage sur non-concordance de nom | `retirer_avecNomReclamantDifferentDuNomTitulaire_shouldRetourner200QuandMemeSansOCR` (documente explicitement le choix : le nom déclaré diffère de `nomTitulaire`/`prenomTitulaire`, le retrait aboutit quand même) | `PieceIntegrationTest` |
| Cohérence index recherche (Écarts identifiés #2) | `retirer_shouldRepublierPieceIndexableEventAvecStatutRetiree` (Mockito, vérifie via `ArgumentCaptor` que le second `PieceIndexableEvent` publié porte `statut = "RETIREE"`) | `PieceServiceTest` |
| Encodage UTF-8 des messages d'erreur | Toutes les assertions de corps de réponse d'erreur (409/400) utilisent `getResponse().getContentAsString(StandardCharsets.UTF_8)` | `PieceIntegrationTest` |
