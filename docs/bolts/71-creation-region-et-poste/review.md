# Review #71 - Création Region et Poste (ADMIN_NATIONAL)

**Verdict : APPROVE**

_Rapport produit par le bolt-reviewer (sans outil d'écriture) ; transcrit dans ce fichier par l'orchestrateur._

Réserve : les tests d'intégration Testcontainers n'ont pas pu être exécutés (pas de Docker). Ils ont été relus attentivement mais leur passage reste à confirmer en CI. Le double encodage jsonb de `horaires` n'est vérifié que par lecture de code, plus un test censé le détecter.

## Critères d'acceptation

| Critère | Statut | Preuve |
|---|---|---|
| API de création Region et Poste, restreinte à ADMIN_NATIONAL | Couvert | `RegionAdminController` (`POST`/`GET /api/v1/regions`), `PosteController.creer` (`POST /api/v1/postes`), tous en `@PreAuthorize("hasRole('ADMIN_NATIONAL')")`. `GET /postes` public inchangé (`SecurityConfig:91`). Tests : `ReferentielAdminIntegrationTest`. |
| Écran frontend, visible uniquement pour les rôles autorisés | Couvert | `ReferentielPage.tsx` ; onglet conditionné par `peutGererReferentiel(agent.role)` dans `AgentShell.tsx`. Tests `AgentShell.test.tsx`, `roles.test.ts`, `ReferentielPage.test.tsx`. |
| Validation des champs obligatoires | Couvert | `CreerPosteRequest` / `CreerRegionRequest` (`@NotNull`/`@NotBlank`/`@Size`) ; `horaires` = objet non vide (`HorairesInvalidesException`, 400). Tests paramétrés (6 champs manquants, région vide, nom trop long, latitude hors bornes, type inconnu, horaires chaîne / `{}`, corps illisible). |
| Rôle non autorisé : invisible et 403 ; 401 sans jeton | Couvert | Test paramétré 403 (rien créé), tests 401 sur les deux POST, onglet masqué testé. |

## Points d'attention vérifiés
- `@PreAuthorize` présent sur les 3 nouveaux endpoints.
- Migration V13 : SQL valide (`lower(nom)` région ; `(region_id, lower(nom))` poste), aucun conflit avec les seeds V1 ni avec les tests existants (nettoyage `region` partout).
- Double encodage jsonb : `writeValueAsString(JsonNode)` puis `String` mappée `@JdbcTypeCode(SqlTypes.JSON)` ; sur Hibernate 6.6 les `String` ne sont pas re-quotées. Test `ReferentielAdminIntegrationTest:169-171` : `jsonb_typeof(horaires) = 'object'` et `horaires->'lundi'->>'debut'`.
- Aucune `IllegalArgumentException` levée par le service.
- Handler 400 limité à `basePackages = "sn.samapiece.referentiel.web"`.
- Unicité : pré-vérification `existsBy...IgnoreCase` + `saveAndFlush` avec capture de `DataIntegrityViolationException` -> 409 ; test SQL direct des index.
- `LazyInitializationException` évitée (`PosteResponse.from` dans la transaction du service).
- Audit `REGION_CREEE` / `POSTE_CREE` (+ test `ECHEC` sur doublon de poste).
- Nettoyage des tests conforme (audit, agents, `piece_sequence`, postes, régions) ; `getContentAsString(StandardCharsets.UTF_8)` partout.

## Findings non bloquants
1. `ReferentielAdminService.creerPoste` : `horaires` JSON `null` explicite => `NullNode`, refusé par `!isObject()` (400 correct, message légèrement différent).
2. `ReferentielExceptionHandler` importe `ErreurReponse` depuis `AgentAdminExceptionHandler` (couplage inter-modules, fonctionnel).
3. `ReferentielPage.tsx` : `setErreur` sur échec de `listerRegions()` peut masquer une erreur de création affichée juste avant (cosmétique).

## Build / tests
- `mvn -q test-compile` OK ; `npx tsc -b` OK ; `npm run lint` OK ; `npx vitest run` : 21 fichiers, 203 tests OK.
- `ReferentielAdminIntegrationTest`, `PosteIntegrationTest` : non exécutés (Docker requis), à valider en CI.
