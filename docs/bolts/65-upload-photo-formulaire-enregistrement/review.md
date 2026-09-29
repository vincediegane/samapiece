# Review #65 - Upload de photo dans le formulaire d'enregistrement

**Verdict : APPROVE**

_Rapport produit par le bolt-reviewer (sans outil d'écriture) ; transcrit dans ce fichier par l'orchestrateur. Le finding n°1 a été revérifié dans le code._

## Critères d'acceptation
- **Champ photo optionnel + aperçu avant envoi** : couvert. `PhotoUpload.tsx` (aperçu via `createObjectURL`, révoqué au cleanup du `useEffect`), inséré avant le submit dans `EnregistrementPiecePage.tsx`. Tests « sans photo » et « avec photo ».
- **Erreurs backend affichées clairement** : couvert. `photosApi.ts` mappe par `code` puis par statut ; alerte `role="alert"` sans empêcher l'affichage du reçu. Test 409 : `creerPiece` appelé une seule fois. `photosApi.test.ts` couvre le mapping.
- **Photo consultable depuis le reçu / la fiche** : couvert. `PhotosFiche.tsx` rendu dans `FichePieceCard.tsx` ; nouveau `GET /pieces/{id}/photos` ; tests `FichePieceCard.test.tsx`, 3 cas `PhotoServiceTest`, 6 cas `PhotoIntegrationTest`.
- **Cohérence hors-ligne** : couvert. `mettreEnFile` reçoit le payload sans photo, message « La photo n'a pas été conservée… ». `shared/offline/*` et `vite.config.ts` intacts.

## Points d'attention vérifiés
- RBAC : `@PreAuthorize` du nouvel endpoint identique au GET unitaire ; `PhotoService.lister` applique `PerimetrePoste.estDansPerimetre` comme `telecharger`.
- Aucune persistance côté client de la photo (localStorage, IndexedDB, cache SW).
- `Content-Type` non forcé sur le multipart (`Authorization` seul).
- Upload photo dans un try/catch dédié, ne peut pas déclencher `mettreEnFile`.
- `PhotoIntegrationTest` relu (non exécuté, Docker requis) : `getContentAsString(StandardCharsets.UTF_8)` ; pièces créées via `pieceRepository.save`, donc pas de nettoyage `piece_sequence` nécessaire.
- Tâches 1 à 15 de la spec réalisées.

## Findings non bloquants
1. `frontend/src/features/pieces/PhotosFiche.tsx:37-40` : fuite possible de blob URL. Si le composant est démonté (ou `pieceId`/rechargement change) pendant la boucle de `telechargerPhoto`, les URLs créées après le cleanup ne sont jamais révoquées (la boucle ne teste pas `annule` avant `createObjectURL`). Correctif suggéré : tester `annule` avant `createObjectURL`, ou révoquer immédiatement. Impact faible.
2. Après un ajout, le rechargement retélécharge toutes les photos. Acceptable.

## Build / tests
- `npx vitest run` : 19 fichiers, 188 tests OK.
- `npx tsc -b` : OK. `npm run lint` : OK.
- `mvn -q -o test -Dtest=PhotoServiceTest` : OK. `mvn -q -o test-compile` : OK.
- `PhotoIntegrationTest` : non exécuté (Docker requis) ; à valider en CI.
