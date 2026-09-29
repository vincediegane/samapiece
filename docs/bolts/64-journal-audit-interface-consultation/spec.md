# Spec #64 - Interface de consultation du journal d'audit

## Résumé

Ajout d'un écran frontend « Journal d'audit » (liste paginée, filtres action/entité, entrée sidebar et accès réservés aux rôles AUDITEUR et ADMIN_NATIONAL) consommant `GET /api/v1/audit/evenements`, avec deux filtres optionnels ajoutés côté backend.

## Tâches

### Backend (racine : `backend/src/main/java/sn/samapiece/audit/`)

- [ ] 1. `EvenementAuditRepository.java` : ajouter `Page<EvenementAudit> findByAction(String action, Pageable p)`, `findByEntiteCible(String entiteCible, Pageable p)`, `findByActionAndEntiteCible(String action, String entiteCible, Pageable p)`. Aucune méthode delete/update (le contrat `EvenementAuditRepositoryContractTest` doit rester vert).
- [ ] 2. `EvenementAuditService.java` : remplacer `lister(Pageable)` par `lister(String action, String entiteCible, Pageable pageable)`. Normaliser chaque filtre (`null` ou `isBlank()` => absent, sinon `trim()`), puis brancher : aucun => `findAll`, action seule, entité seule, les deux. Conserver `@Transactional(readOnly = true)` et le mapping `EvenementAuditResponse::of`. Mettre à jour les appelants (`AuditController`, `EvenementAuditServiceTest`).
- [ ] 3. `web/AuditController.java` : ajouter `@RequestParam(required = false) String action` et `@RequestParam(required = false) String entiteCible`, les passer au service. Ne pas toucher `@PreAuthorize` ni `@PageableDefault(size=20, sort="horodatage", DESC)`.
- [ ] 4. `EvenementAuditServiceTest.java` (existant) : adapter à la nouvelle signature ; ajouter des cas Mockito : sans filtre => `findAll`, action seule, entité seule, les deux, valeurs blanches traitées comme absentes.
- [ ] 5. `AuditEndpointIntegrationTest.java` (existant) : ajouter les cas listés dans le Plan de tests. Utiliser `getContentAsString(StandardCharsets.UTF_8)` ; si des `Piece` sont créées, vider `piece_sequence` avant de supprimer les `Poste`.

### Frontend (racine : `frontend/src/`)

- [ ] 6. `features/audit/types.ts` : `EvenementAudit`, `PageEvenementsAudit` (`content`, `number`, `totalPages`, `totalElements`, `size`), `FiltresAudit { action?: string; entiteCible?: string }`.
- [ ] 7. `features/audit/roles.ts` (ou export dans `auditApi.ts`) : `peutConsulterAudit(role: string | null | undefined): boolean` => vrai ssi `role` ∈ {`AUDITEUR`, `ADMIN_NATIONAL`}. Ne pas dupliquer cette règle ailleurs. Commentaire : confort UX, le RBAC réel est `@PreAuthorize` côté backend.
- [ ] 8. `features/audit/auditApi.ts` : `listerEvenementsAudit(page, filtres, taille = 20)` => `fetch('/api/v1/audit/evenements?page=..&size=..[&action=..][&entiteCible=..]')` avec `Authorization: Bearer <localStorage samapiece.accessToken>` (même convention que `features/agents/agentsApi.ts`). Ne pas envoyer de `sort`. Erreur typée exposant le statut HTTP (401/403 distinguables des autres erreurs). Pas de log du contenu.
- [ ] 9. `features/audit/AuditPage.tsx` :
  - au montage, `recupererAgentCourant()` ; tant que non résolu : état « Chargement… », aucun appel audit ;
  - si `!peutConsulterAudit(agent.role)` (ou échec de `recupererAgentCourant`) : afficher `Accès réservé aux auditeurs et administrateurs nationaux` (classe `alert-error`), aucun appel audit ;
  - sinon : selects « Action » (Toutes + PIECE_CREEE, PIECE_CONSULTEE, PIECE_RETIREE, PIECE_SIGNALEE, PIECE_RECU_GENERE, PIECE_DEBLOQUEE ; constante commentée « valeurs alignées sur ActionAuditee / actions émises ») et « Entité » (Toutes + PIECE) ; changement de filtre => page 0 ;
  - tableau : horodatage (`toLocaleString('fr-FR')`), action, entité (+ 8 premiers caractères de `entiteCibleId`), acteur (`typeActeur` + `acteurId`), IP, détails (texte React échappé, jamais `dangerouslySetInnerHTML`) ;
  - pagination : boutons « Précédent » / « Suivant » (désactivés aux bornes), texte « Page X sur N » (X = number+1) et `totalElements` ;
  - états : chargement, vide (« Aucun événement d'audit »), erreur générique (`alert-error`), 401/403 => même message d'accès réservé.
- [ ] 10. `shared/layout/AgentShell.tsx` : `OngletAgent` += `'audit'` ; après le bouton « Agents », rendre le bouton « Journal d'audit » (classes `sidebar-link` / `sidebar-link-active`, icône existante de `../icons`, ou en ajouter une dans `shared/icons` si aucune ne convient) uniquement si `agent && peutConsulterAudit(agent.role)` (masqué par défaut tant que `agent` est null).
- [ ] 11. `app/App.tsx` : ajouter `'audit'` à `ONGLETS_AGENT`, rendre `{onglet === 'audit' && <AuditPage />}`.
- [ ] 12. `features/audit/AuditPage.test.tsx` (nouveau), `shared/layout/AgentShell.test.tsx` et `app/App.test.tsx` (existants) : voir Plan de tests.
- [ ] 13. Vérifier que la config PWA/service worker (vite config, `shared/offline`) ne met pas `/api/v1/audit` en cache ; corriger si c'est le cas.

## Contrat technique

Endpoint (inchangé hormis les paramètres) :

```
GET /api/v1/audit/evenements?page=0&size=20[&action=PIECE_CREEE][&entiteCible=PIECE]
Authorization: Bearer <jwt>
@PreAuthorize("hasAnyRole('AUDITEUR','ADMIN_NATIONAL')")
200 -> Page<EvenementAuditResponse> { content:[{id, acteurId, typeActeur, action, entiteCible,
       entiteCibleId, details, adresseIp, horodatage}], number, size, totalPages, totalElements, ... }
401 sans token ; 403 rôle insuffisant
```

- Filtres : égalité stricte sensible à la casse, combinés en AND, optionnels ; valeur inconnue => page vide (200), jamais 400. Tri par défaut `horodatage DESC` conservé.
- Aucune migration Flyway, aucun nouvel endpoint, `EvenementAuditResponse` inchangé.
- Consulter le journal n'est pas audité.
- Frontend : `peutConsulterAudit(role)` unique source de vérité pour sidebar et page.

## Plan de tests

| Critère d'acceptation | Test |
|---|---|
| Écran listant les événements, paginé, tri date décroissante, rôles AUDITEUR/ADMIN_NATIONAL | `AuditPage.test.tsx` : rôle AUDITEUR (puis ADMIN_NATIONAL) => lignes rendues, appel `listerEvenementsAudit(0, {})` ; Suivant/Précédent => appel page 1/0, boutons désactivés aux bornes, « Page X sur N ». `AuditEndpointIntegrationTest` : sans filtre, ordre `horodatage` décroissant et comportement inchangé (AUDITEUR et ADMIN_NATIONAL => 200) |
| Filtre par type d'action et/ou entité | Intégration : `?action=PIECE_CREEE` ne retourne que cette action ; `?entiteCible=PIECE` ; combinaison des deux (AND) ; valeur inconnue => 200 page vide. Unitaire `EvenementAuditServiceTest` : branchement des 4 cas. `AuditPage.test.tsx` : choisir une action => appel avec `{action}` et page 0 (même après être allé en page 1) ; idem entité ; « Toutes » => filtre retiré |
| Entrée sidebar dédiée visible uniquement pour ces rôles | `AgentShell.test.tsx` : « Journal d'audit » présent pour AUDITEUR et ADMIN_NATIONAL, absent pour AGENT, CHEF_POSTE et tant que le rôle n'est pas chargé/en échec ; clic => `onNaviguer('audit')` |
| Agent/chef de poste : pas d'entrée, accès direct refusé proprement | Intégration : token AGENT => 403, token CHEF_POSTE => 403, sans token => 401. `AuditPage.test.tsx` : rôle AGENT/CHEF_POSTE => message « Accès réservé… », `listerEvenementsAudit` non appelé ; API répondant 403 => même message. `App.test.tsx` : onglet `audit` rendu dans `AgentShell` ; rôle AGENT => message de refus, pas d'appel audit |
| États vide / chargement / erreur (risque design) | `AuditPage.test.tsx` : liste vide, chargement, erreur réseau (`alert-error`) |
| Contrat repository sans delete/update | `EvenementAuditRepositoryContractTest` existant, doit rester vert |
| Pas de cache offline des données d'audit | Manuel : inspecter la config SW/PWA (tâche 13) |

## Écarts identifiés

1. Format du rôle : le design suppose `AgentCourant.role` égal à `AUDITEUR` / `ADMIN_NATIONAL` (sans préfixe `ROLE_`). À confirmer dans `features/dashboard/dashboardApi.ts` / le endpoint `/agents/me` avant de coder `peutConsulterAudit`. Si le préfixe est présent, adapter le helper (un seul endroit).
2. Critère « accès direct à l'URL refusé » : l'application n'a pas de router ; l'équivalent retenu est l'onglet `audit` (state) avec refus dans `AuditPage`, plus le 403 backend. À valider par le PO comme couverture suffisante du critère.
3. Le design mentionne « `backend/.../audit/web/` » pour le service et le repository ; ils sont en réalité dans `sn/samapiece/audit/` (seul le contrôleur est dans `web/`). Chemins ci-dessus corrigés.
4. Valeurs d'action codées en dur côté frontend : risque de dérive si une nouvelle action est émise ; assumé par le design (commentaire dans le code), pas d'endpoint de liste des actions (hors périmètre).
