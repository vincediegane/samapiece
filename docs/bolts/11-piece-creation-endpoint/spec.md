# Spec — #11 Endpoint API de création d'une fiche pièce

## Résumé

Livrer `POST /api/v1/pieces` (rôles AGENT/CHEF_POSTE) qui hache le numéro de document côté serveur,
génère un numéro de fiche unique `PC-<posteHex8>-<année>-<séquence5>` et déduit `poste_id`/
`agent_createur_id` de l'agent authentifié.

## Tâches

- [ ] **Migration V5** — `backend/src/main/resources/db/migration/V5__create_piece_sequence_et_numero_fiche.sql` :
  avant d'écrire le fichier, vérifier qu'aucune donnée manuelle n'a été insérée dans `piece` sur les
  environnements où la migration sera jouée (`SELECT COUNT(*) FROM piece;` doit retourner 0 ; #10 n'a
  livré que l'entité, aucun chemin de code ne l'alimente avant ce ticket). Si la table n'est pas vide,
  adapter la migration avec un `DEFAULT` temporaire ou un backfill avant de rendre `numero_fiche` non
  nullable ; sinon appliquer le schéma tel quel (voir Contrat technique).
- [ ] **`Piece.java` étendu** — `backend/src/main/java/sn/samapiece/enregistrement/Piece.java` : ajouter
  le champ/colonne `numeroFiche` en **premier** paramètre du constructeur unique existant (pas de second
  constructeur), avec getter `getNumeroFiche()`. Aucun setter.
- [ ] **`PieceTest.java` mis à jour** — `backend/src/test/java/sn/samapiece/enregistrement/PieceTest.java` :
  mettre à jour `nouvellePiece()` avec le nouveau premier argument `numeroFiche`, et l'assertion
  `containsExactly(...)` du test `piece_shouldAvoirUnSeulConstructeurPublicNAcceptantAucunNumeroBrutCandidat`
  avec `String.class` en tête de liste. Ne pas affaiblir les deux autres tests existants (statut forcé
  DISPONIBLE, aucun champ n'expose le numéro en clair).
- [ ] **`PieceRepository`** — `backend/src/main/java/sn/samapiece/enregistrement/PieceRepository.java` :
  `JpaRepository<Piece, UUID>`, aucune méthode de requête supplémentaire.
- [ ] **`PieceNumeroFicheGenerator`** — `backend/src/main/java/sn/samapiece/enregistrement/PieceNumeroFicheGenerator.java` :
  `@Component`, upsert atomique via `JdbcTemplate` (SQL exact en Contrat technique).
- [ ] **`PieceService`** — `backend/src/main/java/sn/samapiece/enregistrement/PieceService.java` :
  orchestration `@Transactional` (résolution de l'appelant via `appelantCourant()` dupliqué du patron
  `AgentAdminService`, hachage via `NumeroDocumentHasher`, génération du numéro de fiche, construction
  et sauvegarde de `Piece`).
- [ ] **`CreerPieceRequest`** — `backend/src/main/java/sn/samapiece/enregistrement/web/CreerPieceRequest.java` :
  DTO requête Bean Validation, sans champ `posteId` ni `agentCreateurId`.
- [ ] **`PieceResponse`** — `backend/src/main/java/sn/samapiece/enregistrement/web/PieceResponse.java` :
  DTO réponse, factory `of(Piece)`, jamais de hash/sel/numéro en clair.
- [ ] **`PieceController`** — `backend/src/main/java/sn/samapiece/enregistrement/web/PieceController.java` :
  `POST /api/v1/pieces`, `@PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE')")`.
- [ ] **Test unitaire `CreerPieceRequestTest`** —
  `backend/src/test/java/sn/samapiece/enregistrement/web/CreerPieceRequestTest.java` : vérifie par
  réflexion que `CreerPieceRequest` n'expose aucun composant `posteId`/`agentCreateurId`.
- [ ] **Test d'intégration `PieceIntegrationTest`** —
  `backend/src/test/java/sn/samapiece/enregistrement/web/PieceIntegrationTest.java` : Testcontainers +
  MockMvc, même patron que `AgentAdminIntegrationTest` (création valide, 400 par champ manquant, 403 par
  rôle non autorisé, déduction poste/agent depuis le contexte, incrémentation de séquence).
- [ ] **Test d'intégration `PieceNumeroFicheGeneratorTest`** —
  `backend/src/test/java/sn/samapiece/enregistrement/PieceNumeroFicheGeneratorTest.java` : Testcontainers,
  deux appels concurrents sur le même `poste_id`/année ne doivent jamais produire le même numéro.

## Contrat technique

### Migration V5

```sql
ALTER TABLE piece ADD COLUMN numero_fiche VARCHAR(50) NOT NULL;
ALTER TABLE piece ADD CONSTRAINT uq_piece_numero_fiche UNIQUE (numero_fiche);

CREATE TABLE piece_sequence (
    poste_id       UUID NOT NULL REFERENCES poste(id),
    annee          INTEGER NOT NULL,
    dernier_numero INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (poste_id, annee)
);
```

### `Piece.java` — nouveau constructeur (numeroFiche en premier paramètre)

```java
@Column(name = "numero_fiche", nullable = false, unique = true, length = 50)
private String numeroFiche;

public Piece(
        String numeroFiche,
        Poste poste,
        Agent agentCreateur,
        TypeDocument typeDocument,
        String nomTitulaire,
        String prenomTitulaire,
        String numeroDocumentHash,
        String numeroDocumentSel,
        String numeroDocumentMasque,
        LocalDate dateNaissanceTitulaire,
        LocalDate dateDepot,
        String etatDocument,
        String remarques) {
    this.numeroFiche = numeroFiche;
    this.poste = poste;
    this.agentCreateur = agentCreateur;
    this.typeDocument = typeDocument;
    this.nomTitulaire = nomTitulaire;
    this.prenomTitulaire = prenomTitulaire;
    this.numeroDocumentHash = numeroDocumentHash;
    this.numeroDocumentSel = numeroDocumentSel;
    this.numeroDocumentMasque = numeroDocumentMasque;
    this.dateNaissanceTitulaire = dateNaissanceTitulaire;
    this.dateDepot = dateDepot;
    this.etatDocument = etatDocument;
    this.statut = StatutPiece.DISPONIBLE;
    this.remarques = remarques;
}

public String getNumeroFiche() {
    return numeroFiche;
}
```

`PieceTest` : la nouvelle assertion de réflexion doit être

```java
assertThat(constructeurs[0].getParameterTypes()).containsExactly(
        String.class,
        Poste.class,
        Agent.class,
        TypeDocument.class,
        String.class,
        String.class,
        String.class,
        String.class,
        String.class,
        LocalDate.class,
        LocalDate.class,
        String.class,
        String.class);
```

### `PieceNumeroFicheGenerator`

```java
package sn.samapiece.enregistrement;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PieceNumeroFicheGenerator {

    private static final String SQL_UPSERT_SEQUENCE = """
            INSERT INTO piece_sequence (poste_id, annee, dernier_numero)
            VALUES (?, ?, 1)
            ON CONFLICT (poste_id, annee)
            DO UPDATE SET dernier_numero = piece_sequence.dernier_numero + 1
            RETURNING dernier_numero
            """;

    private final JdbcTemplate jdbcTemplate;

    public PieceNumeroFicheGenerator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String genererNumeroFiche(UUID posteId, LocalDate dateDepot) {
        int annee = dateDepot.getYear();
        Integer sequence = jdbcTemplate.queryForObject(SQL_UPSERT_SEQUENCE, Integer.class, posteId, annee);
        String segmentPoste = posteId.toString().replace("-", "").substring(0, 8).toUpperCase();
        return "PC-%s-%d-%05d".formatted(segmentPoste, annee, sequence);
    }
}
```

Format exact : `PC-<8 hex majuscules>-<année 4 chiffres>-<séquence 5 chiffres avec zéros>`, exemple
`PC-3F2A9C1B-2026-00001`. Regex de vérification : `^PC-[0-9A-F]{8}-\d{4}-\d{5}$`. L'année utilisée est
`dateDepot.getYear()`, jamais l'horloge serveur. Appelé **dans la même transaction Spring** que
l'`INSERT` de `Piece` (`@Transactional` sur `PieceService.creer`) : rollback conjoint garanti.

### `PieceService`

```java
package sn.samapiece.enregistrement;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.samapiece.enregistrement.NumeroDocumentHasher.NumeroDocumentHache;
import sn.samapiece.enregistrement.web.CreerPieceRequest;
import sn.samapiece.enregistrement.web.PieceResponse;
import sn.samapiece.iam.Agent;
import sn.samapiece.iam.AgentRepository;
import sn.samapiece.iam.AccesRefuseException;
import sn.samapiece.referentiel.Poste;

@Service
public class PieceService {

    private final PieceRepository pieceRepository;
    private final AgentRepository agentRepository;
    private final PieceNumeroFicheGenerator numeroFicheGenerator;
    private final NumeroDocumentHasher numeroDocumentHasher;

    public PieceService(
            PieceRepository pieceRepository,
            AgentRepository agentRepository,
            PieceNumeroFicheGenerator numeroFicheGenerator,
            NumeroDocumentHasher numeroDocumentHasher) {
        this.pieceRepository = pieceRepository;
        this.agentRepository = agentRepository;
        this.numeroFicheGenerator = numeroFicheGenerator;
        this.numeroDocumentHasher = numeroDocumentHasher;
    }

    @Transactional
    public PieceResponse creer(CreerPieceRequest request) {
        Agent appelant = appelantCourant();
        Poste poste = appelant.getPoste();

        NumeroDocumentHache hache = numeroDocumentHasher.hacher(request.numeroDocument());
        String numeroFiche = numeroFicheGenerator.genererNumeroFiche(poste.getId(), request.dateDepot());

        Piece piece = new Piece(
                numeroFiche,
                poste,
                appelant,
                request.typeDocument(),
                request.nomTitulaire(),
                request.prenomTitulaire(),
                hache.hash(),
                hache.sel(),
                hache.masque(),
                request.dateNaissanceTitulaire(),
                request.dateDepot(),
                request.etatDocument(),
                request.remarques());

        pieceRepository.saveAndFlush(piece);
        return PieceResponse.of(piece);
    }

    private Agent appelantCourant() {
        String matricule = SecurityContextHolder.getContext().getAuthentication().getName();
        Agent appelant = agentRepository.findByMatricule(matricule)
                .orElseThrow(() -> new AccesRefuseException("Agent appelant introuvable."));
        if (!appelant.isActif()) {
            throw new AccesRefuseException("Agent appelant inactif.");
        }
        return appelant;
    }
}
```

`appelantCourant()` est une duplication assumée du même bloc dans `AgentAdminService` (décision de
design #6, pas d'extraction commune pour deux occurrences).

### `CreerPieceRequest`

```java
package sn.samapiece.enregistrement.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import sn.samapiece.enregistrement.TypeDocument;

public record CreerPieceRequest(
        @NotNull TypeDocument typeDocument,
        @NotBlank String nomTitulaire,
        @NotBlank String prenomTitulaire,
        @NotBlank String numeroDocument,
        LocalDate dateNaissanceTitulaire,
        @NotNull LocalDate dateDepot,
        String etatDocument,
        String remarques) {
}
```

Aucun composant `posteId` ni `agentCreateurId` : impossible à fournir par le client, quel que soit le
payload envoyé (contrairement à un simple "ignoré silencieusement" côté service).

### `PieceResponse`

```java
package sn.samapiece.enregistrement.web;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import sn.samapiece.enregistrement.Piece;

public record PieceResponse(
        UUID id,
        String numeroFiche,
        UUID posteId,
        UUID agentCreateurId,
        String typeDocument,
        String nomTitulaire,
        String prenomTitulaire,
        String numeroDocumentMasque,
        LocalDate dateNaissanceTitulaire,
        LocalDate dateDepot,
        String etatDocument,
        String statut,
        String remarques,
        OffsetDateTime creeLe) {

    public static PieceResponse of(Piece piece) {
        return new PieceResponse(
                piece.getId(),
                piece.getNumeroFiche(),
                piece.getPoste().getId(),
                piece.getAgentCreateur().getId(),
                piece.getTypeDocument().name(),
                piece.getNomTitulaire(),
                piece.getPrenomTitulaire(),
                piece.getNumeroDocumentMasque(),
                piece.getDateNaissanceTitulaire(),
                piece.getDateDepot(),
                piece.getEtatDocument(),
                piece.getStatut().name(),
                piece.getRemarques(),
                piece.getCreeLe());
    }
}
```

Jamais de `numeroDocumentHash`/`numeroDocumentSel`/`numeroDocument` en clair dans la réponse.

### `PieceController`

```java
package sn.samapiece.enregistrement.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.samapiece.enregistrement.PieceService;

@RestController
@RequestMapping("/api/v1/pieces")
public class PieceController {

    private final PieceService pieceService;

    public PieceController(PieceService pieceService) {
        this.pieceService = pieceService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE')")
    public ResponseEntity<PieceResponse> creer(@Valid @RequestBody CreerPieceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pieceService.creer(request));
    }
}
```

RBAC : `AGENT`/`CHEF_POSTE` uniquement, ensemble volontairement disjoint de celui d'`/api/v1/agents`
(`CHEF_POSTE`/`ADMIN_REGIONAL`/`ADMIN_NATIONAL`). Confirmé par le libellé du ticket ("réservé aux rôles
AGENT/CHEF_POSTE") : pas de dépannage ADMIN_REGIONAL/ADMIN_NATIONAL dans ce ticket, ce serait un
changement de périmètre à traiter par un ticket dédié si le besoin terrain se confirme. Aucune règle
`SecurityConfig` à ajouter : `/api/v1/pieces` n'est pas `permitAll`, donc `authenticated()` par défaut,
et `@PreAuthorize` restreint ensuite aux deux rôles.

### Contrat JSON (repris et validé du design)

Requête `POST /api/v1/pieces` :
```json
{
  "typeDocument": "CNI",
  "nomTitulaire": "Diop",
  "prenomTitulaire": "Awa",
  "numeroDocument": "1234567890123",
  "dateNaissanceTitulaire": "1990-05-12",
  "dateDepot": "2026-09-13",
  "etatDocument": "bon état",
  "remarques": "trouvée sur la voie publique"
}
```
Champs obligatoires : `typeDocument`, `nomTitulaire`, `prenomTitulaire`, `numeroDocument`, `dateDepot`
(alignés avec les colonnes `NOT NULL` de la migration V4). Nullable : `dateNaissanceTitulaire`,
`etatDocument`, `remarques`.

Réponse `201 Created` :
```json
{
  "id": "5b6c...",
  "numeroFiche": "PC-3F2A9C1B-2026-00001",
  "posteId": "b2e1...",
  "agentCreateurId": "9a01...",
  "typeDocument": "CNI",
  "nomTitulaire": "Diop",
  "prenomTitulaire": "Awa",
  "numeroDocumentMasque": "12●●●●●●●●●23",
  "dateNaissanceTitulaire": "1990-05-12",
  "dateDepot": "2026-09-13",
  "etatDocument": "bon état",
  "statut": "DISPONIBLE",
  "remarques": "trouvée sur la voie publique",
  "creeLe": "2026-09-13T10:15:30Z"
}
```
`posteId`/`agentCreateurId` proviennent uniquement de l'agent authentifié (`appelant.getPoste()` /
`appelant`), jamais du payload client (le DTO requête ne porte pas ces champs).

### Erreurs (aucun nouveau `@RestControllerAdvice`)

| Cas | Statut | Mécanisme |
|---|---|---|
| Champ obligatoire manquant/vide | 400 | Bean Validation native (`@Valid`), comme `CreerAgentRequest` |
| Rôle non autorisé | 403 | `@PreAuthorize` / Spring Security |
| Agent appelant introuvable ou désactivé | 403 | `AccesRefuseException` (réutilisée de `sn.samapiece.iam`), déjà mappée par `AgentAdminExceptionHandler` (`@RestControllerAdvice` global, non limité à `iam.web`) |

## Plan de tests

| Critère d'acceptation | Test | Emplacement |
|---|---|---|
| Réservé aux rôles AGENT/CHEF_POSTE | `creer_commeAgent_avecDonneesValides_shouldRetourner201`, `creer_commeChefPoste_avecDonneesValides_shouldRetourner201`, `creer_commeAdminRegional_shouldRetourner403`, `creer_commeAdminNational_shouldRetourner403`, `creer_commeAuditeur_shouldRetourner403` | `PieceIntegrationTest` |
| Valide les champs obligatoires (400) | Un test par champ obligatoire : `creer_sansTypeDocument_shouldRetourner400`, `creer_sansNomTitulaire_shouldRetourner400`, `creer_sansPrenomTitulaire_shouldRetourner400`, `creer_sansNumeroDocument_shouldRetourner400`, `creer_sansDateDepot_shouldRetourner400` | `PieceIntegrationTest` |
| Hash le numéro de document côté serveur | `creer_avecDonneesValides_shouldHacherNumeroDocumentCoteServeur` : lit la `Piece` persistée via `PieceRepository` et vérifie `numeroDocumentHash != numéro clair` et `numeroDocumentSel` non vide ; vérifie aussi côté JSON que `numeroDocumentHash`/`numeroDocumentSel`/`numeroDocument` sont absents de la réponse (`jsonPath(...).doesNotExist()`) et que `numeroDocumentMasque` ne contient pas le numéro en clair | `PieceIntegrationTest` |
| Génère un numéro de fiche unique retourné dans la réponse | `creer_avecDonneesValides_shouldRetournerNumeroFicheAuFormatAttendu` (`jsonPath("$.numeroFiche")` matche `^PC-[0-9A-F]{8}-\d{4}-\d{5}$`) ; `creer_deuxFoisMemePoste_shouldIncrementerLaSequence` (deux créations successives, séquences `00001` puis `00002`) | `PieceIntegrationTest` |
| Génération sûre sous concurrence (risque signalé par l'architecte, condition implicite de l'unicité) | `genererNumeroFiche_appeleConcurrentDeuxFois_shouldRetournerDeuxNumerosDistincts` : deux threads (`ExecutorService` + `CyclicBarrier` pour maximiser le chevauchement) appellent `PieceNumeroFicheGenerator.genererNumeroFiche` sur le même `poste_id`/année ; assertion que les deux résultats sont distincts et valent respectivement la séquence `00001`/`00002`, aucune exception | `PieceNumeroFicheGeneratorTest` |
| `poste_id`/`agent_createur_id` déduits du contexte, jamais du payload | (a) `CreerPieceRequestTest` : réflexion sur `CreerPieceRequest.class.getRecordComponents()`, assertion que la liste des noms ne contient ni `posteId` ni `agentCreateurId` (liste exacte attendue : `typeDocument, nomTitulaire, prenomTitulaire, numeroDocument, dateNaissanceTitulaire, dateDepot, etatDocument, remarques`). (b) `creer_commeAgentDePosteDonne_shouldRetournerPosteEtAgentDuContexte` : deux agents rattachés à deux postes différents créent chacun une fiche ; assertion que `posteId`/`agentCreateurId` de la réponse correspondent à l'agent authentifié, jamais à une valeur arbitraire | `CreerPieceRequestTest` (a), `PieceIntegrationTest` (b) |
| Tests d'intégration : création valide, champs manquants (400), rôle non autorisé (403) | Couvert par l'ensemble des tests `PieceIntegrationTest` ci-dessus | `PieceIntegrationTest` |
| Comportement additionnel (agent appelant désactivé après émission du token) | `creer_commeAgentDesactiveApresEmissionDuToken_shouldRetourner403AccesRefuse` (patron identique à `lister_commeChefPosteDesactiveApresEmissionDuToken_shouldRetourner403AccesRefuse` dans `AgentAdminIntegrationTest`) | `PieceIntegrationTest` |

Toutes les créations valides dans `PieceIntegrationTest` doivent utiliser le même patron que
`AgentAdminIntegrationTest` (`@Testcontainers` + `PostgreSQLContainer` + `@ServiceConnection`,
login réel via `/api/v1/auth/login` pour obtenir un token, `@BeforeEach` nettoyant `pieceRepository`,
`agentRepository`, `posteRepository`, `regionRepository` dans cet ordre).

## Écarts identifiés

Aucun écart bloquant entre `design.md` et les critères d'acceptation du ticket. Deux points ouverts
signalés par l'architecte sont tranchés ici pour éviter toute ambiguïté au codeur :

- **RBAC** : le ticket dit explicitement "réservé aux rôles AGENT/CHEF_POSTE" — pas de rôle ADMIN pour
  dépannage dans ce ticket. `@PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE')")` est donc la spec finale,
  sans extension. Un accès ADMIN de dépannage, si un besoin terrain se confirme, sera un ticket séparé.
- **Table `piece` supposée vide avant V5** : traité comme une tâche de vérification explicite en tête de
  checklist plutôt qu'une hypothèse implicite ; le codeur doit confirmer `SELECT COUNT(*) FROM piece = 0`
  sur l'environnement cible avant d'exécuter la migration telle quelle.
