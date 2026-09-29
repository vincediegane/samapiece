# Design #71 - Création d'une Region et d'un Poste (API + UI admin)

_Rédigé par le bolt-architect (sans outil d'écriture) ; transcrit dans ce fichier par l'orchestrateur. Constats vérifiés : `frontend/src/features/audit/roles.ts` existe (#64 mergé), dernière migration Flyway = V12._

## Approche
Ajouter dans le module `referentiel` un service d'écriture et des endpoints `POST` protégés par `@PreAuthorize("hasRole('ADMIN_NATIONAL')")`. Le module existe déjà : `Region`, `Poste`, `RegionRepository`, `PosteRepository`, `PosteController` (GET public). Modèle : `AgentAdminController`/`AgentAdminService`.

Côté frontend, un nouveau feature `features/referentiel` avec une page à deux formulaires (Region, Poste), exposé par un onglet `referentiel` visible uniquement pour ADMIN_NATIONAL, sur le pattern `roles.ts` + `AgentShell` de #64.

Compromis : l'unicité passe par une migration Flyway V13 (index uniques) plutôt que par un simple contrôle applicatif. Prix : risque d'échec de migration si des doublons existent déjà en base.

## Fichiers/modules impactés
Backend, `backend/src/main/java/sn/samapiece/referentiel/` :
- `RegionRepository` : ajouter `existsByNomIgnoreCase`.
- `PosteRepository` : ajouter `existsByRegionIdAndNomIgnoreCase`. Ne pas toucher au `@EntityGraph` de `findAll()`.
- Créer `ReferentielAdminService` (`@Service`, `@Transactional`) : `creerRegion`, `creerPoste`.
- Créer `RegionDejaExistanteException`, `PosteDejaExistantException`, `RegionIntrouvableException` (ne pas réutiliser `iam/PosteIntrouvableException`).
- Créer `web/RegionAdminController` : `POST /api/v1/regions`, et `GET /api/v1/regions` (ADMIN_NATIONAL) pour alimenter le select de région.
- Ajouter `POST /api/v1/postes` dans `web/PosteController` existant. Le GET reste public et inchangé.
- Créer les records `CreerRegionRequest`, `CreerPosteRequest`, `RegionResponse`. Réutiliser `PosteResponse.from` pour la réponse du POST Poste.
- Créer `web/ReferentielExceptionHandler` (`@RestControllerAdvice`, format `ErreurReponse(code, message)` comme `AgentAdminExceptionHandler`).
- Créer `backend/src/main/resources/db/migration/V13__unicite_region_poste.sql`.
- `config/SecurityConfig` : rien à ajouter, vérifier seulement que `POST /api/v1/postes` retombe sur `anyRequest().authenticated()` (seul `GET /api/v1/postes` est en `permitAll`).
- Test : `backend/src/test/java/sn/samapiece/referentiel/ReferentielAdminIntegrationTest.java` (modèles : `AgentAdminIntegrationTest`, `PosteIntegrationTest`).

Frontend, `frontend/src/` :
- Créer `features/referentiel/{ReferentielPage.tsx, referentielApi.ts, types.ts, roles.ts, ReferentielPage.test.tsx}`. `roles.ts` expose `peutGererReferentiel(role)`, calqué sur `peutConsulterAudit`.
- `shared/layout/AgentShell.tsx` : `'referentiel'` dans `OngletAgent` + bouton conditionné par `agent && peutGererReferentiel(agent.role)`.
- `app/App.tsx` : `'referentiel'` dans `ONGLETS_AGENT` + rendu `{onglet === 'referentiel' && <ReferentielPage />}`.
- Compléter `shared/layout/AgentShell.test.tsx` (visibilité de l'onglet par rôle).

## Décisions clés
- **Périmètre ADMIN_REGIONAL : exclu, ADMIN_NATIONAL seul.** Créer une Region est une action nationale ; un ADMIN_REGIONAL est borné à sa région (`PerimetreRegional`) et aucun contrôle de périmètre n'existe pour la création. AGENT, CHEF_POSTE, ADMIN_REGIONAL, AUDITEUR => 403. Extension possible plus tard, hors ticket.
- **Contrat API.**
  - `POST /api/v1/regions` `{nom}` -> 201 `{id, nom}`.
  - `POST /api/v1/postes` `{regionId, nom, type, adresse, telephone, horaires}` -> 201 `PosteResponse`.
  - `type` = `POLICE|GENDARMERIE` ; valeur inconnue => 400 (pas 500).
  - `horaires` : String JSON comme dans l'entité, sans validation de schéma ; le spec-writer tranche la forme d'entrée (objet ou chaîne). JSON invalide => 400 (sinon la colonne jsonb provoque un 500).
  - `latitude`/`longitude` optionnels (bornes ±90 / ±180), jamais obligatoires.
- **Champs obligatoires** (`@NotBlank`/`@NotNull`) : `nom`, `type`, `adresse`, `telephone`, `horaires`, `regionId`. `telephone` est nullable en SQL : imposé au niveau DTO seulement (pas de migration NOT NULL, des lignes existantes peuvent être nulles). `@Size` alignés sur le schéma : nom ≤ 255, adresse ≤ 500, telephone ≤ 30 (sinon 500).
- **Unicité.** Region : `nom` unique insensible à la casse. Poste : unique sur `(region_id, lower(nom))`. V13 : `CREATE UNIQUE INDEX ... ON region (lower(nom))` et `... ON poste (region_id, lower(nom))`. Contrôle applicatif `exists…` (après trim) pour un message propre + capture de `DataIntegrityViolationException` comme filet anti-concurrence, même 409.
- **Mapping d'erreurs.** 400 : Bean Validation (aucun handler `MethodArgumentNotValid` dans le repo ; suivre le comportement par défaut de `POST /agents`, pas de handler global). 409 : `REGION_DEJA_EXISTANTE`, `POSTE_DEJA_EXISTANT`. 404 : `REGION_INTROUVABLE` si `regionId` inconnu. 401 sans jeton, 403 rôle insuffisant (contrat #60, inchangé). `AgentAdminExceptionHandler` est global et son handler `IllegalArgumentException` -> 400 capterait toute `IllegalArgumentException` du service : utiliser des exceptions dédiées.
- **Audit : oui.** `@ActionAuditee(action="REGION_CREEE", entiteCible="REGION")` et `("POSTE_CREE","POSTE")`. `AuditAspect` extrait l'id via un accesseur `id()` (UUID) sur le corps du `ResponseEntity` : les contrôleurs retournent `ResponseEntity<RegionResponse|PosteResponse>`. Échecs (409, 404) journalisés en `ECHEC`. Pas de migration audit (`action` VARCHAR libre, V10).
- **Frontend.** `GET /api/v1/regions` pour le select de région (dériver de `GET /api/v1/postes` masquerait les régions sans poste). Rafraîchir le select après création d'une région. Afficher `erreur.message` sur 409, comme `creerAgent`. Jeton dans `localStorage['samapiece.accessToken']` (voir `agentsApi.ts`). Masquage de l'onglet = confort UX, sécurité réelle = `@PreAuthorize`.

## Risques / points d'attention
- **Migration V13** : échoue si des doublons existent déjà (seeds manuels du ticket #60). Vérifier la base de démo. CI sur base vide non concernée.
- **Sérialisation de `horaires`** : point fragile (chaîne échappée ou objet). Test avec un `horaires` réaliste et test de JSON invalide => 400.
- **`Poste.majLe`** est `insertable=false` avec défaut SQL : ne pas le renseigner. `creeLe`/`majLe` peuvent être nuls après `save`, non exposés dans `PosteResponse`.
- **`PosteResponse.from`** accède à `getRegion()` (LAZY) : construire la réponse dans la transaction du service, Region chargée par `findById`, pour éviter `LazyInitializationException`.
- **Tests** : nettoyer `poste` avant `region` (FK) ; vider `piece_sequence` avant de supprimer les `Poste` si des Piece sont créées ; purger `evenement_audit` si un test compte les événements ; MockMvc : `getContentAsString(StandardCharsets.UTF_8)`.
- Ne pas casser `GET /api/v1/postes` (public, utilisé par `HomePage` et `AgentsPage`) ni `PosteIntegrationTest`.
- Offline-first : créations admin en ligne uniquement, sans file d'attente ni cache SW sur les POST.

## Hors périmètre
- Modifier, supprimer ou désactiver une Region ou un Poste.
- Droits ADMIN_REGIONAL sur le référentiel.
- Géocodage, carte, schéma strict des horaires.
- Script de seed, modification d'`AdminBootstrapRunner`.
- `telephone` NOT NULL en base.
- Refonte de `SecurityConfig` ou du contrat 401/403.
- Handler global de validation 400 unifié.
