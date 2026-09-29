# Spec #65 - Upload de photo dans le formulaire d'enregistrement

## Résumé
Ajout d'un champ photo optionnel (aperçu, validation, envoi en second appel après `creerPiece`) au formulaire d'enregistrement, d'un affichage/ajout des photos dans `FichePieceCard` (reçu + `ConsulterFichePage`), et d'un endpoint backend `GET /api/v1/pieces/{pieceId}/photos` listant les métadonnées des photos.

## Tâches

### Backend (`backend/src/main/java/sn/samapiece/enregistrement/photo/`)
- [ ] 1. `PhotoRepository.java` : ajouter `List<Photo> findByPieceId(UUID pieceId);` (import `java.util.List`).
- [ ] 2. `PhotoService.java` : ajouter `@Transactional(readOnly = true) public List<UploadPhotoResponse> lister(UUID pieceId)` : `appelantCourant()`, `pieceRepository.findById` sinon `PieceIntrouvableException`, `PerimetrePoste.estDansPerimetre(appelant, piece.getPoste())` sinon `AccesRefuseException` (identique à `telecharger`), puis `photoRepository.findByPieceId(pieceId)` mappé via `UploadPhotoResponse.of`, trié par `creeLe` croissant.
- [ ] 3. `web/PhotoController.java` : ajouter `@GetMapping` (sans sous-chemin) `lister(@PathVariable UUID pieceId)` -> `ResponseEntity<List<UploadPhotoResponse>>` 200, avec `@PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')")`. Aucune modification de `PhotoExceptionHandler` (PIECE_INTROUVABLE 404 et AccesRefuseException 403 déjà gérés).
- [ ] 4. `PhotoServiceTest.java` (existant) : tests unitaires de `lister` (piece introuvable, hors périmètre, liste retournée).
- [ ] 5. `PhotoIntegrationTest.java` (existant, `backend/src/test/java/sn/samapiece/enregistrement/photo/`) : ajouter les cas du plan de tests. Utiliser `getContentAsString(StandardCharsets.UTF_8)`. Si un test crée des pièces via l'API (et non `pieceRepository.save`), vider `piece_sequence` avant `posteRepository.deleteAll()` dans `nettoyer()` (le `@BeforeEach` actuel ne le fait pas).

### Frontend (`frontend/src/features/pieces/`)
- [ ] 6. `types.ts` : ajouter `TypePhoto = 'RECTO' | 'VERSO'` et `PhotoMeta { id; pieceId; type; typeMime; tailleOctets; creeLe }`.
- [ ] 7. Créer `photosApi.ts` : `PhotoApiError(message, status, code?)` ; `uploaderPhoto(pieceId, type, fichier)`, `listerPhotos(pieceId)`, `telechargerPhoto(pieceId, photoId): Promise<Blob>` ; helper d'en-tête `Authorization: Bearer` SEUL (ne pas réutiliser `enTeteAutorisation` de `piecesApi.ts`, non exporté et qui force `application/json`) ; helper d'erreur qui lit `{code,message}` du corps JSON (try/catch si corps non JSON) et mappe par `code`, puis par statut (401/403/404) en repli. Constantes `TYPES_PHOTO_ACCEPTES = ['image/jpeg','image/png']`, `TAILLE_MAX_PHOTO = 10*1024*1024`, `validerPhoto(file): string | null`.
- [ ] 8. Créer `PhotoUpload.tsx` : props `{ fichier, type, onChange(fichier|null), onTypeChange, erreur? , disabled? }` ; `<input type="file" accept="image/jpeg,image/png">`, `<select>` RECTO/VERSO (défaut RECTO), aperçu `<img>` via `URL.createObjectURL` révoqué au changement/démontage (`useEffect` cleanup), bouton « Retirer la photo ». Validation cliente via `validerPhoto` à la sélection (fichier rejeté = non retenu + message `role="alert"`).
- [ ] 9. Créer `PhotosFiche.tsx` : props `{ pieceId, roleAgentCourant }` ; au montage `listerPhotos` ; pour chaque photo `telechargerPhoto` -> `URL.createObjectURL` (révoqué au cleanup) affichée avec le libellé Recto/Verso ; si un type manque ET rôle ∈ {AGENT, CHEF_POSTE}, afficher `PhotoUpload` + bouton « Ajouter la photo » (désactivé pendant l'envoi) puis rechargement de la liste ; erreurs de l'upload affichées en `role="alert"`. Sur 403 de la liste : composant ne rend rien. Hors connexion / échec réseau : « Photo indisponible hors connexion », sans bloquer la fiche.
- [ ] 10. `FichePieceCard.tsx` : rendre `<PhotosFiche pieceId={piece.id} roleAgentCourant={roleAgentCourant} />` dans la `section.card`, sous les boutons d'actions. Aucun changement de `ConsulterFichePage.tsx` (déjà passe `roleAgentCourant`).
- [ ] 11. `EnregistrementPiecePage.tsx` : états `photo: File | null`, `typePhoto: TypePhoto` (défaut RECTO), `alertePhoto: string | null` ; insérer `<PhotoUpload>` (libellé « Photo du document (optionnel) ») avant le bouton submit ; après `creerPiece` réussi : `setRecu`, reset formulaire, puis si `photo` : `uploaderPhoto(resultat.id, typePhoto, photo)` dans un try/catch dédié -> en échec `setAlertePhoto(e.message)` (non bloquant, `role="alert"`, mention « ajoutez-la depuis la fiche ») ; ne jamais relancer `creerPiece` ; `photo` remis à null dans tous les cas. Dans la branche `mettreEnFile` : abandonner `photo` et ajouter au message : « La photo n'a pas été conservée : ajoutez-la depuis la fiche une fois synchronisée. » (uniquement si une photo était sélectionnée). `enEnvoi` reste vrai jusqu'à la fin de l'upload. Ne pas toucher `shared/offline/*`.
- [ ] 12. `vite.config.ts` : aucune modification requise (vérifié : pas de `runtimeCaching`, `globPatterns` limité aux assets statiques, `/api/` exclu du fallback) ; consigner la vérification dans la PR.

### Tests frontend
- [ ] 13. Créer `photosApi.test.ts`.
- [ ] 14. `EnregistrementPiecePage.test.tsx` : nouveaux cas.
- [ ] 15. `FichePieceCard.test.tsx` : nouveaux cas (mocker `photosApi`).

## Contrat technique

Nouvel endpoint :
- `GET /api/v1/pieces/{pieceId}/photos` -> 200 `[{ "id": uuid, "pieceId": uuid, "type": "RECTO|VERSO", "typeMime": "image/jpeg|image/png", "tailleOctets": number, "creeLe": ISO-8601 }]` (`[]` si aucune).
- Erreurs : 401 sans token ; 403 rôle non autorisé (AUDITEUR…) ou hors périmètre ; 404 `{code:"PIECE_INTROUVABLE",message}`.
- RBAC : `hasAnyRole('AGENT','CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')`, périmètre via `PerimetrePoste.estDansPerimetre`.

Endpoint existant utilisé : `POST /api/v1/pieces/{id}/photos` multipart, parts/params `type` (RECTO|VERSO) et `fichier` ; 201 `UploadPhotoResponse`. Côté client : `FormData` avec `append('type', type)` et `append('fichier', fichier)`, header `Authorization` uniquement.

Mapping erreurs frontend (par `code`, sinon statut) :
| code / statut | message agent |
|---|---|
| TYPE_FICHIER_NON_AUTORISE (415) | « Format non accepté : utilisez une image JPEG ou PNG. » |
| FICHIER_TROP_VOLUMINEUX (413) | « La photo dépasse 10 Mo. » |
| PHOTO_DEJA_EXISTANTE (409) | « Une photo de ce côté existe déjà pour cette fiche. » |
| STOCKAGE_INDISPONIBLE (500) | « Stockage des photos indisponible, réessayez plus tard. » |
| PIECE_INTROUVABLE (404) | « Pièce introuvable. » |
| 401 | « Session expirée, reconnectez-vous. » |
| 403 | « Cette pièce n'est pas dans votre périmètre. » |
| autre | `Erreur {status}` |

Validation cliente : `file.type ∈ {image/jpeg, image/png}` et `file.size ≤ 10 Mo` ; le serveur reste l'autorité (magic number). Aucune persistance de la photo côté client (ni localStorage, IndexedDB, cache SW).

## Plan de tests

| Critère d'acceptation | Test |
|---|---|
| Champ photo optionnel + aperçu avant envoi | `EnregistrementPiecePage.test.tsx` : sélection d'un fichier -> `<img>` d'aperçu présent (mock `URL.createObjectURL`), révoqué au retrait ; soumission sans photo -> `uploaderPhoto` jamais appelé et reçu affiché ; soumission avec photo -> `uploaderPhoto(id,'RECTO',file)` appelé après `creerPiece` |
| Erreurs backend affichées clairement | `photosApi.test.ts` : 415/413/409/500/404/401/403 -> message et `code` attendus ; FormData sans `Content-Type` manuel ; corps non JSON toléré. `EnregistrementPiecePage.test.tsx` : échec photo (409) -> reçu affiché + alerte, `creerPiece` appelé une seule fois. `PhotoUpload` via page : fichier GIF ou > 10 Mo rejeté côté client avec message |
| Photo consultable depuis reçu / fiche | `FichePieceCard.test.tsx` : `listerPhotos` mocké renvoie une photo -> image affichée ; liste vide + rôle AGENT -> contrôle d'ajout ; rôle ADMIN_REGIONAL -> pas de contrôle d'ajout ; 403 liste -> rien affiché. Backend : `PhotoIntegrationTest` `lister_commeAgentMemePoste_shouldRetourner200AvecMetadonnees`, `lister_pieceInexistante_shouldRetourner404`, `lister_commeAgentAutrePoste_shouldRetourner403`, `lister_commeAdminRegionalMemeRegion_shouldRetourner200`, `lister_commeAuditeur_shouldRetourner403`, `lister_sansToken_shouldRetourner401` ; `PhotoServiceTest` cas de `lister` |
| Cohérence hors-ligne (photo ne bloque pas la fiche) | `EnregistrementPiecePage.test.tsx` : `creerPiece` échoue en erreur réseau avec photo sélectionnée -> `mettreEnFile` appelé avec le payload SANS photo, `uploaderPhoto` non appelé, message « sans photo » affiché. Manuel : vérification DevTools (IndexedDB `fiches-en-attente` sans blob ; aucune entrée de cache SW pour `/photos`) |

## Écarts identifiés
1. **Gardes upload/lecture différentes** : l'upload exige le même poste (`getPoste().getId().equals(...)`), la lecture utilise `PerimetrePoste` (région pour ADMIN_REGIONAL). Sans impact ici ; le bouton d'ajout est masqué aux seuls rôles non AGENT/CHEF_POSTE, un CHEF_POSTE d'un autre poste verra la liste (200 ou 403 selon périmètre) et recevra un 403 à l'upload (message « périmètre » affiché).
2. **Erreurs de `piecesApi.ts` sans `code`** : le design demande un mapping par `code` ; `piecesApi.ts` ne lit pas le corps. Résolu en isolant la logique dans `photosApi.ts` (`PhotoApiError`), sans toucher `PieceApiError`. Le test existant de `EnregistrementPiecePage` qui détecte le hors-ligne via `instanceof PieceApiError` reste valable pour `creerPiece` ; l'upload photo est dans un try/catch séparé et ne doit jamais déclencher `mettreEnFile`.
3. **Hors-ligne** : le critère du ticket est « à clarifier » ; tranché par le design (photo non supportée hors ligne, avec message). À valider côté produit : un agent hors-ligne perd la photo sélectionnée.
4. **Rôle possiblement `null`** : `roleAgentCourant` vaut `null` tant que `recupererAgentCourant` n'a pas répondu (ou en cas d'échec) ; `PhotosFiche` masque alors l'ajout (comportement sûr par défaut).
5. **Nettoyage de test** : `PhotoIntegrationTest.nettoyer()` ne vide pas `piece_sequence` ; sans conséquence tant que les pièces sont créées via `pieceRepository.save`, à ajouter sinon (cf. mémoire projet).
6. **SW PWA** : vérifié, aucun `runtimeCaching` ; aucune action, sauf si une config future ajoute du caching sur `/api`.
