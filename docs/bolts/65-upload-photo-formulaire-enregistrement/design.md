# Design #65 - Upload de photo dans le formulaire d'enregistrement

## Approche
Le backend d'upload/consultation existe (`PhotoController`, `PhotoService`, `PhotoExceptionHandler`) ; le travail est surtout frontend. La photo est envoyee en **second appel**, apres `creerPiece` reussi, car `POST /pieces/{id}/photos` exige un `pieceId` existant. Un echec photo ne remet jamais en cause la fiche creee. Un seul petit ajout backend est necessaire : aucun endpoint ne permet aujourd'hui de savoir quelles photos une piece possede (`PieceResponse` n'a pas d'ids photo, `PhotoRepository` n'a que `findByPieceIdAndType`/`findByIdAndPieceId`), donc la consultation est impossible sans lui. Prix : un endpoint de liste en plus, plutot que de modifier `PieceResponse` (evite de casser ses consommateurs/tests).

## Fichiers/modules impactes
Backend (existants, package `sn.samapiece.enregistrement.photo`) :
- `web/PhotoController.java` : ajouter `GET /api/v1/pieces/{pieceId}/photos` -> `List<UploadPhotoResponse>` (metadonnees seules, memes roles/perimetre que le GET unitaire).
- `PhotoService.java` : methode `lister(pieceId)` (meme controle de poste/perimetre que `telecharger`).
- `PhotoRepository.java` : `List<Photo> findByPieceId(UUID)`.
- Tests : `backend/src/test/java/sn/samapiece/enregistrement/photo/PhotoIntegrationTest.java` (liste, 404 piece, perimetre). Rappel memoire : vider `piece_sequence` avant de supprimer les Poste dans les tests @SpringBootTest ; `getContentAsString(StandardCharsets.UTF_8)`.

Frontend (`frontend/src/features/pieces/`) :
- Creer `photosApi.ts` : `uploaderPhoto(pieceId, type, fichier)` (FormData, **sans** Content-Type manuel, Bearer comme `piecesApi.ts`), `listerPhotos(pieceId)`, `telechargerPhoto(pieceId, photoId)` -> Blob. Erreur typee (`PhotoApiError` ou reutilisation `PieceApiError`) qui lit `{code,message}` du corps JSON.
- Creer `PhotoUpload.tsx` (input file `accept="image/jpeg,image/png"`, select RECTO/VERSO, apercu via `URL.createObjectURL` revoque au demontage/changement, bouton retirer) et `PhotosFiche.tsx` (liste + affichage des photos existantes via blob URL, ajout si un type manque).
- Modifier `EnregistrementPiecePage.tsx` (etat `photo`/`typePhoto`, enchainement post-creation) et `FichePieceCard.tsx` (integrer `PhotosFiche` : couvre le recu ET `ConsulterFichePage.tsx`, qui l'utilisent tous deux).
- Tests : `EnregistrementPiecePage.test.tsx`, `FichePieceCard.test.tsx`, nouveau `photosApi.test.ts`.

## Decisions cles
1. **Hors-ligne : photo non prise en charge hors-ligne (decision tranchee).** Si `creerPiece` echoue et que la fiche part en file (`mettreEnFile`, store IndexedDB `fiches-en-attente`), la photo selectionnee est abandonnee et un message explicite l'indique : « fiche enregistree localement, sans photo ; ajoutez-la depuis la fiche une fois synchronisee ». Justification : stocker un Blob (jusqu'a 10 Mo) dans IndexedDB imposerait de bumper le schema `db.ts` (version 1), d'etendre `FicheEnAttente`, et d'enchainer dans `envoyerItem` un upload dependant de l'id serveur (nouveaux etats d'echec partiel, quota stockage mobile). Trop risque pour un champ optionnel ; la fiche, elle, ne doit jamais etre bloquee. Ne pas toucher `fileSynchronisation.ts`/`db.ts`/`types.ts` offline.
2. **Echec photo apres fiche creee** : le recu s'affiche normalement, avec une alerte photo non bloquante ; l'agent retente via `PhotosFiche` sur la fiche. Pas de rollback de la piece.
3. **Type de photo** : le backend exige `type` (RECTO/VERSO). Le formulaire propose un select (defaut RECTO) pour une photo ; l'ajout de l'autre face se fait depuis la fiche. Une photo par (piece,type) : 409 `PHOTO_DEJA_EXISTANTE`.
4. **Messages d'erreur** : mapper par `code` du corps (`TYPE_FICHIER_NON_AUTORISE` 415, `FICHIER_TROP_VOLUMINEUX` 413, `PHOTO_DEJA_EXISTANTE` 409, `STOCKAGE_INDISPONIBLE` 500, `PIECE_INTROUVABLE` 404), plus 401/403 comme dans `piecesApi.ts`. Validation cliente en amont (type jpeg/png, <= 10 Mo) pour un retour immediat ; le serveur reste l'autorite (il verifie aussi le magic number).
5. **Affichage** : le GET renvoie du binaire authentifie (Bearer) -> pas de `<img src>` direct ; fetch en Blob + `URL.createObjectURL`, revoque au nettoyage. Offline sur la fiche : afficher « photo indisponible hors connexion », sans bloquer la fiche.
6. **RBAC** : upload AGENT/CHEF_POSTE (existant) ; lecture AGENT/CHEF_POSTE/ADMIN_REGIONAL/ADMIN_NATIONAL. Le nouveau GET liste reprend exactement la garde du GET unitaire. Masquer le bouton d'ajout pour les roles non autorises (role deja fourni via `roleAgentCourant`).

## Risques / points d'attention
- `PieceResponse` sans photo : ne pas y ajouter d'ids (regression tests existants) ; passer par la liste.
- Donnees personnelles : la photo est une image de piece d'identite ; ne jamais la persister cote client (ni localStorage, ni IndexedDB, ni cache du service worker) ; revoquer les blob URLs ; ne pas la journaliser.
- Verifier que le service worker PWA ne met pas en cache les reponses `/photos/` (vue de la config Vite PWA a faire par le codeur).
- Ordre des appels et double soumission : bouton desactive pendant l'envoi (`enEnvoi`) pour la fiche ET la photo ; ne pas recreer la piece si seule la photo echoue.
- Multipart : ne pas fixer `Content-Type` a la main (boundary) ; `enTeteAutorisation()` actuel force `application/json`, donc ne pas le reutiliser tel quel.
- Depassement de `max-file-size: 10MB` (application.yml) : renvoye en 413 `FICHIER_TROP_VOLUMINEUX` par `MaxUploadSizeExceededException`, deja gere.
- Le `Content-Type` declare par le navigateur doit valoir exactement `image/jpeg`/`image/png` (photos HEIC d'iPhone rejetees : message clair).

## Hors perimetre
- File d'attente/synchronisation hors-ligne des photos, modification du schema IndexedDB.
- Suppression/remplacement de photo, compression/redimensionnement cote client, capture camera dediee.
- Modification du chiffrement, du stockage ou de la migration `V6__create_photo.sql`.
- Ajout de photos a `PieceResponse`, galerie pour les roles admin (au-dela de l'affichage dans la fiche).
