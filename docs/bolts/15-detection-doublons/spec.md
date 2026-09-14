# Spec — Ticket #15 : Détection de doublons à la création d'une fiche

## Résumé

`PieceService.creer(...)` détecte, avant persistance, un doublon potentiel par correspondance exacte du numéro de document (hash salé vérifié en mémoire sur une shortlist `typeDocument` + statuts actifs), bloque en 409 (`DOUBLON_POTENTIEL`, numéros de fiche candidats uniquement) sauf si l'agent confirme explicitement via `confirmerMalgreDoublon` dans `CreerPieceRequest`, auquel cas la création aboutit en 201 et la fiche porte une colonne persistante `cree_malgre_doublon` (migration `V11`, qui ajoute aussi un index composite `(type_document, statut)`).

## Décisions fermées (points ouverts du design)

1. **Nom du champ de confirmation forcée** : `confirmerMalgreDoublon`, `boolean` primitif (pas `Boolean`) dans `CreerPieceRequest`, dernier champ du record, sans annotation de validation. Absent du JSON → Jackson désérialise le record avec `false` (comportement standard pour un composant primitif non fourni), donc « absent » et « `false` explicite » sont strictement équivalents côté serveur — aucune ambiguïté résiduelle. Pas de `Boolean` boxé : on évite un `null` à trois états qui n'a aucun sens métier ici.

2. **Forme du corps JSON 409** : `PieceExceptionHandler` gère déjà `TransitionStatutInterditeException` avec un record `ErreurReponse(String code, String message)`. Ce record est insuffisant ici (il faut porter la liste des candidats), donc un second record dédié est ajouté dans la même classe, sans toucher `ErreurReponse` ni son handler existant :

   ```json
   {
     "code": "DOUBLON_POTENTIEL",
     "message": "Un doublon potentiel a ete detecte pour ce numero de document.",
     "numerosFicheCandidats": ["PC-3F2A9C1B-2026-00001"]
   }
   ```

   `message` est un texte fixe (pas d'interpolation de la liste dans la phrase, qui ferait doublon avec le champ dédié `numerosFicheCandidats`). Cohérent avec le pattern `code` + `message` déjà en place pour `TRANSITION_STATUT_INTERDITE`, étendu du champ minimal nécessaire.

3. **Traçabilité de la confirmation forcée : option (b) retenue — colonne persistante.** Justification déterminante, indépendante de la préférence de principe pour la minimisation : `AuditAspect.construireDetails(...)` (lu intégralement, `backend/src/main/java/sn/samapiece/audit/AuditAspect.java`) n'écrit `{"exception":..., "message":...}` que sur le chemin `ECHEC` ; sur `SUCCES` il n'écrit que `{"resultat":"SUCCES"}`, sans aucun moyen d'y injecter un contexte métier (pas de paramètre, pas de hook d'enrichissement). C'est confirmé par le test existant `PieceAuditIntegrationTest.creer_avecDonneesValides_shouldCreerEvenementAuditPieceCreee`, qui n'asserte que `resultat == SUCCES`. Une création confirmée malgré doublon est un **succès** applicatif (l'aspect ne voit passer aucune exception) : elle produirait donc un événement `PIECE_CREEE`/`SUCCES` strictement identique à une création normale. Choisir (a) reviendrait donc, contrairement à la mise en garde du design, à rendre illusoire la traçabilité promise par l'audit générique pour ce cas précis — sauf à modifier `AuditAspect` pour lui faire porter un contexte métier générique, ce qui est une modification plus invasive et plus risquée (aspect transverse partagé par 5 actions auditées) qu'une colonne `boolean` ciblée sur `Piece`. Compromis assumé : `cree_malgre_doublon BOOLEAN NOT NULL DEFAULT false` sur `piece` (migration `V11`), non modifiable après création, visible par un superviseur directement sur la fiche (et via `PieceResponse`) sans avoir à consulter `evenement_audit`. `AuditAspect` reste inchangé (décision 6 du design confirmée pour le chemin ÉCHEC ; le chemin SUCCÈS est couvert par la colonne, pas par l'aspect).

   Nuance importante à documenter dans le code (javadoc sur le champ/la méthode) et pour le reviewer : `cree_malgre_doublon = true` signifie « cette pièce a été créée avec le drapeau de confirmation forcée activé », **pas** « un doublon a été effectivement re-vérifié au moment de la création ». Puisque la confirmation forcée court-circuite entièrement `detecterDoublon(...)` (décision 5 du design), on ne sait plus, une fois la colonne à `true`, si un doublon existait réellement à cet instant — seul le fait que l'agent a choisi de passer outre l'avertissement initial est garanti. C'est exactement le comportement attendu par le critère d'acceptation 2.

4. **Index de performance : inclus dans ce ticket.** `CREATE INDEX idx_piece_type_document_statut ON piece(type_document, statut);` ajouté dans la même migration `V11`. Justification : la nouvelle méthode `PieceRepository.findByTypeDocumentAndStatutIn(...)` scanne aujourd'hui `piece` sans aucun index sur `type_document` (seuls `poste_id`, `agent_createur_id`, `statut` le sont, cf. `V4__create_piece.sql`) ; c'est exactement la requête que l'index composite sert, l'occasion est directement créée par ce ticket, le coût d'ajout est nul en risque de régression (`CREATE INDEX` pur, aucune donnée existante affectée), et différer reviendrait à ouvrir sciemment un ticket de dette technique alors que la migration `V11` est de toute façon nécessaire pour la colonne de traçabilité (décision 3). Pas de `CONCURRENTLY` : le volume de `piece` en environnement pilote reste faible, cohérent avec le phasage du projet signalé par le design.

5. **Statuts actifs confirmés** : `DISPONIBLE`, `RECLAMEE`, `LITIGE`, `SIGNALEE` ; exclusion de `RETIREE`, `ARCHIVEE`, `DETRUITE`. Confirmé tel quel : une pièce dont le cycle de vie est terminé (retirée, archivée, détruite) ne doit plus bloquer un nouveau dépôt légitime du même document, alors qu'une pièce encore disputée (`LITIGE`) ou suspecte (`SIGNALEE`) reste un cas actif où un doublon est au moins aussi préoccupant qu'une pièce simplement `DISPONIBLE`/`RECLAMEE`.

## Écarts identifiés

- Le design (décision 6) affirme qu'« aucune modification d'`AuditAspect` n'est nécessaire », ce qui est vrai pour le chemin 409/ÉCHEC mais ne couvre pas le chemin confirmation-forcée/SUCCÈS (cf. décision 3 ci-dessus) — écart mineur du design par rapport au critère d'acceptation 2 tel qu'interprété strictement (traçabilité réelle d'une confirmation), comblé ici par la colonne `cree_malgre_doublon` plutôt que par une modification de l'aspect.
- Le design ne précise pas que le helper existant `PieceIntegrationTest.creerPieceEnBase(...)` (et son équivalent dans `PieceAuditIntegrationTest`) construit des `Piece` avec des valeurs littérales `"hash"`/`"sel"`/`"masque"` non calculées par `NumeroDocumentHasher` — ces pièces ne peuvent jamais correspondre à `numeroDocumentHasher.verifier(...)` pour un numéro en clair réel. Les nouveaux tests d'intégration doivent utiliser un nouveau helper produisant un hash/sel réels (cf. Plan de tests) ; ce n'était pas un écart bloquant du design, seulement un point d'attention pour le codeur.

## Rappels de vigilance (mémoire projet, à respecter par le codeur)

- **Pas de setter générique sur `Piece`** : `creeMalgreDoublon` est fixé une seule fois, à la création, jamais modifié ensuite. Ajouter un second constructeur (surcharge) plutôt qu'un setter, cf. Contrat technique ci-dessous — pas de nouvelle méthode métier de type `retirer()`/`signaler()` nécessaire ici puisqu'il n'y a pas de transition à valider.
- **`getContentAsString(StandardCharsets.UTF_8)`** obligatoire pour toute assertion MockMvc lisant un corps de réponse contenant du texte accentué (le `message` du 409 contient des accents : "détecté").
- **Ordre de nettoyage `piece_sequence`/`Poste`** dans `PieceIntegrationTest.nettoyer()` (et `PieceAuditIntegrationTest.nettoyer()` si des tests y sont ajoutés) : `jdbcTemplate.update("DELETE FROM piece_sequence")` doit rester avant `posteRepository.deleteAll()` — ne pas réordonner en ajoutant les nouveaux nettoyages.
- **Ne pas redéclarer de handler pour un type déjà géré globalement** : le nouveau `@ExceptionHandler(DoublonPotentielException.class)` va dans `PieceExceptionHandler` aux côtés de celui de `TransitionStatutInterditeException` (même classe, même `@RestControllerAdvice`) — ne pas créer une nouvelle classe de handler, ne pas toucher aux handlers 403/404 déjà globaux (`PhotoExceptionHandler`, `AgentAdminExceptionHandler`).

## Contrat technique

### Migration `backend/src/main/resources/db/migration/V11__detection_doublons_piece.sql`

```sql
ALTER TABLE piece
    ADD COLUMN cree_malgre_doublon BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX idx_piece_type_document_statut ON piece(type_document, statut);
```

### `Piece.java` — nouveau champ, nouvelle surcharge de constructeur

Nouveau champ :

```java
@Column(name = "cree_malgre_doublon", nullable = false)
private boolean creeMalgreDoublon;
```

Nouveau getter :

```java
public boolean isCreeMalgreDoublon() {
    return creeMalgreDoublon;
}
```

Le constructeur public existant (13 paramètres) **n'est pas modifié** — il délègue désormais à une nouvelle surcharge à 14 paramètres avec `creeMalgreDoublon = false`, pour ne casser aucun des 9 fichiers existants qui appellent `new Piece(...)` avec la signature actuelle :

```java
public Piece(
        String numeroFiche, Poste poste, Agent agentCreateur, TypeDocument typeDocument,
        String nomTitulaire, String prenomTitulaire, String numeroDocumentHash,
        String numeroDocumentSel, String numeroDocumentMasque, LocalDate dateNaissanceTitulaire,
        LocalDate dateDepot, String etatDocument, String remarques) {
    this(numeroFiche, poste, agentCreateur, typeDocument, nomTitulaire, prenomTitulaire,
            numeroDocumentHash, numeroDocumentSel, numeroDocumentMasque, dateNaissanceTitulaire,
            dateDepot, etatDocument, remarques, false);
}

public Piece(
        String numeroFiche, Poste poste, Agent agentCreateur, TypeDocument typeDocument,
        String nomTitulaire, String prenomTitulaire, String numeroDocumentHash,
        String numeroDocumentSel, String numeroDocumentMasque, LocalDate dateNaissanceTitulaire,
        LocalDate dateDepot, String etatDocument, String remarques, boolean creeMalgreDoublon) {
    // ... corps identique au constructeur actuel ...
    this.creeMalgreDoublon = creeMalgreDoublon;
}
```

Seul `PieceService.creer(...)` utilise la nouvelle surcharge à 14 paramètres.

### `PieceRepository.java` — nouvelle méthode

```java
List<Piece> findByTypeDocumentAndStatutIn(TypeDocument typeDocument, Collection<StatutPiece> statuts);
```

Ajout pur (pas de modification de `findByTypeDocumentAndStatutAndNomTitulaireIgnoreCase`, utilisée par `RecherchePubliqueService`).

### Nouvelle exception `backend/src/main/java/sn/samapiece/enregistrement/DoublonPotentielException.java`

```java
package sn.samapiece.enregistrement;

import java.util.List;

public class DoublonPotentielException extends RuntimeException {

    private final List<String> numerosFicheCandidats;

    public DoublonPotentielException(List<String> numerosFicheCandidats) {
        super("Un doublon potentiel a ete detecte pour ce numero de document.");
        this.numerosFicheCandidats = List.copyOf(numerosFicheCandidats);
    }

    public List<String> getNumerosFicheCandidats() {
        return numerosFicheCandidats;
    }
}
```

### `web/PieceExceptionHandler.java` — nouveau handler ajouté à la classe existante

```java
public record ErreurDoublonReponse(String code, String message, List<String> numerosFicheCandidats) {}

@ExceptionHandler(DoublonPotentielException.class)
public ResponseEntity<ErreurDoublonReponse> gererDoublonPotentiel(DoublonPotentielException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ErreurDoublonReponse(
                    "DOUBLON_POTENTIEL", ex.getMessage(), ex.getNumerosFicheCandidats()));
}
```

`ErreurReponse` et son handler existant restent inchangés.

### `web/CreerPieceRequest.java`

```java
public record CreerPieceRequest(
        @NotNull TypeDocument typeDocument,
        @NotBlank String nomTitulaire,
        @NotBlank String prenomTitulaire,
        @NotBlank String numeroDocument,
        LocalDate dateNaissanceTitulaire,
        @NotNull LocalDate dateDepot,
        String etatDocument,
        String remarques,
        boolean confirmerMalgreDoublon) {
}
```

### `web/PieceResponse.java` — champ ajouté

```java
public record PieceResponse(
        UUID id, String numeroFiche, UUID posteId, UUID agentCreateurId, String typeDocument,
        String nomTitulaire, String prenomTitulaire, String numeroDocumentMasque,
        LocalDate dateNaissanceTitulaire, LocalDate dateDepot, String etatDocument, String statut,
        String remarques, OffsetDateTime creeLe, boolean creeMalgreDoublon) {

    public static PieceResponse of(Piece piece) {
        return new PieceResponse(
                piece.getId(), piece.getNumeroFiche(), piece.getPoste().getId(),
                piece.getAgentCreateur().getId(), piece.getTypeDocument().name(),
                piece.getNomTitulaire(), piece.getPrenomTitulaire(), piece.getNumeroDocumentMasque(),
                piece.getDateNaissanceTitulaire(), piece.getDateDepot(), piece.getEtatDocument(),
                piece.getStatut().name(), piece.getRemarques(), piece.getCreeLe(),
                piece.isCreeMalgreDoublon());
    }
}
```

### `PieceService.creer(...)` — point d'insertion exact

Avant la construction de la `Piece`, **avant** l'appel à `numeroDocumentHasher.hacher(...)` (pas de calcul de hash gaspillé si la création va être bloquée en 409). Le hash/sel du nouveau document est calculé dans tous les cas où l'exécution se poursuit, qu'il y ait eu confirmation forcée ou absence totale de doublon :

```java
private static final List<StatutPiece> STATUTS_ACTIFS =
        List.of(StatutPiece.DISPONIBLE, StatutPiece.RECLAMEE, StatutPiece.LITIGE, StatutPiece.SIGNALEE);

@Transactional
public PieceResponse creer(CreerPieceRequest request) {
    Agent appelant = appelantCourant();
    Poste poste = appelant.getPoste();

    if (!request.confirmerMalgreDoublon()) {
        detecterDoublon(request.typeDocument(), request.numeroDocument());
    }

    NumeroDocumentHache hache = numeroDocumentHasher.hacher(request.numeroDocument());
    String numeroFiche = numeroFicheGenerator.genererNumeroFiche(poste.getId(), request.dateDepot());

    Piece piece = new Piece(
            numeroFiche, poste, appelant, request.typeDocument(), request.nomTitulaire(),
            request.prenomTitulaire(), hache.hash(), hache.sel(), hache.masque(),
            request.dateNaissanceTitulaire(), request.dateDepot(), request.etatDocument(),
            request.remarques(), request.confirmerMalgreDoublon());

    pieceRepository.saveAndFlush(piece);
    // ... publication des evenements PieceIndexableEvent / PieceDisponibleEvent inchangee ...
    return PieceResponse.of(piece);
}

private void detecterDoublon(TypeDocument typeDocument, String numeroDocumentClair) {
    List<Piece> candidats = pieceRepository.findByTypeDocumentAndStatutIn(typeDocument, STATUTS_ACTIFS);
    List<String> numerosFicheCorrespondants = candidats.stream()
            .filter(candidat -> numeroDocumentHasher.verifier(
                    numeroDocumentClair, candidat.getNumeroDocumentSel(), candidat.getNumeroDocumentHash()))
            .map(Piece::getNumeroFiche)
            .toList();
    if (!numerosFicheCorrespondants.isEmpty()) {
        throw new DoublonPotentielException(numerosFicheCorrespondants);
    }
}
```

Si `confirmerMalgreDoublon() == true`, `detecterDoublon(...)` n'est **pas appelée du tout** (ni `findByTypeDocumentAndStatutIn` ni `verifier`) — court-circuit total, conforme à la décision 5 du design.

## Tâches

- [ ] `backend/src/main/resources/db/migration/V11__detection_doublons_piece.sql` (nouveau) — colonne `cree_malgre_doublon` + index composite `(type_document, statut)`, selon le schéma figé ci-dessus.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/Piece.java` — ajouter le champ `creeMalgreDoublon`, son getter `isCreeMalgreDoublon()`, et la surcharge de constructeur à 14 paramètres (le constructeur à 13 paramètres délègue avec `false`).
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/PieceRepository.java` — ajouter `findByTypeDocumentAndStatutIn(TypeDocument, Collection<StatutPiece>)`.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/DoublonPotentielException.java` (nouveau) — selon signature figée ci-dessus.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/PieceService.java` — ajouter la constante `STATUTS_ACTIFS`, la méthode privée `detecterDoublon(TypeDocument, String)`, et son appel conditionnel dans `creer(...)` au point d'insertion figé ci-dessus ; passer `request.confirmerMalgreDoublon()` au constructeur de `Piece`.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/web/CreerPieceRequest.java` — ajouter `boolean confirmerMalgreDoublon` en dernier champ du record.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/web/PieceResponse.java` — ajouter `boolean creeMalgreDoublon`, alimenté par `piece.isCreeMalgreDoublon()`.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/web/PieceExceptionHandler.java` — ajouter le record `ErreurDoublonReponse` et le handler `gererDoublonPotentiel` pour `DoublonPotentielException` → 409, sans toucher au handler existant de `TransitionStatutInterditeException`.
- [ ] `backend/src/test/java/sn/samapiece/enregistrement/PieceServiceTest.java` — étendre avec les tests unitaires listés dans le Plan de tests.
- [ ] `backend/src/test/java/sn/samapiece/enregistrement/web/PieceIntegrationTest.java` — ajouter un helper de création de `Piece` avec hash/sel réels (via `new NumeroDocumentHasher().hacher(numeroDocumentClair)`, même pattern que `PieceServiceTest.pieceExistante(...)`) puis les tests d'intégration listés dans le Plan de tests.

## Plan de tests

| AC / cas | Test | Emplacement |
|---|---|---|
| AC1 — doublon actif détecté → 409 explicite avec numéro(s) de fiche candidat(s) | `creer_avecNumeroDocumentIdentiqueAUneFicheDisponible_shouldRetourner409AvecNumeroFicheCandidat` : crée une `Piece` existante `DISPONIBLE` avec le même `numeroDocument`/`typeDocument` (hash réel via `NumeroDocumentHasher`), POST création → `status().isConflict()`, corps (`getContentAsString(UTF_8)`) : `$.code == "DOUBLON_POTENTIEL"`, `$.numerosFicheCandidats` contient le `numeroFiche` de la pièce existante | `PieceIntegrationTest` (intégration) |
| AC1 (unitaire) — détection lève `DoublonPotentielException` sans persister | `creer_avecNumeroDocumentCorrespondantAUneFicheActive_shouldLeverDoublonPotentielException` : `pieceRepository.findByTypeDocumentAndStatutIn(...)` stubbé pour retourner un candidat, `numeroDocumentHasher.verifier(...)` stubbé `true` ; assert `DoublonPotentielException` levée avec `numerosFicheCandidats` contenant le `numeroFiche` du candidat ; `verify(pieceRepository, never()).saveAndFlush(any())` ; `verify(eventPublisher, never()).publishEvent(any())` | `PieceServiceTest` (unitaire) |
| AC1 (unitaire) — shortlist interrogée avec les bons statuts actifs | `creer_shouldInterrogerShortlistAvecStatutsActifsAttendus` : `ArgumentCaptor<Collection<StatutPiece>>` sur `findByTypeDocumentAndStatutIn`, assert égal à `List.of(DISPONIBLE, RECLAMEE, LITIGE, SIGNALEE)` | `PieceServiceTest` |
| AC2 — confirmation forcée aboutit malgré doublon détecté | `creer_avecConfirmerMalgreDoublonTrue_shouldRetourner201MalgreDoublonDetecte` : même setup que le test 409 ci-dessus, POST avec `confirmerMalgreDoublon: true` dans le corps JSON → `status().isCreated()`, puis recharge la `Piece` créée via `pieceRepository.findById` et assert `isCreeMalgreDoublon() == true` ; assert aussi `$.creeMalgreDoublon == true` dans la réponse 201 | `PieceIntegrationTest` |
| AC2 (unitaire) — confirmation forcée court-circuite entièrement la vérification | `creer_avecConfirmerMalgreDoublonTrue_shouldCourtCircuiterVerificationEtCreerMalgreCandidatCorrespondant` : `numeroDocumentHasher.verifier(...)` stubbé `true` (détecterait normalement un doublon), requête avec `confirmerMalgreDoublon = true` → aucune exception levée, `pieceRepository.saveAndFlush` appelé une fois, `verify(pieceRepository, never()).findByTypeDocumentAndStatutIn(any(), any())` (le court-circuit empêche même l'interrogation de la shortlist), `Piece` capturée a `isCreeMalgreDoublon() == true` | `PieceServiceTest` |
| Cas limite — absence de confirmation et absence de doublon : `creeMalgreDoublon` reste `false` | `creer_avecConfirmerMalgreDoublonFalseOuAbsent_sansDoublon_shouldCreerAvecCreeMalgreDoublonFalse` (réutilise `requete()` existante, aucune stub de candidat) : assert `Piece` capturée a `isCreeMalgreDoublon() == false` | `PieceServiceTest` |
| Cas limite — plusieurs candidats actifs correspondants | `creer_avecPlusieursCandidatsActifsCorrespondants_shouldRetourner409AvecTousLesNumerosFicheCandidats` : deux `Piece` existantes (`DISPONIBLE` et `LITIGE`) avec le même `numeroDocument`/`typeDocument`, POST création → 409, `$.numerosFicheCandidats` contient exactement les deux `numeroFiche` (`containsExactlyInAnyOrder`) | `PieceIntegrationTest` |
| Cas limite — plusieurs candidats (unitaire) | `creer_avecPlusieursCandidatsCorrespondants_shouldInclureTousLesNumerosFicheDansException` : deux candidats stubbés avec `verifier(...) == true`, assert `getNumerosFicheCandidats()` de taille 2 | `PieceServiceTest` |
| Cas limite — absence totale de candidat (aucun doublon) | Test existant `creer_commeAgent_avecDonneesValides_shouldRetourner201` (table `piece` vide au moment de l'appel) suffit à couvrir ce cas ; aucun nouveau test requis | `PieceIntegrationTest` (existant) |
| Non-détection — statut inactif exclu (`RETIREE`) | `creer_avecNumeroDocumentIdentiqueMaisStatutRetiree_shouldRetourner201SansDetectionDoublon` : `Piece` existante `RETIREE` avec même `numeroDocument`/`typeDocument`, POST création → `status().isCreated()` (pas de 409) | `PieceIntegrationTest` |
| Non-détection — statut inactif exclu (`ARCHIVEE`, `DETRUITE`) | Variante(s) du test précédent paramétrées ou dédiées pour `ARCHIVEE` et `DETRUITE` | `PieceIntegrationTest` |
| Non-détection — type de document différent exclu | `creer_avecNumeroDocumentIdentiqueMaisTypeDocumentDifferent_shouldRetourner201SansDetectionDoublon` : `Piece` existante `CNI`/`DISPONIBLE`, POST création avec `typeDocument = PASSEPORT` et même `numeroDocument` → `status().isCreated()` | `PieceIntegrationTest` |
| Minimisation du contenu exposé (décision 4 du design) | Sur le test 409 (`creer_avecNumeroDocumentIdentiqueAUneFicheDisponible_shouldRetourner409AvecNumeroFicheCandidat`) : assertions `jsonPath("$.nomTitulaire").doesNotExist()`, `jsonPath("$.prenomTitulaire").doesNotExist()`, `jsonPath("$.numeroDocumentMasque").doesNotExist()` sur le corps 409 | `PieceIntegrationTest` |
| Traçabilité (décision 3) — visibilité de `cree_malgre_doublon` sur une création normale | Étendre `creer_avecDonneesValides_shouldHacherNumeroDocumentCoteServeur` (ou nouveau test dédié) : assert `$.creeMalgreDoublon == false` sur une création 201 sans confirmation | `PieceIntegrationTest` |
| Encodage UTF-8 des messages d'erreur | Toutes les assertions de corps de réponse 409 utilisent `getResponse().getContentAsString(StandardCharsets.UTF_8)` (le message contient "détecté") | `PieceIntegrationTest` |
| Non-régression — méthode de repository existante inchangée | Aucun nouveau test requis : `findByTypeDocumentAndStatutAndNomTitulaireIgnoreCase` et ses appelants (`RecherchePubliqueService`) ne sont pas modifiés ; vérification par lecture de code suffisante (pas un AC de ce ticket) | Revue de code (manuel) |
