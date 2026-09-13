# Spec — #18 Endpoint recherche publique anonymisée

## Résumé

Livre `POST /api/v1/recherche-publique` (public, sans authentification) : shortlist Meilisearch
tolérante aux fautes + vérification stricte et fraîche en PostgreSQL (statut, nom/prénom exacts,
numéro re-haché avec le sel de la ligne, date de naissance), avec repli PostgreSQL direct si
Meilisearch échoue, et une réponse anonymisée (`trouvé` + poste + référence de dossier, jamais
d'identité ni de numéro).

## Tâches

- [ ] `backend/src/main/java/sn/samapiece/enregistrement/PieceRepository.java` — ajouter
  `List<Piece> findByTypeDocumentAndStatutAndNomTitulaireIgnoreCase(TypeDocument typeDocument, StatutPiece statut, String nomTitulaire);`
  (utilisée uniquement par le chemin de repli PostgreSQL direct, voir Contrat technique).
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/NumeroDocumentHasher.java` — ajouter une
  méthode publique de vérification à sel connu (voir Contrat technique et **Écarts identifiés**,
  point 1) ; ne pas dupliquer l'algorithme SHA-256 ailleurs.
- [ ] `backend/src/test/java/sn/samapiece/enregistrement/NumeroDocumentHasherTest.java` — ajouter
  les cas de test de la nouvelle méthode `verifier(...)` (numéro correct, numéro incorrect, sel
  incorrect).
- [ ] `backend/src/main/java/sn/samapiece/recherche/CriteresInsuffisantsException.java` — nouvelle
  exception non vérifiée, sans message paramétrable (le message est fixé dans le handler).
- [ ] `backend/src/main/java/sn/samapiece/recherche/web/RecherchePubliqueRequest.java` — nouveau
  DTO requête (record) avec méthode `estSuffisant()`.
- [ ] `backend/src/test/java/sn/samapiece/recherche/web/RecherchePubliqueRequestTest.java` —
  nouveau test unitaire pur (sans Spring) couvrant la table de vérité de `estSuffisant()`.
- [ ] `backend/src/main/java/sn/samapiece/recherche/web/RecherchePubliqueResponse.java` — nouveau
  DTO réponse (record) avec fabriques statiques `trouve(...)` / `nonTrouve()`.
- [ ] `backend/src/main/java/sn/samapiece/recherche/RecherchePubliqueService.java` — nouveau
  service, logique complète décrite dans Contrat technique.
- [ ] `backend/src/main/java/sn/samapiece/recherche/web/RecherchePubliqueController.java` —
  `POST /api/v1/recherche-publique`.
- [ ] `backend/src/main/java/sn/samapiece/recherche/web/RecherchePubliqueExceptionHandler.java` —
  `@RestControllerAdvice`, mappe `CriteresInsuffisantsException` → 400, sur le patron de
  `sn.samapiece.iam.web.AuthExceptionHandler`.
- [ ] `backend/src/main/java/sn/samapiece/config/SecurityConfig.java` — ajouter
  `.requestMatchers(HttpMethod.POST, "/api/v1/recherche-publique").permitAll()` (même bloc que la
  ligne `GET /api/v1/postes`).
- [ ] `backend/src/test/java/sn/samapiece/recherche/web/RecherchePubliqueIntegrationTest.java` —
  nouveau test d'intégration Testcontainers (Postgres + Meilisearch), scénarios listés dans Plan de
  tests.
- [ ] `backend/src/test/java/sn/samapiece/recherche/web/RecherchePubliqueMeilisearchIndisponibleIntegrationTest.java`
  — nouveau test d'intégration dédié au repli PostgreSQL (Meilisearch injoignable).

## Contrat technique

### Requête / réponse HTTP

`POST /api/v1/recherche-publique`, `Content-Type: application/json`, pas d'en-tête
`Authorization` requis (`permitAll()`).

Corps de requête (`RecherchePubliqueRequest`) :

```json
{
  "typeDocument": "CNI",
  "nomTitulaire": "Fall",
  "prenomTitulaire": "Moussa",
  "numeroDocument": "1234567890123",
  "dateNaissanceTitulaire": "1990-05-12"
}
```

- `typeDocument` (enum `sn.samapiece.enregistrement.TypeDocument`, requis).
- `nomTitulaire` (String, requis, non vide après `isBlank()`).
- `prenomTitulaire` (String, optionnel — affine mais n'est jamais suffisant seul).
- `numeroDocument` (String, optionnel — numéro en clair).
- `dateNaissanceTitulaire` (`LocalDate`, format ISO `yyyy-MM-dd`, optionnel).
- Au moins un de `numeroDocument` / `dateNaissanceTitulaire` requis.

Pas d'annotations Bean Validation (`@Valid`/`@NotBlank`) sur ce DTO : la vérification passe
exclusivement par `estSuffisant()` appelé explicitement dans le service, pour que toute requête
syntaxiquement insuffisante produise exactement la même réponse 400, indépendamment du contenu —
une validation déléguée à Spring produirait une réponse différente (`MethodArgumentNotValidException`)
et casserait l'uniformité anti-énumération.

Réponse "trouvé" — 200 :

```json
{
  "trouve": true,
  "typeDocument": "CNI",
  "poste": {
    "nom": "Commissariat Central Dakar",
    "adresse": "Adresse Commissariat Central Dakar",
    "horaires": "{\"lundi\":{\"ouvert\":true,\"debut\":\"08:00\",\"fin\":\"18:00\"}}",
    "telephone": "+221338210000"
  },
  "referenceDossier": "PC-..."
}
```

Réponse "non trouvé" — 200 (même code, même forme, champs optionnels absents/`null`) :

```json
{ "trouve": false, "typeDocument": null, "poste": null, "referenceDossier": null }
```

Réponse "critères insuffisants" — 400, identique quel que soit le contenu soumis :

```json
{ "code": "CRITERES_INSUFFISANTS", "message": "Critères de recherche insuffisants : type, nom, et numéro ou date de naissance sont requis." }
```

Champs **jamais** présents dans aucune réponse : `nomTitulaire`, `prenomTitulaire`,
`numeroDocument`/`numeroDocumentMasque`, `dateNaissanceTitulaire`, tout champ photo.

### Signatures Java

`RecherchePubliqueRequest` (`sn.samapiece.recherche.web`) :

```java
public record RecherchePubliqueRequest(
        TypeDocument typeDocument,
        String nomTitulaire,
        String prenomTitulaire,
        String numeroDocument,
        LocalDate dateNaissanceTitulaire) {

    public boolean estSuffisant() {
        boolean typeEtNomPresents = typeDocument != null
                && nomTitulaire != null
                && !nomTitulaire.isBlank();
        boolean auMoinsUnDiscriminant =
                (numeroDocument != null && !numeroDocument.isBlank())
                        || dateNaissanceTitulaire != null;
        return typeEtNomPresents && auMoinsUnDiscriminant;
    }
}
```

`RecherchePubliqueResponse` (`sn.samapiece.recherche.web`) :

```java
public record RecherchePubliqueResponse(
        boolean trouve,
        TypeDocument typeDocument,
        PosteResume poste,
        String referenceDossier) {

    public record PosteResume(String nom, String adresse, String horaires, String telephone) {}

    public static RecherchePubliqueResponse nonTrouve() {
        return new RecherchePubliqueResponse(false, null, null, null);
    }

    public static RecherchePubliqueResponse trouve(TypeDocument typeDocument, Poste poste, String referenceDossier) {
        return new RecherchePubliqueResponse(
                true,
                typeDocument,
                new PosteResume(poste.getNom(), poste.getAdresse(), poste.getHoraires(), poste.getTelephone()),
                referenceDossier);
    }
}
```

`CriteresInsuffisantsException` (`sn.samapiece.recherche`) :

```java
public class CriteresInsuffisantsException extends RuntimeException {
}
```

`RecherchePubliqueExceptionHandler` (`sn.samapiece.recherche.web`), sur le patron de
`AuthExceptionHandler` :

```java
@RestControllerAdvice
public class RecherchePubliqueExceptionHandler {

    public record ErreurReponse(String code, String message) {}

    @ExceptionHandler(CriteresInsuffisantsException.class)
    public ResponseEntity<ErreurReponse> gererCriteresInsuffisants(CriteresInsuffisantsException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErreurReponse(
                "CRITERES_INSUFFISANTS",
                "Critères de recherche insuffisants : type, nom, et numéro ou date de naissance sont requis."));
    }
}
```

`RecherchePubliqueController` (`sn.samapiece.recherche.web`) :

```java
@RestController
@RequestMapping("/api/v1/recherche-publique")
public class RecherchePubliqueController {

    private final RecherchePubliqueService service;

    public RecherchePubliqueController(RecherchePubliqueService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<RecherchePubliqueResponse> rechercher(@RequestBody RecherchePubliqueRequest requete) {
        return ResponseEntity.ok(service.rechercher(requete));
    }
}
```

Ajout dans `NumeroDocumentHasher` (réutilise la méthode privée existante `calculerHash`, aucune
duplication de l'algorithme) :

```java
public boolean verifier(String numeroClair, String sel, String hashAttendu) {
    Objects.requireNonNull(numeroClair, "numeroClair");
    Objects.requireNonNull(sel, "sel");
    Objects.requireNonNull(hashAttendu, "hashAttendu");
    return calculerHash(sel, numeroClair).equals(hashAttendu);
}
```

Ajout dans `PieceRepository` :

```java
List<Piece> findByTypeDocumentAndStatutAndNomTitulaireIgnoreCase(
        TypeDocument typeDocument, StatutPiece statut, String nomTitulaire);
```

`RecherchePubliqueService` (`sn.samapiece.recherche`) — logique complète :

```java
@Service
public class RecherchePubliqueService {

    private static final Logger LOG = LoggerFactory.getLogger(RecherchePubliqueService.class);
    private static final int LIMITE_SHORTLIST = 20;

    private final Client client;
    private final MeilisearchProperties proprietes;
    private final PieceRepository pieceRepository;
    private final NumeroDocumentHasher hasher;

    public RecherchePubliqueService(
            Client client,
            MeilisearchProperties proprietes,
            PieceRepository pieceRepository,
            NumeroDocumentHasher hasher) {
        this.client = client;
        this.proprietes = proprietes;
        this.pieceRepository = pieceRepository;
        this.hasher = hasher;
    }

    public RecherchePubliqueResponse rechercher(RecherchePubliqueRequest requete) {
        if (!requete.estSuffisant()) {
            throw new CriteresInsuffisantsException();
        }

        return obtenirCandidats(requete).stream()
                .filter(candidat -> correspond(candidat, requete))
                .findFirst()
                .map(candidat -> RecherchePubliqueResponse.trouve(
                        candidat.getTypeDocument(), candidat.getPoste(), candidat.getNumeroFiche()))
                .orElseGet(RecherchePubliqueResponse::nonTrouve);
    }

    // Etape 1 : shortlist Meilisearch (typo-tolerante) -> reload par id en PostgreSQL.
    // Etape 2 (si Meilisearch leve une exception) : repli requete PostgreSQL directe bornee.
    private List<Piece> obtenirCandidats(RecherchePubliqueRequest requete) {
        try {
            return obtenirCandidatsViaMeilisearch(requete);
        } catch (Exception e) {
            LOG.warn("Meilisearch indisponible pour la recherche publique, repli sur PostgreSQL direct", e);
            return pieceRepository.findByTypeDocumentAndStatutAndNomTitulaireIgnoreCase(
                    requete.typeDocument(), StatutPiece.DISPONIBLE, requete.nomTitulaire());
        }
    }

    private List<Piece> obtenirCandidatsViaMeilisearch(RecherchePubliqueRequest requete) throws Exception {
        Index index = client.index(proprietes.getIndexPieces());
        SearchRequest searchRequest = new SearchRequest(construireTexteRequete(requete)).setLimit(LIMITE_SHORTLIST);
        Searchable resultats = index.search(searchRequest);
        List<UUID> ids = resultats.getHits().stream()
                .map(hit -> UUID.fromString((String) hit.get("id")))
                .toList();
        return ids.isEmpty() ? List.of() : pieceRepository.findAllById(ids);
    }

    // Texte libre : type + nom (+ prenom) — pas de clause filter= (voir Ecarts identifies, point 2).
    private String construireTexteRequete(RecherchePubliqueRequest requete) {
        StringBuilder texte = new StringBuilder(requete.typeDocument().name()).append(' ').append(requete.nomTitulaire());
        if (requete.prenomTitulaire() != null && !requete.prenomTitulaire().isBlank()) {
            texte.append(' ').append(requete.prenomTitulaire());
        }
        return texte.toString();
    }

    // Verification finale, la seule qui compte : statut frais, type/nom/prenom exacts, discriminants.
    // Si numero ET date sont tous deux fournis, les deux doivent correspondre (aucun retour anticipe
    // en cas de succes partiel).
    private boolean correspond(Piece candidat, RecherchePubliqueRequest requete) {
        if (candidat.getStatut() != StatutPiece.DISPONIBLE) {
            return false;
        }
        if (candidat.getTypeDocument() != requete.typeDocument()) {
            return false;
        }
        if (!candidat.getNomTitulaire().equalsIgnoreCase(requete.nomTitulaire())) {
            return false;
        }
        if (requete.prenomTitulaire() != null && !requete.prenomTitulaire().isBlank()
                && !candidat.getPrenomTitulaire().equalsIgnoreCase(requete.prenomTitulaire())) {
            return false;
        }
        boolean numeroFourni = requete.numeroDocument() != null && !requete.numeroDocument().isBlank();
        if (numeroFourni && !hasher.verifier(
                requete.numeroDocument(), candidat.getNumeroDocumentSel(), candidat.getNumeroDocumentHash())) {
            return false;
        }
        if (requete.dateNaissanceTitulaire() != null
                && !requete.dateNaissanceTitulaire().equals(candidat.getDateNaissanceTitulaire())) {
            return false;
        }
        return true;
    }
}
```

Règle de non-fuite dans les logs : `LOG.warn(...)` ne doit jamais recevoir `requete` ni aucun de
ses champs (nom, prénom, numéro, date de naissance potentiellement en clair) — uniquement un
message fixe et l'exception. Aucun autre point du contrôleur/service ne doit logger le corps de la
requête entrante.

RBAC : aucune annotation `@PreAuthorize` sur le contrôleur ; sécurité assurée uniquement par
`SecurityConfig` (`permitAll()`), même patron que `GET /api/v1/postes`.

## Plan de tests

| Exigence | Test |
|---|---|
| AC1 — critères minimaux exigés (type + nom + au moins un de numéro/date) | `RecherchePubliqueRequestTest` (table de vérité pure de `estSuffisant()`) + `RecherchePubliqueIntegrationTest` (variantes 400 : type manquant, nom manquant/vide, ni numéro ni date) |
| AC2 — réponse jamais numéro/photo/identité exacte du tiers | `RecherchePubliqueIntegrationTest#criteresSuffisantsAvecCorrespondance_...` : assertion explicite que les clés JSON de la réponse sont exactement `trouve, typeDocument, poste, referenceDossier` (aucune clé `nomTitulaire`/`prenomTitulaire`/`numeroDocument*`/`dateNaissanceTitulaire`) |
| AC3 — aucun résultat si critères trop vagues (anti-énumération) | Même scénarios 400 que AC1 : réponse `ErreurReponse` strictement identique (code + message) quel que soit le contenu soumis, avant tout accès Meilisearch/PostgreSQL |
| AC4 — tests d'intégration : suffisant+correspondance / insuffisant / aucune correspondance | 3 méthodes dédiées dans `RecherchePubliqueIntegrationTest` (voir liste ci-dessous) |
| Dérivé design — statut non DISPONIBLE jamais confirmé, même indexé DISPONIBLE dans Meilisearch | `RecherchePubliqueIntegrationTest#statutNonDisponible_...` : créer la pièce via `POST /api/v1/pieces` (indexée DISPONIBLE), puis `UPDATE piece SET statut = 'RETIREE'` via `JdbcTemplate` (aucun `changerStatut(...)` n'existe encore), rechercher → `trouve=false` |
| Dérivé design — numéro erroné (mauvais hash) jamais confirmé | `RecherchePubliqueIntegrationTest#numeroErrone_...` : pièce existante, `numeroDocument` fourni différent du numéro réel → `trouve=false` |
| Dérivé design — plusieurs discriminants fournis, un seul correspond → pas de match | `RecherchePubliqueIntegrationTest#plusieursCriteresUnSeulCorrespond_...` : (numéro correct + date fausse) et (numéro faux + date correcte) → `trouve=false` dans les deux cas |
| Dérivé design — repli PostgreSQL si Meilisearch indisponible | `RecherchePubliqueMeilisearchIndisponibleIntegrationTest` (contexte Spring dédié, `samapiece.meilisearch.host` pointant vers une adresse injoignable, ex. `http://127.0.0.1:1`) : pièce créée directement via les repositories (pas d'indexation possible), recherche avec critères suffisants et correspondants → `trouve=true` via le repli |
| Non-fonctionnel design — temps de traitement non dépendant du chemin trouvé/non-trouvé | Non testé automatiquement (test de timing jugé non fiable en CI) ; satisfait par construction : un unique code de traitement (`correspond` évalué pour chaque candidat) produit les deux issues, sans court-circuit basé sur les données |

Méthodes de `RecherchePubliqueIntegrationTest` couvrant AC4 (Testcontainers Postgres + Meilisearch,
même patron que `PieceIndexationIntegrationTest`, y compris le nettoyage `piece_sequence` avant
`posteRepository.deleteAll()`) :
- `criteresSuffisantsAvecCorrespondance_devraitRenvoyerTrouveAvecPosteEtReferenceDossier` : créer un
  agent + login + `POST /api/v1/pieces`, attendre l'indexation (réutiliser
  `attendreLaTacheDIndexationLaPlusRecente` du patron existant), puis
  `POST /api/v1/recherche-publique` avec type+nom+numéro exacts → 200, `trouve=true`,
  `referenceDossier` == `numeroFiche` renvoyé à la création, `poste.nom/adresse/horaires/telephone`
  == ceux du poste créé.
- `criteresInsuffisants_devraitRenvoyer400CriteresInsuffisantsQuelQueSoitLeContenu` (paramétré ou
  plusieurs `@Test`) : type manquant ; nom manquant ; nom vide/blanc ; ni numéro ni date → chaque
  cas attend 400 + `{"code":"CRITERES_INSUFFISANTS", ...}` identique.
- `aucuneCorrespondance_devraitRenvoyerTrouveFalseSansAucuneAutreDonnee` : critères suffisants, mais
  aucune pièce ne correspond (nom inexistant) → 200, `trouve=false`, autres champs absents/`null`.

## Écarts identifiés

1. **`NumeroDocumentHasher` non listé comme fichier modifié dans `design.md`, mais nécessaire.** Le
   design demande de « recalculer SHA-256(sel_ligne + numéro fourni) » pour la vérification finale,
   mais `NumeroDocumentHasher` n'expose aujourd'hui que `hacher(numeroClair)`, qui génère un
   *nouveau* sel aléatoire à chaque appel — impossible à réutiliser tel quel pour vérifier un
   candidat avec son sel déjà stocké. Deux options : (a) ajouter une méthode publique
   `verifier(numeroClair, sel, hashAttendu)` réutilisant la méthode privée existante
   `calculerHash` (retenu dans cette spec, diff minimal, source unique de vérité pour
   l'algorithme) ; (b) dupliquer le calcul SHA-256 directement dans
   `RecherchePubliqueService` (écarté : duplication d'un algorithme sensible à la sécurité, risque
   de divergence silencieuse si l'algorithme évolue). À confirmer avant codage si l'option (a) est
   acceptée telle quelle.
2. **Filtrage Meilisearch par `statut`/`typeDocument` non réalisable tel que formulé dans
   `design.md`.** Le design décrit une shortlist Meilisearch « restreinte à statut = DISPONIBLE »,
   ce qui suppose une clause `filter=` sur l'index. Or aucun attribut n'a été déclaré filtrable lors
   de #17 (pas d'appel à `updateFilterableAttributesSettings`, ni dans `MeilisearchConfig` ni
   ailleurs) ; une clause `filter` sur un attribut non filtrable fait échouer l'appel Meilisearch
   avec une erreur 400 SDK, ce qui déclencherait *systématiquement* le repli PostgreSQL et rendrait
   la shortlist Meilisearch inopérante en pratique. Cette spec retient donc de **ne pas** utiliser
   de clause `filter=` : le texte de requête Meilisearch combine simplement
   `typeDocument + nomTitulaire (+ prenomTitulaire)` en recherche plein texte (tous les champs du
   document sont indexés en texte par défaut, aucun réglage requis), et la restriction à
   `statut = DISPONIBLE` ainsi que l'exactitude du `typeDocument` sont entièrement assurées par la
   vérification PostgreSQL (`correspond(...)`), déjà obligatoire selon le design pour la fraîcheur
   du statut. Alternative écartée : modifier `MeilisearchConfig`/l'index pour déclarer des attributs
   filtrables — explicitement exclue par `design.md` (« Aucun fichier de #17 n'est modifié »). Aucun
   impact sur les garanties de confidentialité ou de fraîcheur du statut ; impact uniquement sur le
   rappel (recall) de la shortlist, jugé négligeable au volume pilote. À confirmer avant codage.
3. **Portée exacte de la nouvelle méthode `PieceRepository`, ambiguë dans `design.md`.** Le texte du
   design dit à la fois que la vérification finale « recharge les `Piece` candidates par id » (donc
   via `findAllById`, méthode déjà héritée de `JpaRepository`, sans besoin de nouvelle méthode) et
   que la nouvelle méthode `findByTypeDocumentAndStatutAndNomTitulaireIgnoreCase` est « utilisée pour
   la vérification finale et pour le repli ». Cette spec tranche : la nouvelle méthode est utilisée
   **uniquement** dans le chemin de repli (Meilisearch indisponible, pas d'ids de shortlist
   disponibles) ; le chemin normal recharge par id via `findAllById` (déjà disponible, aucune
   nouvelle méthode nécessaire pour ce chemin). Les deux chemins convergent ensuite vers la même
   fonction de vérification `correspond(...)`. À confirmer avant codage si cette interprétation est
   celle voulue par l'architecte.
