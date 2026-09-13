# Spec — Ticket #13 : Upload et stockage chiffré des photos de document

## Résumé

Ajout du sous-module `sn.samapiece.enregistrement.photo` : upload chiffré (AES-256-GCM applicatif) d'une photo recto/verso vers MinIO avec référence persistée en table `photo` (migration `V6`), et endpoint de lecture streamée scopé par RBAC/périmètre poste-région, avec validation serveur du type (Content-Type + magic bytes) et de la taille (10 Mo).

## Décisions tranchées (points laissés ouverts par le design)

1. **Ré-upload du même type recto/verso** : `409 Conflict` (`PHOTO_DEJA_EXISTANTE`), pas de remplacement silencieux. Cohérent avec l'absence de `PATCH`/`DELETE` sur `/photos/{photoId}` (hors périmètre du ticket) : il n'existe aujourd'hui aucune opération de remplacement propre, donc autoriser un remplacement implicite via un second `POST` serait une sémantique cachée. Un futur ticket pourra ajouter un `DELETE` explicite avant de permettre un nouveau `POST`.
2. **AUDITEUR en lecture** : **exclu** de ce ticket. Les critères d'acceptation ne mentionnent que "droit sur le poste concerné" (périmètre agent/chef de poste/admin), et aucune règle de périmètre national/régional pour `AUDITEUR` n'existe ailleurs dans le code (contrairement à `ADMIN_REGIONAL`/`ADMIN_NATIONAL` qui ont `PerimetreRegional`). Inventer un périmètre de lecture pour `AUDITEUR` sans spécification déborderait ce ticket. Décision : `AUDITEUR` reçoit `403` sur `GET /photos/{photoId}`, exactement comme il reçoit déjà `403` sur `POST /pieces` aujourd'hui. Un futur ticket pourra définir explicitement le périmètre d'audit.
3. **Taille max** : confirmée à **10 Mo** (10 × 1024 × 1024 octets), appliquée à deux niveaux redondants : `spring.servlet.multipart.max-file-size`/`max-request-size` (rejet avant même d'atteindre le contrôleur) et une vérification applicative dans `PhotoService` (défense en profondeur, message d'erreur métier cohérent avec les autres validations).
4. **Provisionnement du bucket MinIO** : **pas** de vérification/création au démarrage de l'application (pas de `@PostConstruct`). Un `@PostConstruct` qui interroge MinIO ferait échouer le démarrage du contexte Spring de **tous** les tests `@SpringBootTest` existants (ex. `PieceIntegrationTest`) qui ne fournissent pas de conteneur MinIO. À la place, `PhotoStockageService.televerser(...)` vérifie/crée le bucket **paresseusement**, juste avant le premier `putObject` (idempotent : `bucketExists` puis `makeBucket` si absent). Cela fonctionne aussi bien en dev/docker-compose qu'en test d'intégration, sans étape de provisionnement séparée.
5. **"Streaming" de la lecture** : au vu du plafond de 10 Mo, l'endpoint `GET` déchiffre en mémoire et renvoie un corps `byte[]` complet (`ResponseEntity<byte[]>`) avec le bon `Content-Type` — pas de `Transfer-Encoding: chunked` ni de passthrough d'`InputStream` non bufferisé. AES-GCM authentifie l'intégralité du texte chiffré via son tag, donc un déchiffrement réellement flux-par-flux exigerait une gestion de buffer bien plus complexe pour un gain nul à cette taille de fichier. Ceci satisfait le critère d'acceptation 3 (accès contrôlé) sans complexité inutile.

## Tâches

- [ ] `backend/pom.xml` : ajouter la dépendance `io.minio:minio` (dernière version stable compatible Spring Boot 3.3/Java 21) et `org.testcontainers:minio` en `scope=test`.
- [ ] `backend/src/main/resources/db/migration/V6__create_photo.sql` : créer la table `photo` (schéma exact ci-dessous).
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/photo/TypePhoto.java` : enum `RECTO`, `VERSO`.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/photo/TypePhotoConverter.java` : `AttributeConverter<TypePhoto, String>` sur le modèle de `TypeDocumentConverter` (minuscules en base).
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/photo/Photo.java` : entité JPA immuable (pas de setter), sur le modèle de `Piece.java`.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/photo/PhotoRepository.java` : `JpaRepository<Photo, UUID>` avec `findByPieceIdAndType(UUID, TypePhoto)` et `findByIdAndPieceId(UUID, UUID)`.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/PieceIntrouvableException.java` : nouvelle exception (n'existait pas avant ce ticket, `PieceService.creer` n'ayant jamais eu besoin de charger une pièce par id), sur le modèle de `PosteIntrouvableException`.
- [ ] `backend/src/main/java/sn/samapiece/iam/security/PerimetrePoste.java` : nouveau helper statique `estDansPerimetre(Agent appelant, Poste posteCible)` — `AGENT`/`CHEF_POSTE` → même poste ; `ADMIN_REGIONAL`/`ADMIN_NATIONAL` → délègue à `PerimetreRegional.estDansPerimetreRegion` ; tout autre rôle (dont `AUDITEUR`) → `false`. Utilisé uniquement par la lecture (`GET`), pas par l'upload (voir contrat technique).
- [ ] `backend/src/test/java/sn/samapiece/iam/security/PerimetrePosteTest.java` : test unitaire du helper (une assertion par branche de rôle), sur le modèle de `PerimetreRegionalTest.java`.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/photo/PhotoMinioProperties.java` : `@ConfigurationProperties(prefix = "samapiece.minio")` (`endpoint`, `accessKey`, `secretKey`, `bucketPhotos`, tous `@NotBlank`), sur le modèle de `JwtProperties`.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/photo/PhotoChiffrementService.java` : chiffrement/déchiffrement AES-256-GCM.
- [ ] `backend/src/test/java/sn/samapiece/enregistrement/photo/PhotoChiffrementServiceTest.java` : round-trip, détection d'altération, clé de taille invalide.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/photo/PhotoStockageException.java` : exception non vérifiée enveloppant les exceptions vérifiées du SDK MinIO.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/photo/PhotoStockageService.java` : encapsule `MinioClient` (upload/download par clé d'objet + création paresseuse du bucket).
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/photo/PhotoIntrouvableException.java`, `TypeFichierNonAutoriseException.java`, `FichierTropVolumineuxException.java`, `PhotoDejaExistanteException.java`.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/photo/PhotoService.java` : orchestration upload (RBAC poste + unicité + validation + chiffrement + stockage + persistance) et lecture (RBAC périmètre + déchiffrement).
- [ ] `backend/src/test/java/sn/samapiece/enregistrement/photo/PhotoServiceTest.java` : tests unitaires Mockito (voir plan de tests).
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/photo/web/UploadPhotoResponse.java` : DTO de réponse.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/photo/web/PhotoController.java` : `POST`/`GET` sous `/api/v1/pieces/{pieceId}/photos`.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/photo/web/PhotoExceptionHandler.java` : `@RestControllerAdvice` (ne pas dupliquer `AccesRefuseException`, déjà géré globalement par `AgentAdminExceptionHandler`).
- [ ] `backend/src/main/resources/application.yml` : ajouter `spring.servlet.multipart.max-file-size`/`max-request-size` (10 Mo) et `samapiece.photo.cle-chiffrement: ${PHOTO_CLE_CHIFFREMENT}`.
- [ ] `backend/src/main/resources/application-dev.yml`, `application-staging.yml`, `application-prod.yml` : ajouter le bloc `samapiece.minio.*` (voir contrat technique pour les valeurs par défaut dev vs staging/prod).
- [ ] `backend/src/test/resources/application.yml` : ajouter `samapiece.minio.*` et `samapiece.photo.cle-chiffrement` avec des valeurs factices valides — **obligatoire**, sans quoi tout `@SpringBootTest` existant (ex. `PieceIntegrationTest`) échoue au démarrage du contexte à cause de la validation `@NotBlank` de `PhotoMinioProperties`/`JwtProperties`.
- [ ] `.env.example` : ajouter `MINIO_BUCKET_PHOTOS` et `PHOTO_CLE_CHIFFREMENT` (valeur d'exemple explicitement invalide, même convention que `JWT_SECRET`).
- [ ] `docker-compose.yml` : propager `MINIO_ENDPOINT` (hardcodé `http://minio:9000`, même logique que `DB_HOST: postgres`), `MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY` (réutilisent `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD` déjà définis), `MINIO_BUCKET_PHOTOS`, `PHOTO_CLE_CHIFFREMENT` au service `backend`, et ajouter `depends_on: minio: condition: service_healthy`.
- [ ] `backend/src/test/java/sn/samapiece/enregistrement/photo/PhotoIntegrationTest.java` : test d'intégration `@SpringBootTest`/`@Testcontainers` avec `PostgreSQLContainer` + `MinIOContainer`, sur le modèle de `PieceIntegrationTest.java` (voir plan de tests).

## Contrat technique

### Schéma SQL — `V6__create_photo.sql`

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

`cle_objet_stockage` = `pieces/{pieceId}/{photoId aléatoire}.enc` (référence interne MinIO, jamais une URL). `iv_chiffrement` = IV base64 (12 octets → 16 caractères, largement sous la limite de 64). Pas de `maj_le` : `Photo` est immuable (aucune modification après création, cf. hors périmètre `PATCH`/`DELETE`).

### `Photo.java` (entité)

Champs : `id` (UUID, généré), `piece` (`@ManyToOne(LAZY, optional=false)` vers `Piece`), `type` (`TypePhoto`, converti), `cleObjetStockage` (String), `typeMime` (String), `tailleOctets` (long), `ivChiffrement` (String), `creeLe` (`OffsetDateTime`, `insertable=false`). Constructeur unique prenant tous les champs métier (pas `id`/`creeLe`), `equals`/`hashCode` sur `id` — copie exacte du style de `Piece.java`.

### `PhotoRepository.java`

```java
public interface PhotoRepository extends JpaRepository<Photo, UUID> {
    Optional<Photo> findByPieceIdAndType(UUID pieceId, TypePhoto type);
    Optional<Photo> findByIdAndPieceId(UUID id, UUID pieceId);
}
```

`findByIdAndPieceId` est essentiel côté sécurité : il garantit qu'un `photoId` valide mais rattaché à une **autre** pièce que celle de l'URL renvoie `404`, pas la photo d'une pièce différente (IDOR).

### `sn.samapiece.enregistrement.PieceIntrouvableException.java`

```java
public class PieceIntrouvableException extends RuntimeException {
    public PieceIntrouvableException(UUID id) {
        super("Piece introuvable : " + id);
    }
}
```

Géré par `PhotoExceptionHandler` (bien que la classe vive dans le package `enregistrement`, un `@RestControllerAdvice` sans restriction de package intercepte les exceptions levées par n'importe quel contrôleur — exactement comme `AgentAdminExceptionHandler` gère déjà `AccesRefuseException` levée par `PieceService`).

### `PerimetrePoste.java`

```java
package sn.samapiece.iam.security;

public final class PerimetrePoste {
    private PerimetrePoste() {}

    public static boolean estDansPerimetre(Agent appelant, Poste posteCible) {
        return switch (appelant.getRole()) {
            case AGENT, CHEF_POSTE -> appelant.getPoste().getId().equals(posteCible.getId());
            case ADMIN_REGIONAL, ADMIN_NATIONAL ->
                    PerimetreRegional.estDansPerimetreRegion(appelant, posteCible.getRegion().getId());
            default -> false;
        };
    }
}
```

Utilisé **uniquement** par `PhotoService.telecharger` (lecture). L'upload vérifie directement `appelant.getPoste().getId().equals(piece.getPoste().getId())` en ligne dans `PhotoService.uploader` — pas de réutilisation de ce helper à l'upload, car `@PreAuthorize` y restreint déjà les appelants à `AGENT`/`CHEF_POSTE`, et la règle d'upload est plus stricte/différente en intention (créer une ressource, pas la consulter) que celle de lecture.

### `PhotoChiffrementService.java`

```java
package sn.samapiece.enregistrement.photo;

@Component
public class PhotoChiffrementService {

    public PhotoChiffrementService(@Value("${samapiece.photo.cle-chiffrement}") String cleBase64) { ... }

    public PhotoChiffree chiffrer(byte[] octetsClair);
    public byte[] dechiffrer(byte[] octetsChiffres, String ivBase64);

    public record PhotoChiffree(byte[] octetsChiffres, String ivBase64) {}
}
```

- Transformation `AES/GCM/NoPadding`, tag 128 bits, IV 12 octets généré par `SecureRandom` à chaque appel de `chiffrer`.
- Le constructeur décode `cleBase64` et **lève `IllegalStateException` si le résultat ne fait pas exactement 32 octets** (AES-256) — fail-fast au démarrage plutôt qu'une erreur de chiffrement silencieuse plus tard.
- `dechiffrer` propage une `IllegalStateException` si le tag GCM ne correspond pas (données altérées ou mauvaise clé) — pas de récupération partielle possible avec GCM, comportement voulu.

### `PhotoMinioProperties.java` / `PhotoStockageService.java`

```java
@Component
@Validated
@ConfigurationProperties(prefix = "samapiece.minio")
public class PhotoMinioProperties {
    @NotBlank private String endpoint;
    @NotBlank private String accessKey;
    @NotBlank private String secretKey;
    @NotBlank private String bucketPhotos;
    // getters/setters
}
```

```java
@Service
public class PhotoStockageService {
    public PhotoStockageService(PhotoMinioProperties proprietes); // construit MinioClient en interne

    public void televerser(String cleObjet, byte[] octetsChiffres); // assure le bucket puis putObject
    public byte[] telecharger(String cleObjet); // getObject puis readAllBytes()
}
```

- Contenu stocké dans MinIO avec `Content-Type: application/octet-stream` (le blob est chiffré, le vrai type MIME vit uniquement dans la colonne `photo.type_mime`, jamais dans MinIO).
- Toute exception vérifiée du SDK (`ErrorResponseException`, `InsufficientDataException`, `InternalException`, `InvalidKeyException`, `InvalidResponseException`, `IOException`, `NoSuchAlgorithmException`, `ServerException`, `XmlParserException`) est capturée globalement (`catch (Exception e)`) et enveloppée dans `PhotoStockageException` (RuntimeException).
- **Aucun `@PostConstruct`** : voir décision tranchée n°4.

### `PhotoService.java`

```java
@Service
public class PhotoService {
    private static final long TAILLE_MAX_OCTETS = 10L * 1024 * 1024;
    private static final Set<String> TYPES_MIME_AUTORISES = Set.of("image/jpeg", "image/png");

    public UploadPhotoResponse uploader(UUID pieceId, TypePhoto type, MultipartFile fichier);
    public PhotoTelechargee telecharger(UUID pieceId, UUID photoId);

    public record PhotoTelechargee(byte[] octets, String typeMime) {}
}
```

**Ordre exact des vérifications dans `uploader`** (chaque étape lève l'exception associée, capturée par `PhotoExceptionHandler`) :
1. `appelantCourant()` (même pattern que `PieceService`/`AgentAdminService` : lecture du matricule via `SecurityContextHolder`, `AccesRefuseException` si agent introuvable/inactif).
2. `pieceRepository.findById(pieceId)` → `PieceIntrouvableException` (404) si absent.
3. `appelant.getPoste().getId().equals(piece.getPoste().getId())` → `AccesRefuseException` (403) sinon.
4. `photoRepository.findByPieceIdAndType(pieceId, type)` présent → `PhotoDejaExistanteException` (409).
5. Lecture unique des octets (`fichier.getBytes()`), puis validation : fichier vide → `TypeFichierNonAutoriseException` ; taille > 10 Mo → `FichierTropVolumineuxException` (413) ; `Content-Type` déclaré absent de `TYPES_MIME_AUTORISES` → `TypeFichierNonAutoriseException` (415) ; signature magic bytes ne correspondant à aucun type connu, **ou** ne correspondant pas au `Content-Type` déclaré → `TypeFichierNonAutoriseException` (415).
6. `chiffrementService.chiffrer(octets)`, génération de `cleObjet = "pieces/" + pieceId + "/" + UUID.randomUUID() + ".enc"`, `stockageService.televerser(...)`.
7. Persistance `Photo` + `photoRepository.saveAndFlush(...)`.

**Détection magic bytes** :
- JPEG : `0xFF 0xD8 0xFF` (3 premiers octets).
- PNG : `0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A` (8 premiers octets).
- Le type MIME **persisté** en base (`photo.type_mime`) est celui détecté par magic bytes (autoritaire), pas celui simplement déclaré par le client.

**Ordre exact des vérifications dans `telecharger`** :
1. `appelantCourant()`.
2. `pieceRepository.findById(pieceId)` → `PieceIntrouvableException` (404).
3. `PerimetrePoste.estDansPerimetre(appelant, piece.getPoste())` → `AccesRefuseException` (403) sinon.
4. `photoRepository.findByIdAndPieceId(photoId, pieceId)` → `PhotoIntrouvableException` (404) si absent (couvre aussi le cas où `photoId` existe mais appartient à une autre pièce).
5. `stockageService.telecharger(...)` puis `chiffrementService.dechiffrer(...)`.

### DTO / endpoints REST

**`POST /api/v1/pieces/{pieceId}/photos`** — `@PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE')")`, `consumes = multipart/form-data`.

Requête multipart :
| Partie | Type | Obligatoire | Description |
|---|---|---|---|
| `type` | texte | oui | `RECTO` ou `VERSO` (nom d'enum en majuscules, comme `typeDocument` dans `CreerPieceRequest`) |
| `fichier` | fichier | oui | Contenu binaire, `Content-Type` déclaré `image/jpeg` ou `image/png` |

Réponse `201 Created` :
```json
{
  "id": "uuid",
  "pieceId": "uuid",
  "type": "RECTO",
  "typeMime": "image/jpeg",
  "tailleOctets": 123456,
  "creeLe": "2026-09-13T10:00:00Z"
}
```
`cleObjetStockage` et `ivChiffrement` ne sont **jamais** exposés dans la réponse (référence interne + matériel cryptographique), même logique que `PieceResponse` qui n'expose jamais `numeroDocumentHash`/`numeroDocumentSel`.

Codes d'erreur : `400` (paramètres multipart manquants/malformés — géré par défaut Spring, pas de handler dédié nécessaire), `401` (pas de JWT), `403` (`ACCES_REFUSE` : rôle valide mais poste hors périmètre, ou agent inactif), `404` (`PIECE_INTROUVABLE`), `409` (`PHOTO_DEJA_EXISTANTE`), `413` (`FICHIER_TROP_VOLUMINEUX`), `415` (`TYPE_FICHIER_NON_AUTORISE`).

**`GET /api/v1/pieces/{pieceId}/photos/{photoId}`** — `@PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')")` (`AUDITEUR` volontairement absent, cf. décision tranchée n°2).

Réponse `200 OK` : corps = octets déchiffrés bruts, en-tête `Content-Type` = `photo.typeMime` (`image/jpeg` ou `image/png`).

Codes d'erreur : `401` (pas de JWT), `403` (`ACCES_REFUSE` : rôle exclu par `@PreAuthorize` **ou** poste/région hors périmètre), `404` (`PIECE_INTROUVABLE` ou `PHOTO_INTROUVABLE`).

### `PhotoExceptionHandler.java`

| Exception | Statut | `code` |
|---|---|---|
| `PieceIntrouvableException` | 404 | `PIECE_INTROUVABLE` |
| `PhotoIntrouvableException` | 404 | `PHOTO_INTROUVABLE` |
| `PhotoDejaExistanteException` | 409 | `PHOTO_DEJA_EXISTANTE` |
| `TypeFichierNonAutoriseException` | 415 | `TYPE_FICHIER_NON_AUTORISE` |
| `FichierTropVolumineuxException` | 413 | `FICHIER_TROP_VOLUMINEUX` |
| `MaxUploadSizeExceededException` (Spring, déclenché par `spring.servlet.multipart.max-file-size`) | 413 | `FICHIER_TROP_VOLUMINEUX` |
| `PhotoStockageException` | 500 | `STOCKAGE_INDISPONIBLE` |

`AccesRefuseException` n'est **pas** redéclarée ici (déjà gérée globalement par `AgentAdminExceptionHandler`, un `@RestControllerAdvice` sans restriction de package).

### Configuration

`backend/src/main/resources/application.yml` (ajout, ne pas retirer l'existant) :
```yaml
spring:
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 10MB

samapiece:
  photo:
    cle-chiffrement: ${PHOTO_CLE_CHIFFREMENT}
```

`application-dev.yml` (ajout) :
```yaml
samapiece:
  minio:
    endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
    access-key: ${MINIO_ACCESS_KEY:samapiece_minio}
    secret-key: ${MINIO_SECRET_KEY:samapiece_minio_password}
    bucket-photos: ${MINIO_BUCKET_PHOTOS:samapiece-photos-dev}
```

`application-staging.yml` / `application-prod.yml` (ajout, identique aux deux, sans valeur par défaut — même logique que `spring.datasource` dans ces fichiers) :
```yaml
samapiece:
  minio:
    endpoint: ${MINIO_ENDPOINT}
    access-key: ${MINIO_ACCESS_KEY}
    secret-key: ${MINIO_SECRET_KEY}
    bucket-photos: ${MINIO_BUCKET_PHOTOS}
```

`backend/src/test/resources/application.yml` (fichier existant, à compléter — **critique**, voir tâches) :
```yaml
samapiece:
  jwt:
    secret: test-secret-uniquement-pour-les-tests-automatises-ne-jamais-reutiliser
  minio:
    endpoint: http://localhost:9000
    access-key: test-access-key
    secret-key: test-secret-key
    bucket-photos: samapiece-photos-test
  photo:
    cle-chiffrement: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
```
`cle-chiffrement` ci-dessus est l'encodage base64 de 32 octets à zéro (`Base64.getEncoder().encodeToString(new byte[32])`) — une clé AES-256 syntaxiquement valide, à usage de test uniquement. Ces valeurs `minio.*` factices suffisent à satisfaire `@NotBlank` sur `PhotoMinioProperties` pour **tous** les tests `@SpringBootTest` qui ne touchent pas réellement MinIO (aucun appel réseau n'a lieu tant que `televerser`/`telecharger` ne sont pas invoqués). `PhotoIntegrationTest` surchargera `samapiece.minio.endpoint`/`access-key`/`secret-key` via `@DynamicPropertySource` pour pointer vers son `MinIOContainer`.

`.env.example` (ajout) :
```
# --- MinIO : bucket photos (ticket #13) ---
MINIO_BUCKET_PHOTOS=samapiece-photos-dev

# --- Chiffrement des photos (ticket #13) ---
# Cle AES-256 (32 octets) encodee en base64. Generer une valeur en local avec :
#   openssl rand -base64 32
# Ne jamais utiliser cette valeur d'exemple en staging/prod.
PHOTO_CLE_CHIFFREMENT=changez_moi_avec_une_cle_aes_256_generee_en_base64
```

`docker-compose.yml`, service `backend` — ajout au bloc `environment` existant :
```yaml
      MINIO_ENDPOINT: http://minio:9000
      MINIO_ACCESS_KEY: ${MINIO_ROOT_USER}
      MINIO_SECRET_KEY: ${MINIO_ROOT_PASSWORD}
      MINIO_BUCKET_PHOTOS: ${MINIO_BUCKET_PHOTOS}
      PHOTO_CLE_CHIFFREMENT: ${PHOTO_CLE_CHIFFREMENT}
```
et ajout d'un `depends_on` :
```yaml
    depends_on:
      minio:
        condition: service_healthy
```

`SecurityConfig.java` : **aucune modification nécessaire**. `.anyRequest().authenticated()` couvre déjà `/api/v1/pieces/**/photos/**` ; une requête sans JWT reçoit déjà `401` via `HttpStatusEntryPoint`.

## Plan de tests

Fixtures binaires réutilisables (unitaire et intégration) :
- `octetsJpegValides()` → `byte[] { 0xFF, 0xD8, 0xFF, ...padding... }` avec `Content-Type: image/jpeg`.
- `octetsPngValides()` → `byte[] { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, ...padding... }` avec `Content-Type: image/png`.
- `octetsGifNonAutorise()` → signature GIF (`0x47, 0x49, 0x46, 0x38, 0x39, 0x61`) avec `Content-Type: image/gif`.
- `octetsMensongers()` → contenu texte brut avec `Content-Type: image/png` déclaré (magic bytes ne correspondant pas — teste spécifiquement la vérification magic bytes, pas seulement le `Content-Type`).
- `octetsTropVolumineux()` → signature JPEG valide suivie de > 10 Mo de padding, `Content-Type: image/jpeg`.

| Critère d'acceptation | Test(s) | Type |
|---|---|---|
| AC1 — upload stocke chiffré + référence en base | `PhotoIntegrationTest.uploader_commeAgent_avecFichierValide_shouldRetourner201EtPersisterPhotoChiffree` : `201`, `PhotoRepository.findById` renvoie une ligne avec `cleObjetStockage`/`ivChiffrement` renseignés, **et** lecture directe de l'objet MinIO (via un `MinioClient` de test construit sur le conteneur) prouvant que les octets stockés ≠ octets envoyés (preuve du chiffrement, pas seulement de la persistance) | Intégration |
| AC1 — orchestration correcte | `PhotoServiceTest.uploader_avecFichierValide_shouldChiffrerAvantAppelStockageEtPersisterReference` : vérifie via Mockito que `chiffrementService.chiffrer` est appelé avant `stockageService.televerser`, que `televerser` reçoit le texte chiffré (pas le clair), et que `photoRepository.saveAndFlush` est appelé avec les bons champs | Unitaire (Mockito) |
| AC1 — lecture round-trip | `PhotoIntegrationTest.telecharger_commeAgentMemePoste_shouldRetourner200EtOctetsIdentiquesAUploadInitial` : upload puis GET, assert `octets déchiffrés == octets originaux` | Intégration |
| AC2 — type non autorisé rejeté | `PhotoIntegrationTest.uploader_avecTypeGif_shouldRetourner415` ; `PhotoServiceTest.uploader_avecContentTypeNonAutorise_shouldLeverTypeFichierNonAutoriseException` | Intégration + Unitaire |
| AC2 — magic bytes vérifiés (pas seulement le header HTTP) | `PhotoIntegrationTest.uploader_avecContentTypeMensonger_shouldRetourner415` (Content-Type `image/png` déclaré, octets non-PNG) ; `PhotoServiceTest.uploader_avecMagicBytesNeCorrespondantPasAuContentTypeDeclare_shouldLeverTypeFichierNonAutoriseException` | Intégration + Unitaire |
| AC2 — taille max appliquée | `PhotoIntegrationTest.uploader_avecFichierTropVolumineux_shouldRetourner413` ; `PhotoServiceTest.uploader_avecFichierTropVolumineux_shouldLeverFichierTropVolumineuxException` | Intégration + Unitaire |
| AC3 — pas d'accès sans authentification | `PhotoIntegrationTest.uploader_sansToken_shouldRetourner401` ; `PhotoIntegrationTest.telecharger_sansToken_shouldRetourner401` | Intégration |
| AC3 — pas d'accès sans droit sur le poste (upload) | `PhotoIntegrationTest.uploader_commeAgentDeAutrePoste_shouldRetourner403` | Intégration |
| AC3 — scoping RBAC lecture par rôle | `PhotoIntegrationTest.telecharger_commeAgentMemePoste_shouldRetourner200` ; `telecharger_commeAgentDeAutrePoste_shouldRetourner403` ; `telecharger_commeChefPosteDeAutrePoste_shouldRetourner403` ; `telecharger_commeAdminRegionalMemeRegionAutrePoste_shouldRetourner200` ; `telecharger_commeAdminRegionalHorsRegion_shouldRetourner403` ; `telecharger_commeAdminNational_shouldRetourner200` ; `telecharger_commeAuditeur_shouldRetourner403` (documente la décision tranchée n°2) | Intégration |
| AC3 — IDOR : photoId d'une autre pièce | `PhotoServiceTest.telecharger_avecPhotoAppartenantAUneAutrePiece_shouldLeverPhotoIntrouvableException` | Unitaire |
| AC4 — test d'intégration rejet type + accès refusé sans JWT | Couvert par la combinaison de `uploader_avecTypeGif_shouldRetourner415` (ou `uploader_avecContentTypeMensonger_shouldRetourner415`) et `uploader_sansToken_shouldRetourner401`/`telecharger_sansToken_shouldRetourner401` ci-dessus — aucun test supplémentaire requis, ce sont littéralement les tests déjà listés pour AC2/AC3 | Intégration |
| (dérivé RBAC upload, découle du RBAC §10.3, non listé explicitement mais nécessaire) | `PhotoIntegrationTest.uploader_commeAdminRegional_shouldRetourner403` ; `uploader_commeAdminNational_shouldRetourner403` ; `uploader_commeAuditeur_shouldRetourner403` (mêmes rôles déjà refusés sur `POST /pieces`) | Intégration |
| (dérivé : unicité recto/verso) | `PhotoIntegrationTest.uploader_avecTypeDejaPresentPourLaPiece_shouldRetourner409` ; `PhotoServiceTest.uploader_avecPhotoDejaExistantePourCeType_shouldLeverPhotoDejaExistanteException` | Intégration + Unitaire |
| (dérivé : chiffrement) | `PhotoChiffrementServiceTest.chiffrer_puisDechiffrer_shouldRetournerOctetsOriginaux` ; `dechiffrer_avecIvIncorrect_shouldLeverIllegalStateException` (détection d'altération via le tag GCM) ; `constructeur_avecCleDeTailleIncorrecte_shouldLeverIllegalStateException` | Unitaire |
| (dérivé : helper RBAC) | `PerimetrePosteTest` : une assertion par rôle (`AGENT`/`CHEF_POSTE` même poste vrai/faux, `ADMIN_REGIONAL` même région vrai/faux, `ADMIN_NATIONAL` toujours vrai, `AUDITEUR` toujours faux) | Unitaire |

`PhotoIntegrationTest` : structure attendue, calquée sur `PieceIntegrationTest` avec un second conteneur :
```java
@Container
@ServiceConnection
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

@Container
static MinIOContainer minio = new MinIOContainer("minio/minio:RELEASE.2025-09-07T16-13-09Z");

@DynamicPropertySource
static void proprietesMinio(DynamicPropertyRegistry registry) {
    registry.add("samapiece.minio.endpoint", minio::getS3URL);
    registry.add("samapiece.minio.access-key", minio::getUserName);
    registry.add("samapiece.minio.secret-key", minio::getPassword);
}
```
(`samapiece.minio.bucket-photos` et `samapiece.photo.cle-chiffrement` restent ceux de `backend/src/test/resources/application.yml` — pas besoin de les surcharger dynamiquement, le bucket étant créé paresseusement au premier upload.)

Requête multipart MockMvc, forme attendue :
```java
mockMvc.perform(multipart("/api/v1/pieces/" + pieceId + "/photos")
                .file(new MockMultipartFile("fichier", "photo.jpg", "image/jpeg", octetsJpegValides()))
                .param("type", "RECTO")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isCreated());
```

## Écarts identifiés

- **`docker-compose.yml` contient un commentaire obsolète** sur le service `backend` (lignes ~13-16) affirmant que "le backend actuel ne les [DB_*] utilise jamais" et que Flyway/JDBC seraient désactivés — c'est faux depuis les tickets #9/#10 (`V1`-`V5` sont des migrations actives, `application-dev.yml` configure un vrai `spring.datasource`). Ce commentaire précède directement le bloc que cette tâche modifie (ajout des variables MinIO) ; il est recommandé de le corriger dans la même PR pour éviter de perpétuer une information trompeuse, mais ce n'est pas un blocant fonctionnel de ce ticket.
- **Périmètre de lecture `AUDITEUR`** : tranché à "hors périmètre / 403" faute de règle de périmètre définie ailleurs dans le code pour ce rôle (voir décision tranchée n°2). Si le produit attend réellement un accès lecture pour `AUDITEUR` dès ce ticket, cette décision doit être révisée avant codage — sinon, un ticket futur définira son périmètre (national ? régional ?) et ajustera `PhotoController`/`PerimetrePoste` en conséquence.
- Aucune autre incohérence entre `design.md` et les critères d'acceptation du ticket n'a été identifiée : le design couvre bien les 4 critères, y compris implicitement le critère 3 grâce à l'endpoint `GET` qu'il introduit lui-même.
