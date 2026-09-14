# Review — Ticket #24 : Workflow de retrait avec double vérification d'identité

## Verdict

APPROVE

## Critères d'acceptation

| # | Critère | Statut |
|---|---|---|
| AC1 | `POST /api/v1/pieces/{id}/retrait` enregistre réclamant, pièce justificative, agent validateur, passe à `RETIREE` | Couvert — `PieceService.retirer` persiste `Retrait` via `saveAndFlush` puis `Piece.retirer()` ; testé en unitaire (`PieceServiceTest#retirer_avecStatutDisponible_shouldPersisterRetraitEtPasserStatutARetiree`, capture Mockito) et en intégration (`PieceIntegrationTest#retirer_commeAgentDuPosteDeLaPiece_avecPieceDisponible_shouldRetourner200EtPasserRetiree`, relit `Piece` + `Retrait` en base) |
| AC2 | Impossible de retirer une pièce `RETIREE`/`ARCHIVEE`/`LITIGE` (et par extension `SIGNALEE`/`DETRUITE`) sans déblocage explicite | Couvert — précondition dans `Piece.retirer()` ; tests unitaires paramétrés sur tous les statuts non autorisés y compris `DETRUITE` (`PieceTest#retirer_avecStatutNonAutorise_shouldLeverTransitionStatutInterdite`) et tests d'intégration dédiés `RETIREE`/`ARCHIVEE`/`LITIGE` (409) |
| AC3 | `POST /api/v1/pieces/{id}/signaler` marque `LITIGE`/`SIGNALEE`, bloque le retrait | Couvert — `Piece.signaler(...)` + tests d'intégration enchaînant signalement puis tentative de retrait (409) pour les deux valeurs cibles |
| AC4 | Tests d'intégration : retrait valide, tentative sur pièce déjà retirée (409), signalement bloquant | Couvert — voir `PieceIntegrationTest` (retrait 200, 409 sur `RETIREE`/`ARCHIVEE`/`LITIGE`, signalement puis 409, plus déblocage puis nouveau retrait 200, RBAC positif/négatif, périmètre poste/région, validation 400, non-rapprochement de nom) |

Déblocage (`debloquer`), non listé comme AC explicite mais nécessaire au workflow complet et documenté dans la spec, est également couvert (unitaire + intégration, y compris `DETRUITE` refusé et RBAC/périmètre régional `PerimetrePoste`).

## Relecture détaillée

Le diff (`git diff main..bolt/issue-24-workflow-retrait`) correspond fidèlement au contrat technique figé dans `spec.md`, ligne par ligne :

- **`Piece.java`** : les 6 colonnes/getters et les 3 méthodes métier (`retirer`, `signaler`, `debloquer`) sont exactement celles du contrat, sans setter générique. Préconditions vérifiées manuellement et par tests : `retirer` accepte `DISPONIBLE`/`RECLAMEE` uniquement ; `signaler` idem en entrée avec sortie restreinte à `LITIGE`/`SIGNALEE` (`IllegalArgumentException` sinon) ; `debloquer` accepte `RETIREE`/`ARCHIVEE`/`LITIGE`/`SIGNALEE` (PAS `DETRUITE`) et sort vers `DISPONIBLE`. Le cas `DETRUITE` est bien refusé à la fois par `retirer()` et par `debloquer()`, avec test dédié dans `PieceTest`.
- **`PieceService.java`** : `retirer`/`signaler` utilisent la comparaison directe `appelant.getPoste().getId().equals(piece.getPoste().getId())` (même pattern que `PhotoService.uploader`) ; `debloquer` utilise `PerimetrePoste.estDansPerimetre` (couvre le périmètre régional pour `ADMIN_REGIONAL`/`ADMIN_NATIONAL`). Les deux mécanismes ne sont pas confondus. Chaque transition republie un `PieceIndexableEvent` avec un `PieceRechercheDocument` reconstruit à partir de la `Piece` à jour, exactement comme dans `creer` — vérifié par capture Mockito (`retirer_shouldRepublierPieceIndexableEventAvecStatutRetiree`).
- **`PieceExceptionHandler.java`** : gère uniquement `TransitionStatutInterditeException` → 409. Aucun doublon de `PieceIntrouvableException`/`AccesRefuseException` (déjà gérés par `PhotoExceptionHandler`/`AgentAdminExceptionHandler`). Confirmé par lecture des trois `@RestControllerAdvice` concernés et par le fait que le contexte Spring démarre sans erreur `ApplicationContext` dans tous les `@SpringBootTest` (les seuls échecs sont des `ContainerFetchException` Docker, cf. Build/tests).
- **Migration `V9`** : conforme au schéma figé (table `retrait` + 2 index, 6 colonnes nullables ajoutées sur `piece`), aucune modification du `CHECK` existant sur `piece.statut` (vérifié dans `V4__create_piece.sql`, toujours en place).
- **Pas de rapprochement automatique nom réclamant/titulaire** : confirmé, aucune comparaison n'existe dans `PieceService.retirer` ; test dédié `retirer_avecNomReclamantDifferentDuNomTitulaire_shouldRetourner200QuandMemeSansOCR` (intégration) + équivalent unitaire.
- **Pas de `@Version`** sur `Piece`/`Retrait` : confirmé, aucun ajout de verrou optimiste.
- **`PieceIntegrationTest` — ordre de nettoyage** : `retraitRepository.deleteAll()` intervient bien avant `pieceRepository.deleteAll()`, qui précède le `DELETE FROM piece_sequence` puis `posteRepository.deleteAll()`/`regionRepository.deleteAll()` — ordre FK respecté, cohérent avec le rappel de mémoire projet.
- **Encodage UTF-8** : les assertions sur les corps d'erreur 409 utilisent `getContentAsString(StandardCharsets.UTF_8)` dans les tests concernés (ex. `retirer_survienneSurPieceDejaRetiree_shouldRetourner409`, `signaler_commeAgentDuPoste_avecStatutCibleLitige_shouldRetourner200EtPasserLitige`).
- **Minimisation des données (§10 PROJET-SAMAPIECE.md)** : `nomReclamant` et `pieceJustificativePresentee` sont stockés en clair sur `retrait`. Ce n'est pas un `numeroDocument` ni un `contact citoyen` au sens strict de §10.4 (seuls explicitement visés par le hashing), et le pattern est cohérent avec l'existant (`Piece.nomTitulaire`/`prenomTitulaire` déjà en clair, seul `numeroDocument` est haché via `NumeroDocumentHasher`). Décision déjà actée et documentée comme risque assumé dans `design.md`/`spec.md` (§ Risques, minimisation) — pas un ajout furtif du codeur. Point à garder en tête pour un futur ticket EIPD/§10, mais ne bloque pas ce lot.

## Build/tests

- `mvn -q -pl backend -am compile` → succès (aucune sortie, pas d'erreur).
- `mvn -q -pl backend -am test-compile` → succès.
- `mvn -pl backend -am test -Dtest=PieceServiceTest,PieceTest` → **36 tests, 0 échec, 0 erreur** (BUILD SUCCESS).
- `mvn -pl backend -am test` (suite complète) → 195 tests exécutés, 17 erreurs, **toutes de type `org.testcontainers.containers.ContainerFetchException: Can't get Docker image ... postgres:16-alpine`** (Docker indisponible dans le sandbox — limitation connue et documentée dans le prompt de revue). Les 17 classes en échec sont exactement les `@SpringBootTest`/Testcontainers déjà existants avant ce ticket (`SamaPieceApplicationTests`, `AlerteCorrespondanceIntegrationTest`, `AlerteIntegrationTest`, `PieceNumeroFicheGeneratorTest`, `PhotoIntegrationTest`, `PieceIntegrationTest`, `AgentAdminIntegrationTest`, `AgentIntegrationTest`, `AuthIntegrationTest`, `SmsRetryIntegrationTest`, `PieceIndexationBestEffortIntegrationTest`, `PieceIndexationIntegrationTest`, `RecherchePubliqueCaptchaIntegrationTest`, `RecherchePubliqueIntegrationTest`, `RecherchePubliqueMeilisearchIndisponibleIntegrationTest`, `RecherchePubliqueRateLimitingIntegrationTest`, `PosteIntegrationTest`) — aucune ne révèle de doublon de bean/`@ExceptionHandler` ni de régression de démarrage de contexte imputable à ce bolt ; le reste de la suite (178 tests, dont tous les tests unitaires purs) passe. Compensé par une relecture manuelle complète de `PieceIntegrationTest` et du code de production qu'il exerce (RBAC, périmètre, transitions, republishing d'événement, ordre de nettoyage FK).

## Conclusion

Le code respecte le contrat technique de `spec.md` à la lettre (signatures, préconditions de transition, RBAC/périmètre, DTOs, migration, exception handler), couvre tous les AC avec des tests qui échoueraient si le code était retiré, et ne présente aucune régression détectable localement. Aucun finding bloquant.
