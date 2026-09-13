# Design — Ticket #13 : Upload et stockage chiffré des photos de document

## Approche

On ajoute un nouveau sous-module `sn.samapiece.enregistrement.photo` (colocalisé avec `Piece` puisque `PHOTO` est un enfant de `PIECE` au §9.1) exposant deux endpoints protégés : `POST /api/v1/pieces/{id}/photos` (upload) et `GET /api/v1/pieces/{id}/photos/{photoId}` (lecture streamée). Le chiffrement est **applicatif** : le service chiffre les octets en AES-256-GCM avant l'appel MinIO, qui ne voit qu'un blob opaque — MinIO reste un simple stockage objet, pas un composant de sécurité. C'est cohérent avec le choix déjà fait pour `numero_document` (hash SHA-256 applicatif plutôt que dépendre d'une fonctionnalité serveur/DB avancée), évite une dépendance à la configuration SSE-C/SSE-S3 de MinIO (complexe, hors de portée d'un pilote), et garde la clé de déchiffrement entièrement sous contrôle applicatif — condition nécessaire pour que le critère d'acceptation 3 (accès conditionné à l'auth + au périmètre poste) soit réellement effectif : si MinIO exposait des URLs présignées, le contrôle d'accès applicatif serait contournable. Le prix de ce choix : le backend porte la charge CPU du chiffrement/déchiffrement et la gestion du cycle de vie d'une clé symétrique (voir Risques).

Le ticket ne demande explicitement qu'un endpoint d'upload dans ses critères d'acceptation, mais le critère 3 ("Aucune URL de photo n'est accessible sans authentification et sans droit sur le poste concerné") n'est vérifiable que s'il existe un mécanisme de lecture contrôlée par l'application. Décision : l'endpoint `GET` **est livré par ce ticket**, en périmètre minimal (retourne les octets déchiffrés en streaming avec le bon `Content-Type`, sans pagination/listing ni URL présignée), car sans lui le 4e critère (test d'intégration sur l'accès refusé sans JWT) n'aurait rien à tester côté lecture — seul l'upload nécessite un JWT, ce qui est un test trivial déjà couvert par le pattern existant (`@PreAuthorize`). Un listing des photos d'une pièce (`GET /api/v1/pieces/{id}/photos`) n'est pas nécessaire pour ce ticket et est laissé hors périmètre.

## Fichiers/modules impactés

**Nouveaux fichiers backend** (package `sn.samapiece.enregistrement.photo`, à créer — n'existe pas encore) :
- `Photo.java` — entité JPA, immuable comme `Piece` (pas de setter générique), calquée sur le style de `backend/src/main/java/sn/samapiece/enregistrement/Piece.java`.
- `TypePhoto.java` + `TypePhotoConverter.java` — enum `RECTO`/`VERSO` et son `AttributeConverter`, sur le modèle de `TypeDocument`/`TypeDocumentConverter`.
- `PhotoRepository.java` — `JpaRepository<Photo, UUID>` avec `findByPieceId(UUID)`.
- `PhotoChiffrementService.java` — chiffrement/déchiffrement AES-256-GCM (rôle analogue à `NumeroDocumentHasher` mais sur des octets, pas un hash).
- `PhotoStockageService.java` — encapsule le client MinIO (upload/download d'un objet par sa clé), config via `@ConfigurationProperties(prefix = "samapiece.minio")` sur le modèle de `JwtProperties`.
- `PhotoService.java` — orchestration : validation type/taille, génération de la clé objet MinIO, chiffrement, upload, persistance de la référence, et pour la lecture : contrôle de périmètre + déchiffrement + streaming.
- `web/UploadPhotoResponse.java`, `web/PhotoController.java` (routes `POST`/`GET` sous `/api/v1/pieces/{id}/photos`).
- `PhotoIntrouvableException.java`, `TypeFichierNonAutoriseException.java`, `FichierTropVolumineuxException.java` + un `web/PhotoExceptionHandler.java` (`@RestControllerAdvice`) sur le modèle de `AgentAdminExceptionHandler`/`AuthExceptionHandler`.

**Migration Flyway** : `backend/src/main/resources/db/migration/V6__create_photo.sql` (dernière existante : `V5__create_piece_sequence_et_numero_fiche.sql`).

**Config** :
- `backend/pom.xml` — ajout de la dépendance `io.minio:minio` (client MinIO officiel, compatible S3, recommandé au §11.4) ; ajout de `org.testcontainers:minio` en scope test.
- `backend/src/main/resources/application.yml` — bloc `samapiece.minio.*` (endpoint, access-key, secret-key, bucket) et `samapiece.photo.cle-chiffrement` (clé AES en base64), tous résolus via variables d'environnement, jamais en clair (cf. `samapiece.jwt.secret`).
- `.env.example` / `docker-compose.yml` — ajouter `MINIO_BUCKET_PHOTOS` (nom de bucket, absent actuellement) et `PHOTO_CLE_CHIFFREMENT` ; transmettre ces variables au service `backend` dans `docker-compose.yml` (actuellement le service `minio` existe mais aucune variable MinIO n'est propagée au service `backend`).

**Tests** : `backend/src/test/java/sn/samapiece/enregistrement/photo/PhotoIntegrationTest.java` (nouveau), suivant le patron de `PieceIntegrationTest.java` (`@SpringBootTest` + `@Testcontainers`), avec un second `@Container` MinIO (`org.testcontainers.containers.MinIOContainer` du module `org.testcontainers:minio`) en plus du `PostgreSQLContainer` déjà utilisé.

## Décisions clés

1. **Chiffrement applicatif AES-256-GCM avant upload**, clé symétrique unique portée par la configuration (`samapiece.photo.cle-chiffrement`), pas de SSE MinIO. IV aléatoire par photo, stocké à côté du blob chiffré — nécessaire pour le déchiffrement, non secret en soi.
2. **Schéma table `photo`** (V6), plus riche que le squelette indicatif du §9.1 pour couvrir taille/type MIME/validation :
   ```sql
   CREATE TABLE photo (
       id                 UUID PRIMARY KEY,
       piece_id           UUID NOT NULL REFERENCES piece(id),
       type               VARCHAR(10) NOT NULL CHECK (type IN ('recto', 'verso')),
       cle_objet_stockage VARCHAR(255) NOT NULL UNIQUE,
       type_mime          VARCHAR(50) NOT NULL,
       taille_octets      BIGINT NOT NULL,
       iv_chiffrement     VARCHAR(64) NOT NULL,
       cree_le            TIMESTAMPTZ NOT NULL DEFAULT now()
   );
   CREATE INDEX idx_photo_piece_id ON photo(piece_id);
   CREATE UNIQUE INDEX uq_photo_piece_type ON photo(piece_id, type);
   ```
   `cle_objet_stockage` est la clé/chemin de l'objet dans MinIO (ex. `pieces/{pieceId}/{photoId}.enc`), jamais une URL — cohérent avec §9.1 qui nomme la colonne `url_stockage` mais le principe de minimisation/non-exposition du §10.2 impose de ne stocker qu'une référence interne, pas une URL exploitable directement. Contrainte unique `(piece_id, type)` : au plus une photo recto et une verso par pièce, conforme à "une ou deux photos (recto/verso)".
3. **Endpoint de lecture livré dans ce ticket** (`GET /api/v1/pieces/{id}/photos/{photoId}`), avec contrôle de périmètre par un nouveau helper (pas de réutilisation telle quelle de `verifierPerimetrePoste` d'`AgentAdminService`, qui exclut le rôle `AGENT` par construction — ici un `AGENT` doit pouvoir lire les photos des pièces de son propre poste). Règle : `AGENT`/`CHEF_POSTE` → uniquement si `piece.poste.id == appelant.poste.id` ; `ADMIN_REGIONAL` → si même région (réutilise `PerimetreRegional.estDansPerimetreRegion`) ; `ADMIN_NATIONAL` → toujours ; `AUDITEUR` → lecture seule autorisée (cohérent avec persona §6.6) mais à confirmer par le spec-writer, absent des critères d'acceptation explicites.
4. **RBAC upload** : `@PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE')")` comme `PieceController.creer`, plus une vérification applicative que la pièce ciblée appartient au poste de l'appelant (un agent d'un autre poste ne doit pas pouvoir attacher une photo à une pièce qui n'est pas la sienne — non listé explicitement dans les critères mais découle directement du RBAC §10.3).
5. **Validation type/taille** : types acceptés `image/jpeg` et `image/png` (vérifiés sur le `Content-Type` déclaré **et** sur les premiers octets/magic bytes du fichier, pas seulement l'en-tête HTTP, pour éviter un contournement trivial) ; taille max arbitraire fixée à **10 Mo**, valeur à faire valider explicitement par le spec-writer/product owner (pas de valeur donnée par le ticket).
6. **Client MinIO** : `io.minio:minio` (SDK officiel, déjà nommé au §11.4 comme option pour le stockage objet — préféré ici à `software.amazon.awssdk:s3` car plus simple pour un usage MinIO pur, sans les couches de compatibilité IAM/région AWS inutiles en interne).

## Risques / points d'attention

- **Gestion de la clé de chiffrement** : `samapiece.photo.cle-chiffrement` doit suivre exactement la convention déjà posée par le ticket #4 (`config-multi-env`) et par `JWT_SECRET` — jamais commitée, injectée via variable d'environnement/`.env` non versionné, valeur d'exemple explicitement invalide dans `.env.example`. Pas de KMS/Vault dans ce pilote (le §11.4 le recommande à terme) : la clé est un secret applicatif comme les autres, à documenter comme dette technique.
- **Rotation de clé impossible sans ré-chiffrement de masse** : si la clé change, toutes les photos existantes deviennent illisibles. À documenter, pas à résoudre dans ce ticket.
- **Test d'intégration nécessite un conteneur MinIO en plus de PostgreSQL** : alourdit le temps d'exécution des tests (`PieceIntegrationTest` ne démarre qu'un seul conteneur aujourd'hui) ; vérifier que l'environnement CI (GitHub Actions, ticket #2) a assez de ressources pour démarrer deux Testcontainers en parallèle par classe de test.
- **Pas de `@ServiceConnection` officiel Spring Boot pour MinIO** (contrairement à PostgreSQL) : la config du client `MinioClient` de test devra être injectée manuellement (`@DynamicPropertySource` ou équivalent) plutôt que par autoconfiguration, ce qui diverge légèrement du patron `PieceIntegrationTest`.
- **Cohérence avec le cycle de vie de la pièce** : rien dans les critères d'acceptation ne dit ce qu'il advient des photos si la pièce est archivée/détruite (§7.6.4) — probablement un ticket futur (purge/anonymisation), à ne pas anticiper ici.
- **Doublons recto/verso** : la contrainte unique `(piece_id, type)` implique une décision de comportement en cas de ré-upload du même type (409 Conflict vs remplacement) — non spécifié par le ticket, à trancher par le spec-writer.
- **`docker-compose.yml` actuel** ne propage aucune variable MinIO au service `backend` (seulement `DB_*` et `JWT_SECRET`) : à corriger en même temps, sinon le backend ne pourra jamais joindre MinIO en environnement docker-compose local.

## Hors périmètre

- Floutage automatique de la photo d'identité (mentionné au §7.1 comme "optionnel").
- Listing des photos d'une pièce (`GET /api/v1/pieces/{id}/photos` en liste) — seule la lecture unitaire par `photoId` est nécessaire pour vérifier le critère 3.
- Compression/redimensionnement côté client (§11.7) — sujet frontend PWA agent, pas ce ticket backend.
- Gestion de clé via KMS/Vault (§10.4, §11.4) — hors de portée du pilote, secret applicatif classique pour l'instant.
- Purge/suppression physique des photos liée au cycle de vie de la pièce (archivage/destruction, §7.6.4).
- Modification/suppression d'une photo existante (`PATCH`/`DELETE` sur `/photos/{photoId}`) — non demandé par les critères d'acceptation.
