# Spec -- #22 Création et gestion d'une alerte de recherche citoyenne

## Résumé

Nouveau module backend `sn.samapiece.alertes` exposant `POST /api/v1/alertes` (création, contact chiffré réversible, jeton de désinscription envoyé par SMS) et `DELETE /api/v1/alertes/{id}` (désinscription par jeton opaque à usage unique), sans aucune consultation en clair possible et sans aucun changement frontend.

## Décisions tranchées (points ouverts du design)

Ces cinq points sont **fermés** ; le codeur les applique tels quels, sans les réinterpréter.

1. **Sémantique de `{id}`** : le segment `{id}` de `DELETE /api/v1/alertes/{id}` est le jeton opaque de désinscription, jamais l'UUID de l'entité `Alerte`. Format exact : jeton brut = 32 octets `SecureRandom`, encodés en base64url **sans padding** (`Base64.getUrlEncoder().withoutPadding()`), soit une chaîne de 43 caractères dans l'alphabet `[A-Za-z0-9_-]` (aucun `/`, `+`, `=` — donc utilisable tel quel comme unique segment de chemin, sans encodage supplémentaire). La route reste littéralement `/api/v1/alertes/{id}` pour respecter le nommage du ticket/cahier des charges §12, mais le paramètre Java doit être documenté (Javadoc du contrôleur) comme "jeton de désinscription, pas l'identifiant de l'alerte".
2. **Expiration du jeton, sans régénération** : on garde la colonne `expire_le` (pas de cas spécial "sans expiration" dans le schéma/le code), mais la valeur par défaut de `samapiece.alerte.token-expiration-heures` est fixée à **8760** (365 jours), configurable. Choix retenu : option (a) — allonger fortement l'expiration plutôt que construire un endpoint de renvoi. Justification : le critère d'acceptation n'exige aucune régénération, le ticket #23 (worker) n'existe pas encore, et un endpoint de renvoi ajouterait une nouvelle surface publique non authentifiée (donc un nouveau risque d'abus) pour un gain marginal. Compromis assumé et documenté ici : si un citoyen perd son SMS et que le jeton finit par expirer après un an, il n'a aujourd'hui aucun moyen de se désinscrire lui-même ; ce résidu de risque est accepté pour #22 et devra être réévalué avec #23.
3. **Soft-delete + effacement du contact à la désinscription** : à la désinscription, l'alerte n'est pas supprimée physiquement (`active = false`, `maj_le` mis à jour), **mais** `contact_chiffre` et `contact_iv` sont mis à `NULL` immédiatement (colonnes rendues nullable en base, obligatoires uniquement à la création). Choix retenu en application du principe de minimisation des données mentionné dans le design : conserver un contact chiffré après désinscription n'a plus aucune utilité fonctionnelle (aucun code ne le relira jamais) et ne fait qu'étendre inutilement la surface de compromission en cas de fuite de la clé `ALERTE_CLE_CHIFFREMENT`. Compromis assumé : la ligne `alerte` restante après désinscription ne prouve plus quel contact était associé (traçabilité partielle : on sait qu'une alerte a existé, ses critères, et qu'elle a été désinscrite, mais plus par qui) — jugé acceptable puisqu'aucune consultation ni aucun audit nommé n'est requis par le ticket.
4. **Canal `sms` uniquement dans la contrainte CHECK** : `canal VARCHAR(10) NOT NULL DEFAULT 'sms' CHECK (canal IN ('sms'))`. Choix retenu : ne pas accepter `'email'` en base tant qu'aucune infra email n'existe (évite un chemin de validation applicatif mort — rejet d'une valeur qui ne peut de toute façon jamais être insérée — et évite de devoir le tester). Une migration `V8` future ajoutera `'email'` le jour où l'infra existera (`ALTER TABLE alerte DROP CONSTRAINT ... ADD CONSTRAINT ... CHECK (canal IN ('sms','email'))`), ce qui est un changement trivial. Conséquence directe : `CreerAlerteRequest` n'a pas de champ `canal` du tout (le service fixe `"sms"` en dur à la persistance) — pas de valeur à valider côté API.
5. **Frontend** : ce ticket est **strictement backend**. Recherche confirmée dans `frontend/` : aucune occurrence du mot "alerte" (recherche insensible à la casse), aucun composant citoyen de création/désinscription d'alerte n'existe. Le codeur ne touche à aucun fichier sous `frontend/`.

## Tâches

### Migration

- [ ] `backend/src/main/resources/db/migration/V7__create_alerte.sql` : créer les tables `alerte` et `alerte_desinscription_token` (voir Contrat technique > Schéma SQL ci-dessous).

### Domaine

- [ ] `backend/src/main/java/sn/samapiece/alertes/package-info.java` : Javadoc de package une phrase, style `recherche/package-info.java` (ex. "Enregistrement et désinscription des alertes de recherche citoyenne, sans consultation en clair possible.").
- [ ] `backend/src/main/java/sn/samapiece/alertes/Alerte.java` : entité JPA `@Table(name = "alerte")`, `@Id @GeneratedValue(strategy = GenerationType.UUID)`, constructeur métier (hors id/creeLe/majLe), `equals`/`hashCode` sur `id` (calque exact de `Piece.java`). Méthode `desinscrire()` : met `active = false`, `contactChiffre = null`, `contactIv = null`, `majLe = OffsetDateTime.now()`. Champs : voir Contrat technique.
- [ ] `backend/src/main/java/sn/samapiece/alertes/AlerteDesinscriptionToken.java` : entité JPA `@Table(name = "alerte_desinscription_token")`, mêmes conventions. Méthode `consommer()` : met `consommeLe = OffsetDateTime.now()`. Méthode `estValide(OffsetDateTime maintenant)` : retourne `consommeLe == null && expireLe.isAfter(maintenant)`.
- [ ] `backend/src/main/java/sn/samapiece/alertes/AlerteRepository.java` : `interface AlerteRepository extends JpaRepository<Alerte, UUID>`.
- [ ] `backend/src/main/java/sn/samapiece/alertes/AlerteDesinscriptionTokenRepository.java` : `interface AlerteDesinscriptionTokenRepository extends JpaRepository<AlerteDesinscriptionToken, UUID>` avec `Optional<AlerteDesinscriptionToken> findByTokenHash(String tokenHash)`.
- [ ] `backend/src/main/java/sn/samapiece/alertes/AlerteContactChiffrementService.java` : copie conforme de `PhotoChiffrementService` (AES/GCM/NoPadding, tag 128 bits, IV 12 octets, clé 32 octets), constructeur injectant `@Value("${samapiece.alerte.cle-chiffrement}")`. Message d'erreur adapté ("chiffrement/déchiffrement du contact").
- [ ] `backend/src/main/java/sn/samapiece/alertes/AlerteDesinscriptionTokenGenerator.java` : méthode `String genererBrut()` (32 octets `SecureRandom`, encodage `Base64.getUrlEncoder().withoutPadding()`) et méthode `String hacher(String tokenBrut)` (SHA-256 hex, calque de `NumeroDocumentHasher.calculerHash` mais **sans sel**, puisque le jeton est déjà à haute entropie — documenter ce choix en Javadoc de la méthode).
- [ ] `backend/src/main/java/sn/samapiece/alertes/AlerteCriteresInsuffisantsException.java` : `RuntimeException` vide, calque de `sn.samapiece.recherche.CriteresInsuffisantsException`.
- [ ] `backend/src/main/java/sn/samapiece/alertes/AlerteIntrouvableOuExpireeException.java` : `RuntimeException` vide, levée pour jeton inconnu, expiré, ou déjà consommé (aucune distinction exposée entre ces trois cas, pour ne pas donner d'oracle à un attaquant).
- [ ] `backend/src/main/java/sn/samapiece/alertes/AlerteService.java` : voir Contrat technique > Signatures pour `creer(...)` et `desinscrire(...)`.

### Web

- [ ] `backend/src/main/java/sn/samapiece/alertes/web/CreerAlerteRequest.java` : record avec `estSuffisant()` (mêmes règles que `RecherchePubliqueRequest.estSuffisant()`), voir Contrat technique.
- [ ] `backend/src/main/java/sn/samapiece/alertes/web/CreerAlerteResponse.java` : record, un seul champ `message` (String), fabrique statique `CreerAlerteResponse.confirmee()` retournant un message fixe (ex. `"Alerte enregistrée. Un lien de désinscription a été envoyé par SMS."`). Aucun champ n'expose l'UUID de l'alerte ni le contact.
- [ ] `backend/src/main/java/sn/samapiece/alertes/web/AlerteController.java` : `@RestController @RequestMapping("/api/v1/alertes")`. `POST` -> `creer`, retourne `201 Created` + `CreerAlerteResponse`. `DELETE /{id}` -> `desinscrire`, param `@PathVariable("id") String tokenBrut`, retourne `204 No Content`. Javadoc de classe précisant explicitement la sémantique de `{id}` (point tranché 1).
- [ ] `backend/src/main/java/sn/samapiece/alertes/web/AlerteExceptionHandler.java` : `@RestControllerAdvice`, calque de `RecherchePubliqueExceptionHandler` (record `ErreurReponse(String code, String message)`). Handlers : `AlerteCriteresInsuffisantsException` -> `400` code `CRITERES_INSUFFISANTS` ; `IllegalArgumentException` -> `400` code `CONTACT_INVALIDE` (couvre le rejet de `NumeroTelephone.de(...)` sur contact vide/invalide) ; `AlerteIntrouvableOuExpireeException` -> `404` code `JETON_INTROUVABLE`.

### Configuration

- [ ] `backend/src/main/java/sn/samapiece/config/SecurityConfig.java` : ajouter dans `authorizeHttpRequests` (avant `.anyRequest().authenticated()`) :
  ```
  .requestMatchers(HttpMethod.POST, "/api/v1/alertes").permitAll()
  .requestMatchers(HttpMethod.DELETE, "/api/v1/alertes/*").permitAll()
  ```
  Utiliser `/*` (un seul segment, pas `**`) : le jeton ne contient jamais de `/` (alphabet base64url), donc un seul segment suffit et évite un `permitAll` inutilement large sur d'éventuels sous-chemins futurs.
- [ ] `backend/src/main/resources/application.yml` : ajouter sous `samapiece:` :
  ```yaml
  alerte:
    cle-chiffrement: ${ALERTE_CLE_CHIFFREMENT}
    token-expiration-heures: ${ALERTE_TOKEN_EXPIRATION_HEURES:8760}
    lien-desinscription-base-url: ${ALERTE_LIEN_DESINSCRIPTION_BASE_URL:https://www.samapiece.sn/desinscription-alerte}
  ```
- [ ] `backend/src/test/resources/application.yml` : ajouter les mêmes clés sous `samapiece.alerte` avec des valeurs fixes de test (ex. `cle-chiffrement: BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=` — 32 octets valides différents de la clé photo pour éviter toute ambiguïté entre les deux secrets ; `token-expiration-heures: 8760` ; `lien-desinscription-base-url: http://localhost/desinscription-alerte`).
- [ ] `docker-compose.yml` : dans `services.backend.environment`, ajouter `ALERTE_CLE_CHIFFREMENT: ${ALERTE_CLE_CHIFFREMENT}` (même schéma que `PHOTO_CLE_CHIFFREMENT`), et optionnellement `ALERTE_TOKEN_EXPIRATION_HEURES`/`ALERTE_LIEN_DESINSCRIPTION_BASE_URL` si des valeurs différentes du défaut sont nécessaires en dev (sinon, ne pas les ajouter, le défaut applicatif suffit).

### Tests

- [ ] `backend/src/test/java/sn/samapiece/alertes/web/CreerAlerteRequestTest.java` : test unitaire pur (pas de Spring) de `estSuffisant()`, calque de `RecherchePubliqueRequestTest`.
- [ ] `backend/src/test/java/sn/samapiece/alertes/AlerteContactChiffrementServiceTest.java` : test unitaire round-trip `chiffrer`/`dechiffrer`, calque de `PhotoChiffrementServiceTest`.
- [ ] `backend/src/test/java/sn/samapiece/alertes/AlerteDesinscriptionTokenGeneratorTest.java` : vérifie que `genererBrut()` produit une chaîne de 43 caractères dans l'alphabet `[A-Za-z0-9_-]` (regex), que deux appels successifs donnent des valeurs différentes, et que `hacher(x)` est déterministe (même entrée -> même hash) et différent pour deux jetons différents.
- [ ] `backend/src/test/java/sn/samapiece/alertes/FakePasserelleSms.java` : test double `implements PasserelleSms`, non annoté `@Test`, expose une liste `List<Envoi> envois` (record `Envoi(NumeroTelephone destinataire, String message)`) et une méthode `dernierEnvoi()`. Enregistré comme bean `@Primary` via une `@TestConfiguration` statique imbriquée dans le test d'intégration (voir tâche suivante) — ne pas utiliser `@MockBean` pour ce cas précis, car le test a besoin de relire le jeton brut réellement envoyé dans le corps du message SMS, ce qu'un mock Mockito classique rendrait plus verbeux à capturer.
- [ ] `backend/src/test/java/sn/samapiece/alertes/web/AlerteIntegrationTest.java` : test d'intégration `@SpringBootTest @AutoConfigureMockMvc @Testcontainers` avec `PostgreSQLContainer` (`@Container @ServiceConnection`, calque exact de `RecherchePubliqueIntegrationTest`/`PieceIntegrationTest`). Couvre les trois scénarios du critère d'acceptation 4 (voir Plan de tests). Utiliser `JdbcTemplate` pour les assertions SQL directes sur `contact_chiffre`. **Nettoyage `@BeforeEach`** : cette suite ne crée ni `Piece` ni `Poste`, donc l'ordre `piece_sequence` avant `Poste` ne s'applique pas ici — mais si une méthode de test réutilise des fixtures `Poste`/`Piece` existantes (à éviter, non nécessaire pour ce module), respecter cet ordre. Toute assertion sur le corps de réponse MockMvc doit utiliser `.getResponse().getContentAsString(StandardCharsets.UTF_8)` (jamais sans argument), conformément au point de vigilance mémorisé du projet.

## Contrat technique

### Schéma SQL (`V7__create_alerte.sql`)

```sql
CREATE TABLE alerte (
    id                       UUID PRIMARY KEY,
    type_document            VARCHAR(30) NOT NULL CHECK (type_document IN
        ('cni', 'passeport', 'permis_conduire', 'carte_electeur',
         'extrait_naissance', 'carte_grise', 'carte_consulaire', 'autre')),
    nom_titulaire            VARCHAR(255) NOT NULL,
    prenom_titulaire         VARCHAR(255),
    numero_document_hash     VARCHAR(64),
    numero_document_sel      VARCHAR(64),
    numero_document_masque   VARCHAR(64),
    date_naissance_titulaire DATE,
    canal                    VARCHAR(10) NOT NULL DEFAULT 'sms' CHECK (canal IN ('sms')),
    contact_chiffre          BYTEA,
    contact_iv               VARCHAR(64),
    active                   BOOLEAN NOT NULL DEFAULT true,
    cree_le                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    maj_le                   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_alerte_active ON alerte(active);

CREATE TABLE alerte_desinscription_token (
    id           UUID PRIMARY KEY,
    alerte_id    UUID NOT NULL REFERENCES alerte(id),
    token_hash   VARCHAR(64) NOT NULL,
    expire_le    TIMESTAMPTZ NOT NULL,
    consomme_le  TIMESTAMPTZ,
    cree_le      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_alerte_desinscription_token_token_hash ON alerte_desinscription_token(token_hash);
CREATE INDEX idx_alerte_desinscription_token_alerte_id ON alerte_desinscription_token(alerte_id);
```

Note : `contact_chiffre`/`contact_iv` sont **nullable** dès la création du schéma (pas de `NOT NULL` puis migration ultérieure) car ils sont explicitement effacés à la désinscription (décision tranchée 3) — la contrainte applicative "obligatoire à la création" est portée par `AlerteService.creer(...)`, pas par le schéma.

### Entité `Alerte` — champs JPA

| Colonne | Type Java | Nullable | Notes |
|---|---|---|---|
| `id` | `UUID` | non | `GenerationType.UUID` |
| `type_document` | `TypeDocument` (via `@Convert`, réutiliser `sn.samapiece.enregistrement.TypeDocumentConverter`) | non | |
| `nom_titulaire` | `String` | non | |
| `prenom_titulaire` | `String` | oui | |
| `numero_document_hash` | `String` | oui | |
| `numero_document_sel` | `String` | oui | |
| `numero_document_masque` | `String` | oui | |
| `date_naissance_titulaire` | `LocalDate` | oui | |
| `canal` | `String` | non | toujours `"sms"`, pas d'enum dédiée (une seule valeur possible en V7) |
| `contact_chiffre` | `byte[]` | oui | `@Lob` non nécessaire pour `BYTEA` avec le driver PostgreSQL utilisé ailleurs dans le projet (vérifier le mapping déjà utilisé pour un `byte[]`/`BYTEA` existant si présent, sinon `@Column(columnDefinition = "bytea")`) |
| `contact_iv` | `String` | oui | base64, calque `Photo.ivChiffrement` |
| `active` | `boolean` | non | |
| `cree_le` | `OffsetDateTime` | non | `insertable = false, updatable = false` |
| `maj_le` | `OffsetDateTime` | non | `insertable = false` (mis à jour explicitement par `desinscrire()`, pas de trigger DB — cohérent avec `Piece.majLe`) |

### Entité `AlerteDesinscriptionToken` — champs JPA

| Colonne | Type Java | Nullable |
|---|---|---|
| `id` | `UUID` | non |
| `alerte_id` | `UUID` | non |
| `token_hash` | `String` | non |
| `expire_le` | `OffsetDateTime` | non |
| `consomme_le` | `OffsetDateTime` | oui |
| `cree_le` | `OffsetDateTime` | non |

`alerte_id` est stocké comme `UUID` brut (pas de `@ManyToOne` vers `Alerte`) : pas besoin de charger l'entité complète pour retrouver un jeton, cohérent avec le principe "un jeton = une ligne indépendante consultable seule" mentionné dans le design (relation un-vers-plusieurs pensée pour #23).

### `CreerAlerteRequest`

```java
public record CreerAlerteRequest(
        TypeDocument typeDocument,
        String nomTitulaire,
        String prenomTitulaire,
        String numeroDocument,
        LocalDate dateNaissanceTitulaire,
        String contact) {

    public boolean estSuffisant() {
        boolean typeEtNomPresents = typeDocument != null
                && nomTitulaire != null && !nomTitulaire.isBlank();
        boolean auMoinsUnDiscriminant =
                (numeroDocument != null && !numeroDocument.isBlank())
                        || dateNaissanceTitulaire != null;
        return typeEtNomPresents && auMoinsUnDiscriminant;
    }
}
```

`contact` n'est pas validé par `estSuffisant()` (qui ne concerne que les critères de recherche) : sa validation (non vide, format) est déléguée à `NumeroTelephone.de(...)` appelé dans `AlerteService.creer(...)`.

### `AlerteService` — signatures

```java
@Service
public class AlerteService {

    public AlerteService(
            AlerteRepository alerteRepository,
            AlerteDesinscriptionTokenRepository tokenRepository,
            AlerteContactChiffrementService chiffrementService,
            AlerteDesinscriptionTokenGenerator tokenGenerator,
            NumeroDocumentHasher numeroDocumentHasher,
            PasserelleSms passerelleSms,
            @Value("${samapiece.alerte.token-expiration-heures}") long tokenExpirationHeures,
            @Value("${samapiece.alerte.lien-desinscription-base-url}") String lienBaseUrl) { ... }

    @Transactional
    public CreerAlerteResponse creer(CreerAlerteRequest requete) {
        // 1. si !requete.estSuffisant() -> throw AlerteCriteresInsuffisantsException
        // 2. NumeroTelephone destinataire = NumeroTelephone.de(requete.contact())
        //    (leve IllegalArgumentException si vide -> mappe en 400 CONTACT_INVALIDE)
        // 3. hash/sel/masque du numeroDocument si fourni (NumeroDocumentHasher.hacher)
        // 4. chiffrement du contact (AlerteContactChiffrementService.chiffrer)
        // 5. persistance de l'Alerte (canal = "sms" en dur, active = true)
        // 6. generation + hash du jeton, persistance de AlerteDesinscriptionToken
        //    (expireLe = OffsetDateTime.now().plusHours(tokenExpirationHeures))
        // 7. envoi SMS : message contenant lienBaseUrl + "?token=" + tokenBrut
        //    (passerelleSms.envoyer(destinataire, message) -- jamais logger destinataire.valeurBrute())
        // 8. return CreerAlerteResponse.confirmee()
    }

    @Transactional
    public void desinscrire(String tokenBrut) {
        // 1. si tokenBrut null/blank -> throw AlerteIntrouvableOuExpireeException
        // 2. tokenHash = tokenGenerator.hacher(tokenBrut)
        // 3. token = tokenRepository.findByTokenHash(tokenHash)
        //         .orElseThrow(AlerteIntrouvableOuExpireeException::new)
        // 4. si !token.estValide(OffsetDateTime.now()) -> throw AlerteIntrouvableOuExpireeException
        // 5. alerte = alerteRepository.findById(token.getAlerteId())
        //         .orElseThrow(AlerteIntrouvableOuExpireeException::new)
        // 6. alerte.desinscrire() ; token.consommer()
        //    (dirty checking JPA suffit dans le @Transactional, pas de save() explicite requis
        //     si les entites sont deja managees par les repositories ci-dessus)
    }
}
```

### Endpoints REST

**`POST /api/v1/alertes`** — public (`permitAll`), pas d'authentification.
- Requête : `CreerAlerteRequest` (JSON, voir ci-dessus).
- Réponse succès : `201 Created`, body `{"message": "Alerte enregistrée. Un lien de désinscription a été envoyé par SMS."}`.
- Réponse erreur critères insuffisants : `400 Bad Request`, body `{"code": "CRITERES_INSUFFISANTS", "message": "..."}`.
- Réponse erreur contact invalide : `400 Bad Request`, body `{"code": "CONTACT_INVALIDE", "message": "..."}`.

**`DELETE /api/v1/alertes/{id}`** — public (`permitAll`), pas d'authentification. `{id}` = jeton opaque base64url (voir décision tranchée 1), jamais l'UUID de l'entité.
- Réponse succès : `204 No Content`, corps vide.
- Réponse erreur (jeton inconnu, expiré, ou déjà consommé — indifférencié) : `404 Not Found`, body `{"code": "JETON_INTROUVABLE", "message": "..."}`.

### RBAC

Aucune règle `@PreAuthorize` : les deux endpoints sont volontairement non authentifiés (`permitAll` dans `SecurityConfig`), et aucun endpoint de lecture n'est créé pour un agent (garantit trivialement le critère "jamais consultable en clair par un agent").

## Plan de tests

| Critère d'acceptation | Test |
|---|---|
| `POST /api/v1/alertes` enregistre les critères et le contact chiffré, sans authentification forte | `AlerteIntegrationTest` : `POST` sans en-tête `Authorization` avec des critères suffisants et un contact valide -> `201`, puis assertion `JdbcTemplate` que la ligne `alerte` existe avec `contact_chiffre` non vide et `active = true`, et que le `FakePasserelleSms` a reçu exactement un envoi contenant le lien de désinscription. |
| `POST /api/v1/alertes` rejette des critères insuffisants | `AlerteIntegrationTest` : requête sans `typeDocument`/`nomTitulaire` ni discriminant -> `400` code `CRITERES_INSUFFISANTS` ; et `CreerAlerteRequestTest` (unitaire) pour toutes les combinaisons de `estSuffisant()`. |
| `DELETE /api/v1/alertes/{id}` désinscrit via jeton unique envoyé par SMS | `AlerteIntegrationTest` : après création, extraire le jeton brut depuis `FakePasserelleSms.dernierEnvoi().message()` (parsing du paramètre `token=` de l'URL), appeler `DELETE /api/v1/alertes/{tokenBrut}` -> `204`, puis assertion SQL que `alerte.active = false` et `alerte.contact_chiffre IS NULL` et `alerte.contact_iv IS NULL`, et que `alerte_desinscription_token.consomme_le IS NOT NULL`. |
| Rejeu du jeton après désinscription | `AlerteIntegrationTest` : rappeler `DELETE` avec le même jeton -> `404` code `JETON_INTROUVABLE`. |
| Jeton invalide/inconnu | `AlerteIntegrationTest` : `DELETE /api/v1/alertes/un-jeton-qui-nexiste-pas` -> `404`. |
| Contact jamais consultable en clair par un agent | `AlerteIntegrationTest` : après création, assertion SQL directe (`JdbcTemplate.queryForObject` sur `contact_chiffre`) que la colonne ne contient jamais la sous-chaîne du numéro en clair (comparaison sur les octets, pas sur une simple recherche texte puisque c'est du binaire chiffré) ; et absence de tout champ contact dans `CreerAlerteResponse` (vérifié par désérialisation JSON de la réponse `201` : aucune clé `contact`/`telephone`/`numero`). Complété par une revue manuelle : confirmer qu'aucune route `GET` n'existe sous `/api/v1/alertes` (recherche statique dans `AlerteController`, pas de test automatisé dédié — absence de code est la garantie). |
| Format et unicité du jeton | `AlerteDesinscriptionTokenGeneratorTest` (unitaire) : longueur/alphabet, unicité entre deux appels, déterminisme du hash. |
| Chiffrement/déchiffrement du contact | `AlerteContactChiffrementServiceTest` (unitaire) : round-trip, calque `PhotoChiffrementServiceTest`. |
| Masquage systématique dans les logs | Revue manuelle du code de `AlerteService` : vérifier qu'aucun `LOG.info/warn/error` ne reçoit `destinataire.valeurBrute()` ni le `contact` brut de la requête, seulement l'objet `NumeroTelephone` (déjà masqué par `toString()`) si un log est ajouté. Pas de test automatisé dédié (comme pour le reste du projet, cf. `MasquageNumeroLogsTest` qui couvre `NumeroTelephone` lui-même, déjà existant et suffisant). |

## Points de vigilance rappelés (mémoire projet)

- Toute assertion MockMvc dans `AlerteIntegrationTest` doit utiliser `.getResponse().getContentAsString(StandardCharsets.UTF_8)`, jamais sans argument.
- Cette suite ne crée ni `Piece` ni `Poste` : l'ordre de nettoyage `piece_sequence` avant `Poste` ne s'applique pas. S'il devient nécessaire d'ajouter une fixture `Piece`/`Poste` dans une évolution future de ce test, respecter cet ordre (`DELETE FROM piece_sequence` avant `posteRepository.deleteAll()`).
- Aucune nouvelle dépendance ajoutant un health indicator Actuator n'est introduite par ce ticket (pas de nouveau client Redis/RabbitMQ/etc. — réutilisation de `PasserelleSms` déjà existant) ; si une évolution future en ajoutait une, la désactiver dans `application.yml` **et** `test/resources/application.yml` si le composant doit être fail-open.
- Ne jamais logger `contact`/`destinataire.valeurBrute()` en clair : réutiliser systématiquement l'objet `NumeroTelephone` pour tout log impliquant le numéro (déjà en place depuis #21).

## Écarts identifiés

- Le ticket #22 mentionne un lien de désinscription "envoyé par SMS/email", mais le design (décision 4, confirmée ici) n'implémente que le canal SMS — aucune infra email n'existe dans le repo. Ceci est une réduction de périmètre assumée et documentée par l'architecte (section "Hors périmètre" de `design.md`), pas une ambiguïté de code : le codeur n'implémente que SMS, sans branche de code pour l'email.
- Absence de rate limiting/CAPTCHA sur `POST /api/v1/alertes` (endpoint public non authentifié déclenchant un envoi SMS facturé) : risque documenté par l'architecte et volontairement hors périmètre de #22 (cohérent avec `/api/v1/recherche-publique`, qui n'en a pas non plus). Aucune action attendue du codeur sur ce point pour ce ticket.
- Purge/rétention des alertes anciennes (actives ou désinscrites) : non spécifiée par le ticket, non traitée par cette spec (cohérent avec `design.md` > Hors périmètre). Aucune tâche de purge n'est créée ici.
