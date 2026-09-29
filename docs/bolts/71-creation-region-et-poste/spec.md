# Spec #71 - Création d'une Region et d'un Poste (API + UI admin)

## Résumé
Ajout de `POST /api/v1/regions`, `GET /api/v1/regions` et `POST /api/v1/postes` réservés à ADMIN_NATIONAL (audités, unicité garantie par index V13), et d'un onglet frontend `referentiel` (formulaires Region + Poste) visible uniquement pour ADMIN_NATIONAL.

## Tâches

Chemins backend relatifs à `backend/src/main/java/sn/samapiece/referentiel/`, frontend relatifs à `frontend/src/`.

### Backend
- [ ] 1. Créer `backend/src/main/resources/db/migration/V13__unicite_region_poste.sql` (dernière migration existante : V12). Contenu : `CREATE UNIQUE INDEX uq_region_nom_lower ON region (lower(nom));` et `CREATE UNIQUE INDEX uq_poste_region_nom_lower ON poste (region_id, lower(nom));`.
- [ ] 2. `RegionRepository.java` : ajouter `boolean existsByNomIgnoreCase(String nom);`.
- [ ] 3. `PosteRepository.java` : ajouter `boolean existsByRegionIdAndNomIgnoreCase(UUID regionId, String nom);` (ne pas toucher au `@EntityGraph` de `findAll()`).
- [ ] 4. Créer les exceptions (package `sn.samapiece.referentiel`, `RuntimeException`, sans dépendance web) : `RegionDejaExistanteException`, `PosteDejaExistantException`, `RegionIntrouvableException`, `HorairesInvalidesException` (message = motif). Ne pas réutiliser `sn.samapiece.iam.PosteIntrouvableException`.
- [ ] 5. Créer les DTO dans `web/` (records) : `CreerRegionRequest`, `CreerPosteRequest`, `RegionResponse` (voir Contrat technique). `PosteResponse` inchangé.
- [ ] 6. Créer `ReferentielAdminService.java` (`@Service`, `@Transactional`, dépendances : `RegionRepository`, `PosteRepository`, `ObjectMapper`) :
  - `RegionResponse creerRegion(CreerRegionRequest)` : `nom.trim()`, `existsByNomIgnoreCase` -> `RegionDejaExistanteException`, `saveAndFlush(new Region(nom))`, capture `DataIntegrityViolationException` -> `RegionDejaExistanteException`.
  - `List<RegionResponse> listerRegions()` (`@Transactional(readOnly = true)`, `findAll(Sort.by("nom"))`).
  - `PosteResponse creerPoste(CreerPosteRequest)` : `regionRepository.findById` sinon `RegionIntrouvableException` ; valider `horaires` (voir Contrat) ; trim de `nom`, `adresse`, `telephone` ; `existsByRegionIdAndNomIgnoreCase` -> `PosteDejaExistantException` ; `saveAndFlush(new Poste(region, nom, type, adresse, telephone, horairesJson, latitude, longitude))` avec capture `DataIntegrityViolationException` -> `PosteDejaExistantException` ; retourner `PosteResponse.from(poste)` DANS la transaction (Region LAZY déjà chargée par `findById`). Ne pas renseigner `majLe`/`creeLe`.
- [ ] 7. Créer `web/RegionAdminController.java` : `@RestController @RequestMapping("/api/v1/regions")`, `@PreAuthorize("hasRole('ADMIN_NATIONAL')")` sur chaque méthode (style `AgentAdminController`) :
  - `@PostMapping @ActionAuditee(action = "REGION_CREEE", entiteCible = "REGION") ResponseEntity<RegionResponse> creer(@Valid @RequestBody CreerRegionRequest)` -> 201.
  - `@GetMapping List<RegionResponse> lister()` -> 200 (non audité).
- [ ] 8. `web/PosteController.java` : injecter `ReferentielAdminService`, ajouter `@PostMapping @PreAuthorize("hasRole('ADMIN_NATIONAL')") @ActionAuditee(action = "POSTE_CREE", entiteCible = "POSTE") ResponseEntity<PosteResponse> creer(@Valid @RequestBody CreerPosteRequest)` -> 201. Le GET reste public, inchangé (garder le constructeur compatible : `PosteRepository` + service). Le type de retour DOIT rester `ResponseEntity<PosteResponse>` (l'aspect d'audit lit `id()` sur le corps).
- [ ] 9. Créer `web/ReferentielExceptionHandler.java` : `@RestControllerAdvice(basePackages = "sn.samapiece.referentiel.web")` (limité pour ne pas modifier le comportement des autres modules), record de réponse `ErreurReponse(String code, String message)` (soit réutiliser `AgentAdminExceptionHandler.ErreurReponse`, soit un record local ; préférer le record existant pour un JSON identique). Mappings dans Contrat technique.
- [ ] 10. `config/SecurityConfig.java` : aucune modification. Vérifier seulement que `POST /api/v1/postes` et `/api/v1/regions` retombent sur `anyRequest().authenticated()` (seul `GET /api/v1/postes` est `permitAll`). `/error` est déjà `permitAll` (donc un 400 par défaut n'est pas réécrit en 401).

### Frontend
- [ ] 11. Créer `features/referentiel/roles.ts` : `peutGererReferentiel(role)` vrai uniquement pour `'ADMIN_NATIONAL'` (copie de `features/audit/roles.ts`, commentaire "confort UX, la sécurité réelle est le @PreAuthorize").
- [ ] 12. Créer `features/referentiel/types.ts` : `Region {id,nom}`, `CreerRegionPayload {nom}`, `TypePoste = 'POLICE'|'GENDARMERIE'`, `CreerPostePayload`, `PosteCree` (forme de `PosteResponse`).
- [ ] 13. Créer `features/referentiel/referentielApi.ts` (modèle `features/agents/agentsApi.ts`, jeton `localStorage['samapiece.accessToken']`, en-tête `Authorization: Bearer`) : `listerRegions()` (GET), `creerRegion(payload)`, `creerPoste(payload)`. Sur `!reponse.ok` : `throw new Error(erreur?.message ?? \`Erreur ${status}\`)`.
- [ ] 14. Créer `features/referentiel/ReferentielPage.tsx` : deux formulaires (Region : `nom` ; Poste : select région, nom, type, adresse, téléphone, horaires, latitude/longitude optionnelles). Voir Contrat (UI). Charge `listerRegions()` au montage ; après création de région, rafraîchit la liste et présélectionne la nouvelle région ; messages de succès/erreur (`erreur.message` affiché).
- [ ] 15. `shared/layout/AgentShell.tsx` : ajouter `'referentiel'` à `OngletAgent`, importer `peutGererReferentiel`, ajouter après le bouton audit `{agent && peutGererReferentiel(agent.role) && (<button ... onClick={() => onNaviguer('referentiel')}>...Référentiel</button>)}` (libellé exact "Référentiel", icône existante de `../icons`, p.ex. `IconShield` ou `IconChart` ; ne pas créer d'icône).
- [ ] 16. `app/App.tsx` : ajouter `'referentiel'` à `ONGLETS_AGENT`, importer `ReferentielPage`, ajouter `{onglet === 'referentiel' && <ReferentielPage />}`.

### Tests
- [ ] 17. Créer `backend/src/test/java/sn/samapiece/referentiel/ReferentielAdminIntegrationTest.java` (modèles : `AgentAdminIntegrationTest`, `PosteIntegrationTest`) - voir Plan de tests.
- [ ] 18. Créer `features/referentiel/ReferentielPage.test.tsx` (Vitest + Testing Library, `vi.mock('./referentielApi')`).
- [ ] 19. Compléter `shared/layout/AgentShell.test.tsx` : onglet "Référentiel" visible pour ADMIN_NATIONAL (clic -> `onNaviguer('referentiel')`), masqué pour AGENT, CHEF_POSTE, ADMIN_REGIONAL, AUDITEUR et si le rôle n'est pas chargé.
- [ ] 20. Vérifier que `PosteIntegrationTest` et les tests existants d'`AgentShell` passent inchangés (GET public non régressé).

## Contrat technique

### RBAC
`@PreAuthorize("hasRole('ADMIN_NATIONAL')")` sur les 3 endpoints. AGENT, CHEF_POSTE, ADMIN_REGIONAL, AUDITEUR -> 403 ; sans jeton -> 401 (contrat #60 inchangé, aucun code à ajouter).

### Migration V13
Deux index uniques (voir tâche 1). Échoue si doublons existants (vérifier la base de démo avant déploiement).

### Endpoints

| Méthode / chemin | Rôle | Corps | Succès |
|---|---|---|---|
| `POST /api/v1/regions` | ADMIN_NATIONAL | `{"nom":"Dakar"}` | 201 `{"id":"<uuid>","nom":"Dakar"}` |
| `GET /api/v1/regions` | ADMIN_NATIONAL | - | 200 `[{"id","nom"}, ...]` trié par nom |
| `POST /api/v1/postes` | ADMIN_NATIONAL | voir ci-dessous | 201 `PosteResponse` |
| `GET /api/v1/postes` | public | - | inchangé |

### DTO

```java
public record CreerRegionRequest(@NotBlank @Size(max = 255) String nom) {}

public record RegionResponse(UUID id, String nom) {   // accesseur id() requis par AuditAspect
    public static RegionResponse from(Region r) { ... }
}

public record CreerPosteRequest(
    @NotNull UUID regionId,
    @NotBlank @Size(max = 255) String nom,
    @NotNull TypePoste type,                 // enum sn.samapiece.referentiel.TypePoste
    @NotBlank @Size(max = 500) String adresse,
    @NotBlank @Size(max = 30) String telephone,
    @NotNull JsonNode horaires,              // com.fasterxml.jackson.databind.JsonNode
    @DecimalMin("-90") @DecimalMax("90") Double latitude,     // optionnel
    @DecimalMin("-180") @DecimalMax("180") Double longitude   // optionnel
) {}
```

Requête Poste exemple :
```json
{"regionId":"...","nom":"Commissariat de Rufisque","type":"POLICE","adresse":"Route de Bargny","telephone":"+221338360000",
 "horaires":{"lundi":{"ouvert":true,"debut":"08:00","fin":"18:00"}},"latitude":14.72,"longitude":-17.27}
```

### Décisions tranchées

1. **Forme de `horaires` en entrée : OBJET JSON** (pas de chaîne échappée). Le DTO reçoit un `JsonNode` ; le service applique `horaires.isObject() && horaires.size() > 0`, sinon `HorairesInvalidesException("Les horaires doivent etre un objet JSON non vide.")` -> 400. La colonne reçoit `objectMapper.writeValueAsString(horaires)` (String JSON canonique, compatible avec le champ `String horaires` de l'entité). Une chaîne JSON (`"horaires":"{...}"`), un tableau, un nombre, `{}` ou `null`/absent (`@NotNull`) -> 400. Aucune validation de schéma des jours. Le champ `horaires` de `PosteResponse` reste une `String` (inchangé, cohérent avec `GET /postes`).
2. **JSON de requête invalide (syntaxe cassée) -> 400** : Spring lève `HttpMessageNotReadableException` ; géré par le handler (voir mapping). Jamais 500 (le JSON est parsé avant d'atteindre la colonne jsonb).
3. **`type` inconnu -> 400** : le DTO utilise l'enum `TypePoste` directement ; Jackson (config Boot par défaut, aucun `application*.yml` ne configure `jackson.*`) refuse toute valeur hors `POLICE`/`GENDARMERIE` (sensible à la casse : `police` en minuscules est aussi rejeté) avec `InvalidFormatException` enveloppée dans `HttpMessageNotReadableException` -> 400. `TypePosteConverter` est un convertisseur JPA uniquement (majuscules <-> minuscules SQL), jamais utilisé par Jackson : ne pas le toucher. `type` absent -> `@NotNull` -> 400.
4. **Validation Bean Validation -> 400**. Constat vérifié : aucun handler `MethodArgumentNotValidException` dans le repo (`grep` : seuls `AgentSelfController`, `PieceController`, `AuthController`, `AgentAdminController` utilisent `@Valid`) ; le comportement par défaut donne un 400 sans `ErreurReponse` (corps Boot `/error` standard, sans détail des champs ; `/error` est `permitAll` donc pas de réécriture en 401). Pour permettre au frontend d'afficher un message, ajouter dans `ReferentielExceptionHandler` (limité au package `referentiel.web`) des handlers dédiés pour `MethodArgumentNotValidException` et `HttpMessageNotReadableException`. Aucun handler global : les autres endpoints gardent leur comportement.

### Format des erreurs (`ErreurReponse(code, message)`)

| Cas | Statut | `code` | `message` |
|---|---|---|---|
| Bean Validation (`MethodArgumentNotValidException`) | 400 | `REQUETE_INVALIDE` | `"<champ>: <message>"` des erreurs, concaténées par `"; "` (ordre trié par champ pour stabilité) |
| Corps illisible / JSON invalide / `type` inconnu (`HttpMessageNotReadableException`) | 400 | `REQUETE_INVALIDE` | `"Corps de requete invalide."` (ne pas exposer le message Jackson) |
| `HorairesInvalidesException` | 400 | `REQUETE_INVALIDE` | message de l'exception |
| `RegionDejaExistanteException` | 409 | `REGION_DEJA_EXISTANTE` | `"Cette region existe deja."` |
| `PosteDejaExistantException` | 409 | `POSTE_DEJA_EXISTANT` | `"Un poste de ce nom existe deja dans cette region."` |
| `RegionIntrouvableException` | 404 | `REGION_INTROUVABLE` | `"Region introuvable."` |
| rôle insuffisant | 403 | (géré par Spring Security, inchangé) | - |
| sans jeton | 401 | (idem) | - |

Attention : `AgentAdminExceptionHandler` (global) mappe toute `IllegalArgumentException` -> 400 `REQUETE_INVALIDE` ; le service ne doit lever aucune `IllegalArgumentException` (exceptions dédiées uniquement).

### Audit
`@ActionAuditee` sur les méthodes de contrôleur (tâches 7-8). Succès : `SUCCES` avec `entiteCibleId` = `id()` du corps. Échecs après le corps valide (409, 404, horaires invalides) : événement `ECHEC` avec `entiteCibleId` null. Les 400 de Bean Validation / JSON illisible surviennent avant l'invocation de la méthode : non audités (acceptable). Aucune migration audit (`action` est un VARCHAR libre, V10).

### UI (`ReferentielPage`)
- Formulaire Region : champ `nom` (requis). Bouton "Créer la région".
- Formulaire Poste : `<select>` région (requis, options = `GET /regions`), `nom`, `type` (select POLICE/GENDARMERIE, libellés "Police"/"Gendarmerie"), `adresse`, `telephone`, `horaires` (requis ; saisie : une `<textarea>` contenant du JSON objet, pré-remplie d'un exemple ; `JSON.parse` côté client, erreur "Horaires : JSON invalide" et pas d'appel réseau si invalide ou non-objet ; envoi de l'objet parsé), `latitude`/`longitude` optionnels. Champs requis marqués `required`. Bouton "Créer le poste".
- Erreur API : afficher le `message` du 409/400/404 (comme `creerAgent`). Pas de file hors-ligne, pas de cache SW sur les POST.
- Libellé onglet : "Référentiel".

## Plan de tests

Classe `ReferentielAdminIntegrationTest` : `@SpringBootTest @AutoConfigureMockMvc @Testcontainers`, container `postgres:16-alpine` (comme `PosteIntegrationTest`). `@BeforeEach` : dans cet ordre `jdbcTemplate.update("DELETE FROM evenement_audit")` (si des agents sont supprimés ensuite, FK acteur éventuelle ; nécessaire aussi pour compter les événements), `agentRepository.deleteAll()`, `piece_sequence` vidée (`DELETE FROM piece_sequence`) avant de supprimer les `Poste` si une Piece/séquence est créée, puis `posteRepository.deleteAll()` AVANT `regionRepository.deleteAll()`. Toutes les lectures de réponse : `getContentAsString(StandardCharsets.UTF_8)`. Connexion : helper `login` copié d'`AgentAdminIntegrationTest` (un agent par rôle sur un poste de départ, `creerEtLoginToken`).

| Critère d'acceptation | Test |
|---|---|
| API de création Region + Poste, ADMIN_NATIONAL | `creerRegion_adminNational_shouldReturn201AvecIdEtNom` ; `creerPoste_adminNational_shouldReturn201EtPersister` (vérifie `region.id/nom`, `type`, et relecture SQL `SELECT jsonb_typeof(horaires) FROM poste` = `object` et `horaires->'lundi'->>'debut'` = `08:00` pour garantir l'absence de double-encodage jsonb) ; `listerRegions_adminNational_shouldReturn200Trie` ; `getPostes_apresCreation_shouldListerLePosteSansAuthentification` (GET public toujours OK) |
| Validation champs obligatoires | `creerRegion_nomVide_shouldReturn400` ; `creerPoste_champManquant_shouldReturn400` (`@ParameterizedTest`/boucle sur `nom`, `type`, `adresse`, `telephone`, `horaires`, `regionId` absents ou blancs, vérifie `code=REQUETE_INVALIDE`) ; `creerPoste_nomTropLong_shouldReturn400` (256 car.) ; `creerPoste_latitudeHorsBornes_shouldReturn400` |
| `type` inconnu | `creerPoste_typeInconnu_shouldReturn400` (`"type":"MILITAIRE"` et `"police"` minuscule ; jamais 500) |
| `horaires` invalides | `creerPoste_horairesChaine_shouldReturn400`, `creerPoste_horairesVideObjet_shouldReturn400`, `creerPoste_corpsJsonInvalide_shouldReturn400` (accolade manquante, `code=REQUETE_INVALIDE`, `message="Corps de requete invalide."`) |
| Unicité / région inconnue | `creerRegion_doublonInsensibleCasse_shouldReturn409` (`Dakar` puis `dAkAr` avec espaces, code `REGION_DEJA_EXISTANTE`) ; `creerPoste_memeNomMemeRegion_shouldReturn409` ; `creerPoste_memeNomAutreRegion_shouldReturn201` ; `creerPoste_regionInconnue_shouldReturn404` (`REGION_INTROUVABLE`) ; `index_unique_shouldRejeterDoublonSQL` (insertion JDBC directe -> `DataIntegrityViolationException`, valide V13) |
| Rôle non autorisé refusé (403, contrat #60) | `@ParameterizedTest` sur AGENT, CHEF_POSTE, ADMIN_REGIONAL, AUDITEUR : `POST /regions`, `GET /regions`, `POST /postes` -> 403 ; aucune ligne créée (comptage `regionRepository.count()` / `posteRepository.count()` inchangé) |
| Non authentifié | `postRegionsSansJeton_shouldReturn401`, `postPostesSansJeton_shouldReturn401` (prouve que `POST /postes` n'est pas couvert par le `permitAll` du GET) |
| Audit | `creerRegion_shouldAuditerREGION_CREEE` et `creerPoste_shouldAuditerPOSTE_CREE` (événement `SUCCES`, `entiteCibleId` = id créé, `entiteCible` REGION/POSTE) ; `creerPoste_doublon_shouldAuditerEchec` (résultat `ECHEC`) |
| Écran frontend, visible seulement pour rôles autorisés | `AgentShell.test.tsx` : `it.each(['ADMIN_NATIONAL'])` affiche "Référentiel" + `onNaviguer('referentiel')` ; `it.each(['AGENT','CHEF_POSTE','ADMIN_REGIONAL','AUDITEUR'])` masqué ; masqué si `recupererAgentCourant` rejette |
| Formulaires frontend | `ReferentielPage.test.tsx` : charge et affiche les régions dans le select ; création de région appelle `creerRegion({nom})` puis rafraîchit la liste ; création de poste envoie le payload avec `horaires` objet parsé ; JSON d'horaires invalide -> message d'erreur et `creerPoste` non appelé ; erreur API 409 (`Error('Cette region existe deja.')`) affichée ; champs requis vides -> pas d'appel API |
| Rôle non autorisé, UI (`roles.ts`) | test unitaire optionnel `roles.test.ts` : `peutGererReferentiel` vrai pour ADMIN_NATIONAL, faux pour les autres rôles, `null`, `undefined` |
| Non-régression | `PosteIntegrationTest` inchangé et vert ; suites backend/frontend complètes vertes |
| Vérification manuelle | Sur une base de démo avec données existantes, appliquer V13 (échec attendu si doublons) ; se connecter en ADMIN_NATIONAL, créer une région puis un poste depuis l'onglet Référentiel et vérifier son apparition sur la page d'accueil |

## Écarts identifiés

1. **Handlers 400 dédiés vs "aucun handler"** : le design dit de suivre le comportement par défaut sans handler. Constaté : le défaut renvoie un 400 au corps Boot `/error` sans `code`/`message` métier, donc l'UI ne peut pas afficher la cause. Cette spec ajoute, dans `ReferentielExceptionHandler` limité à `sn.samapiece.referentiel.web`, un handler pour `MethodArgumentNotValidException` et `HttpMessageNotReadableException`. Pas de handler global (respecte le hors-périmètre). À valider ; en cas de refus, retirer ces deux handlers et adapter les assertions de test (statut 400 seul) ; la validation client des formulaires reste.
2. **`horaires` : le design laissait le choix** ; tranché : objet JSON en entrée (`JsonNode`), sérialisé en chaîne pour l'entité. `{}` refusé alors que le défaut SQL est `'{}'` (le ticket exige des horaires renseignés).
3. **Unicité et `IgnoreCase`** : `existsBy...IgnoreCase` génère `upper(...)` alors que l'index utilise `lower(...)` ; l'écart n'est visible que pour des caractères Unicode particuliers, le filet `DataIntegrityViolationException` (409) couvre ce cas. Ne pas remplacer par une requête native.
4. **Critère "périmètre ADMIN_REGIONAL à confirmer"** : tranché par le design (exclu, ADMIN_NATIONAL seul) ; l'ADMIN_REGIONAL reçoit 403 et ne voit pas l'onglet. À documenter dans la PR.
5. **Double encodage jsonb** (`String` + `@JdbcTypeCode(SqlTypes.JSON)`) : aucun test existant n'écrit un `Poste` via une API puis n'inspecte la colonne (`PosteIntegrationTest` relit via Hibernate seulement). Le test `creerPoste_adminNational_shouldReturn201EtPersister` inclut une vérification SQL directe `jsonb_typeof(horaires) = 'object'` ; si elle échoue (chaîne JSON stockée), corriger le mapping avant de continuer.
