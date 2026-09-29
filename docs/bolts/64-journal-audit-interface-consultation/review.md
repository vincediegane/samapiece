# Review #64 - Interface de consultation du journal d'audit

**Verdict : APPROVE**

## Critères d'acceptation

| Critère | Statut | Preuve |
|---|---|---|
| Écran paginé, tri date décroissante, rôles AUDITEUR/ADMIN_NATIONAL | Couvert | `AuditPage.tsx` (aucun `sort` envoyé, défaut backend `horodatage DESC` conservé) ; `AuditPage.test.tsx` (pagination, bornes, "Page X sur N") ; IT backend AUDITEUR/ADMIN_NATIONAL 200 |
| Filtre action et/ou entité | Couvert | Backend `findBy*` + `EvenementAuditService.lister` (normalisation, 4 branches) ; `EvenementAuditServiceTest` (4 cas + blancs) ; IT (action, entité, AND, inconnue => vide) ; `AuditPage.test.tsx` (retour page 0, "Toutes") |
| Entrée sidebar réservée | Couvert | `AgentShell.tsx:132` `agent && peutConsulterAudit(agent.role)` ; `AgentShell.test.tsx` (présent/absent, rôle non chargé) |
| Sans rôle : pas d'entrée, accès direct refusé | Couvert (avec réserve d'écart n°2 de la spec) | Pas de router : refus dans `AuditPage` sans appel audit avant résolution du rôle, 403/401 => même message, `@PreAuthorize` intact ; IT AGENT/CHEF_POSTE 403, sans token 401 ; `App.test.tsx` |

## Points d'attention
- RBAC backend : `@PreAuthorize` et `@PageableDefault` inchangés.
- `EvenementAuditRepository` : uniquement des finders ; le test de contrat reste vert.
- Aucun `dangerouslySetInnerHTML` ; `details` rendu en texte React échappé.
- Aucun appel audit avant résolution du rôle (effet conditionné à `acces === 'autorise'`).
- Pas de cache offline : `vite.config.ts` n'a pas de `runtimeCaching`, `/api/` est en denylist du navigateFallback.
- IT : `getContentAsString(StandardCharsets.UTF_8)` utilisé ; aucune `Piece` créée dans les nouveaux tests, `piece_sequence` vidé dans le `@BeforeEach` existant. Insertion via `enregistrer` (REQUIRES_NEW, commit effectif) : correct. Non exécutable localement (Testcontainers), relu uniquement ; il devra passer en CI.
- Format du rôle : `AgentCourant.role` est comparé sans préfixe ailleurs (`CHEF_POSTE` dans `FichePieceCard`), cohérent avec `roles.ts`.

## Findings
Aucun bloquant. Remarques mineures non bloquantes : liste d'actions codée en dur (assumé par le design) ; le texte "Chargement…" est affiché aussi sous le tableau pendant un changement de page (cosmétique).

## Build/tests
- `npx vitest run` (frontend) : 18 fichiers, 160 tests OK.
- `npx tsc -b` : OK, sans erreur.
- `npm run lint` : OK, sans avertissement.
- `mvn -q test -Dtest='EvenementAuditServiceTest,EvenementAuditRepositoryContractTest'` : OK.
- `AuditEndpointIntegrationTest` : non lancé (Docker requis), relu.
