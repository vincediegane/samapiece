# Review #74 - Lister/consulter les pièces en stock

**Verdict : APPROVE**

_Rapport produit par le bolt-reviewer (sans outil d'écriture) ; transcrit dans ce fichier par l'orchestrateur._

Aucun finding bloquant. Les tests d'intégration Testcontainers n'ont pas été exécutés (pas de Docker) ; ils ont été relus.

## Critères d'acceptation

| Critère | Statut | Preuve |
|---|---|---|
| Endpoint listant les pièces d'un poste (pagination, filtre statut), restreint par rôle | Couvert | `PieceController.lister` : `GET /api/v1/pieces`, `@PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE')")`, `@PageableDefault(size=20)`, `statut` optionnel. `PieceService.lister` : 404 si `posteId` inconnu (`PosteIntrouvableException`), 403 si hors périmètre (`PerimetrePoste.estDansPerimetre`). Tests : `PieceServiceTest` (6 cas `lister_*`), `PieceListeIntegrationTest` (401, 403 ADMIN_REGIONAL/ADMIN_NATIONAL/AUDITEUR, 403 autre poste, 404 poste inconnu, 200, 400 statut invalide, pagination, plafond de taille). |
| Écran frontend (n° fiche, type, statut, ancienneté), accessible depuis la navigation agent | Couvert | `PiecesEnStockPage.tsx`, onglet `stock` « Pièces en stock » dans `AgentShell.tsx`, branché dans `App.tsx`. Tests : `PiecesEnStockPage.test.tsx`, `AgentShell.test.tsx`, `App.test.tsx`, `piecesApi.test.ts`. |
| Accès à la fiche détaillée depuis la liste | Couvert | « Ouvrir la fiche » -> `onOuvrirFiche(id)` -> `App` : `setPieceAOuvrir` puis onglet `fiche` -> `ConsulterFichePage idInitial` (chargement auto). `key` force le remontage ; `naviguerAgent('fiche')` remet `pieceAOuvrir` à null. Tests `ConsulterFichePage.test.tsx`, `App.test.tsx`. |
| Cohérence avec les compteurs du dashboard #26 | Couvert | Liste par défaut = {DISPONIBLE, RECLAMEE} comme `agregerStockParPoste` ; ancienneté `ChronoUnit.DAYS.between(dateDepot, today)` (équivalent `CURRENT_DATE - date_depot`) ; seuil strict `> seuil` via `StatistiquesProperties` ; `depasseSeuil` limité à DISPONIBLE/RECLAMEE. Test `lister_totalElementsEgalNombrePiecesEnAttenteDuDashboard` : `totalElements` = `nombrePiecesEnAttente`, lignes `depasseSeuil` = `nombrePiecesDepassantSeuil`. |

## Points critiques vérifiés
- Sécurité : `@PreAuthorize` présent, 401 par le filtre ; handlers ACCES_REFUSE (403) / POSTE_INTROUVABLE (404) dans `AgentAdminExceptionHandler` (advice global). Liste non auditée (voulu).
- DTO sans donnée personnelle : `PieceListeItemResponse` = id, numeroFiche, typeDocument, statut, dateDepot, ancienneteJours, depasseSeuil. Le test d'intégration vérifie l'absence de noms, numéro de document masqué, remarques et posteId dans le JSON brut.
- JPQL : `Collection<StatutPiece>` avec `IN :statuts`, `countQuery` fournie, `ORDER BY p.dateDepot ASC, p.numeroFiche ASC`, aucun littéral de statut (`StatutPieceConverter` s'applique aux paramètres).
- Tri et taille : `PageRequest.of(page, min(size,100))` sans `Sort` (tri client ignoré) ; tests unitaires et intégration.
- Migration V14 : `CREATE INDEX idx_piece_poste_statut_date_depot ON piece(poste_id, statut, date_depot)`, valide.
- #26 inchangé : aucun diff dans `reporting/` ni dans les requêtes natives existantes.
- Tests : `pieceRepository.deleteAll()` puis `DELETE FROM piece_sequence` avant agents/postes/régions ; `getContentAsString(StandardCharsets.UTF_8)` partout.
- Diff frontend minimal : `App.tsx` et `AgentShell.tsx` sans reformatage (même chiffre avec `--ignore-all-space`), CRLF conservés. Conflit avec la PR #83 attendu limité à `OngletAgent`, `ONGLETS_AGENT` et au bloc de boutons.
- Refactor `ConsulterFichePage` : `charger(id)` extrait, `useEffect` au montage seulement, `eslint-disable exhaustive-deps` justifié ; pas de régression.

## Findings non bloquants
1. Cycle de package `enregistrement` <-> `reporting` : `PieceService.java:29` importe `sn.samapiece.reporting.StatistiquesProperties`, alors que `reporting` dépend déjà de `PieceRepository`. Aucune règle ArchUnit dans le repo. Suggestion : déplacer le seuil dans un package commun si une règle d'architecture est introduite.
2. `PiecesEnStockPage.tsx` et son test sont en fins de ligne LF alors que le reste de `features/pieces` est en CRLF (pas de `.gitattributes`) : cosmétique.
3. `PageImpl` sérialisé directement : format dépendant de la version de Spring Data ; le test d'intégration fige le contrat (`content`, `totalElements`, `totalPages`, `number`, `size`).
4. UX : « Aucune pièce en stock. » s'affiche aussi avec un filtre sur un statut hors stock (ex. RETIREE) : message légèrement inexact.

## Build / tests
- `npx vitest run` : 24 fichiers, 242 tests OK ; `npx tsc -b` OK ; `npm run lint` OK.
- `mvn -q test-compile` OK ; `mvn -q test -Dtest=PieceServiceTest` OK.
- `PieceListeIntegrationTest` : non exécuté (Docker requis), à valider en CI.
