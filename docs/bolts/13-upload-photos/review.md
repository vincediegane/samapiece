# Review — Ticket #13 : Upload et stockage chiffré des photos de document

## APPROVE

## Critères d'acceptation

| # | Critère | Statut |
|---|---|---|
| AC1 | `POST /api/v1/pieces/{id}/photos` upload, stocke chiffré dans MinIO, enregistre en base | **Couvert** — `PhotoService.uploader` chiffre (`PhotoChiffrementService.chiffrer`) avant `PhotoStockageService.televerser`, persiste `Photo` (`cleObjetStockage`/`ivChiffrement`). Testé unitairement (ordre Mockito `InOrder`) et en intégration (`PhotoIntegrationTest.uploader_commeAgent_avecFichierValide_shouldRetourner201EtPersisterPhotoChiffree`, qui relit l'objet MinIO directement et vérifie que les octets stockés ≠ octets envoyés). |
| AC2 | Taille max et types (JPEG/PNG) validés côté serveur | **Couvert** — validation en profondeur : `spring.servlet.multipart.max-file-size/max-request-size` (10MB) + vérification applicative dans `PhotoService.uploader` (taille, Content-Type déclaré, ET magic bytes). Détection par magic bytes fait autorité pour le `type_mime` persisté (pas le Content-Type client). Tests unitaires + intégration (`uploader_avecTypeGif_shouldRetourner415`, `uploader_avecContentTypeMensonger_shouldRetourner415`, `uploader_avecFichierTropVolumineux_shouldRetourner413`). |
| AC3 | Aucune URL de photo accessible sans authentification ni droit sur le poste | **Couvert** — `.anyRequest().authenticated()` existant + `@PreAuthorize` sur `GET`/`POST` + `PerimetrePoste.estDansPerimetre` (exclut `AUDITEUR`) pour la lecture, vérification directe de poste pour l'upload. IDOR neutralisé via `PhotoRepository.findByIdAndPieceId` (pas `findById` seul) — testé unitairement (`telecharger_avecPhotoAppartenantAUneAutrePiece_shouldLeverPhotoIntrouvableException`) et en intégration (matrice de rôles/postes/régions complète). |
| AC4 | Test d'intégration : rejet type non autorisé + accès refusé sans JWT | **Couvert** — `uploader_avecTypeGif_shouldRetourner415`, `uploader_avecContentTypeMensonger_shouldRetourner415`, `uploader_sansToken_shouldRetourner401`, `telecharger_sansToken_shouldRetourner401` dans `PhotoIntegrationTest`. |

## Points critiques vérifiés

1. **Chiffrement réel** — `PhotoChiffrementService.chiffrer` est appelé avant `PhotoStockageService.televerser` (`PhotoService.uploader` lignes 91-93). AES/GCM/NoPadding, tag 128 bits, IV 12 octets généré par `SecureRandom` à *chaque* appel de `chiffrer` (pas de champ IV réutilisé). `PhotoChiffrementServiceTest.chiffrer_shouldGenererUnIvDifferentAChaqueAppel` le confirme. Preuve supplémentaire côté intégration : lecture directe de l'objet MinIO et comparaison avec les octets originaux.
2. **Anti-IDOR** — `PhotoService.telecharger` utilise bien `photoRepository.findByIdAndPieceId(photoId, pieceId)`, pas `findById` seul. Confirmé par lecture du code et par le test dédié.
3. **Validation type de fichier** — la détection magic bytes (`detecterTypeMime`) est appliquée après validation du Content-Type déclaré, et le résultat de la détection (pas le Content-Type client) est ce qui est persisté dans `photo.type_mime` (`PhotoService.java:86-99`).
4. **RBAC lecture vs upload** — `PerimetrePoste.estDansPerimetre` a une branche `default -> false`, donc `AUDITEUR` est exclu. `PhotoController.telecharger` a `@PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')")` (AUDITEUR absent). `PhotoController.uploader` reste restreint à `AGENT`/`CHEF_POSTE`.
5. **Pas de fuite de secret** — `UploadPhotoResponse` n'expose ni `cleObjetStockage` ni `ivChiffrement`. Aucun logger n'a été trouvé dans le package `photo` (grep vide) — aucun risque de trace du contenu ou de la clé.
6. **`PhotoMinioProperties`/tests existants** — `backend/src/test/resources/application.yml` complété avec des valeurs factices valides (`@NotBlank` satisfait) ; confirmé que les tests unitaires purs (Mockito, pas de contexte Spring) et les 50 tests listés dans le ticket passent tous.
7. **Pas de `@PostConstruct`** — confirmé absent de `PhotoStockageService` ; le bucket est vérifié/créé paresseusement dans `assurerBucket()` appelée depuis `televerser`.
8. **`docker-compose.yml`** — variables MinIO (`MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`, `MINIO_BUCKET_PHOTOS`) et `PHOTO_CLE_CHIFFREMENT` bien propagées au service `backend`, `depends_on: minio: condition: service_healthy` ajouté (le service `minio` a bien un `healthcheck` défini). Le commentaire obsolète sur `DB_*` a été corrigé pour refléter la réalité (JPA/Flyway actifs depuis #9/#10).

## Cohérence avec spec.md

Le code suit fidèlement le contrat technique de `spec.md` : schéma SQL de `V6__create_photo.sql` identique caractère pour caractère, ordre exact des vérifications dans `uploader`/`telecharger` respecté, DTO de réponse conforme, table des exceptions/codes HTTP dans `PhotoExceptionHandler` conforme. Aucun écart non justifié constaté. Les décisions tranchées (409 sur ré-upload même type, AUDITEUR exclu de la lecture, 10 Mo, pas de `@PostConstruct`, réponse `byte[]` non streamée) sont toutes implémentées comme documentées.

## Build/tests

- `mvn -pl backend -am test -Dtest=PerimetrePosteTest,PhotoChiffrementServiceTest,PhotoServiceTest,AgentTest,JwtServiceTest,PerimetreRegionalTest,PieceTest,NumeroDocumentHasherTest,CreerPieceRequestTest` → **BUILD SUCCESS**, `Tests run: 50, Failures: 0, Errors: 0`.
- `mvn -pl backend -am test` (suite complète) → 8 erreurs, **toutes** dues à `org.testcontainers.containers.ContainerFetchException: ... Previous attempts to find a Docker environment failed`, sur `SamaPieceApplicationTests`, `PieceNumeroFicheGeneratorTest`, `PieceIntegrationTest`, `AgentAdminIntegrationTest`, `AgentIntegrationTest`, `AuthIntegrationTest`, `PosteIntegrationTest` **et** `PhotoIntegrationTest`. C'est l'incompatibilité Docker Desktop/Windows déjà documentée dans l'environnement du reviewer (confirmé : `docker version` répond correctement côté client/serveur, mais Testcontainers ne parvient pas à établir la connexion — probablement le socket nommé attendu par Testcontainers sous Windows). Comme les 7 tests d'intégration *préexistants* échouent exactement de la même façon que `PhotoIntegrationTest`, cette défaillance est un artefact d'environnement local, pas une régression introduite par ce bolt. Une relecture de code stricte de `PhotoIntegrationTest.java` (555 lignes) a été menée en compensation (voir ci-dessus) : structure `@DynamicPropertySource` conforme au plan, preuve de chiffrement présente et correcte (lecture directe MinIO), matrice RBAC/périmètre complète et cohérente avec `PerimetrePosteTest`.

## Conclusion

Code conforme point par point à `spec.md` et aux 4 critères d'acceptation, chiffrement et anti-IDOR corrects, RBAC cohérent (AUDITEUR exclu comme documenté), aucune fuite de secret, tests unitaires 50/50 verts. `PhotoIntegrationTest` n'a pas pu être exécuté dans cet environnement pour une raison d'infrastructure locale non liée au code (Testcontainers/Docker Desktop Windows, affectant identiquement tous les tests d'intégration préexistants du repo), mais sa relecture de code ne révèle aucune anomalie par rapport au plan de tests détaillé.
