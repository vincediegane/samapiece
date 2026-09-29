# Review #72 - Vue multi-poste Admin régional / Admin national

**Verdict : APPROVE**

_Rapport produit par le bolt-reviewer (sans outil d'écriture) ; transcrit dans ce fichier par l'orchestrateur._

## Critères d'acceptation

| Critère | Statut | Preuve |
|---|---|---|
| Endpoint de stats agrégées par région (ADMIN_REGIONAL) et nationale (ADMIN_NATIONAL) | Couvert | `StatistiquesController` : `GET /api/v1/statistiques/regionale` (`@PreAuthorize("hasRole('ADMIN_REGIONAL')")`) et `GET /nationale` (`hasRole('ADMIN_NATIONAL')`). Route absente de `SecurityConfig` => `anyRequest().authenticated()` => 401 sans jeton. `StatistiquesConsolideesIntegrationTest` : 403 pour Agent, Chef de poste, Auditeur et l'autre admin ; 401 sans jeton. |
| Vue frontend dédiée, visible uniquement pour ces deux rôles | Couvert | Onglet conditionné par `peutVoirVueMultiPoste(agent.role)` dans `AgentShell.tsx` ; `VueMultiPostePage` n'appelle l'API que pour ces rôles. Tests `roles.test.ts`, `AgentShell.test.tsx`, `VueMultiPostePage.test.tsx`. Garde client = confort UX, contrôle réel = `@PreAuthorize`. |
| Tableau de bord poste unique inchangé (Agent, Chef de poste) | Couvert | Diff vide sur `StatistiquesPosteService`, `DashboardPage` et leurs tests ; 226 tests frontend verts. |
| Cohérence des seuils/alertes de #26 à l'échelle agrégée | Couvert | Mêmes statuts `('disponible','reclamee')` que `agregerStockParPoste` / `compterDepassantSeuil`, même seuil strict `(CURRENT_DATE - date_depot) > :seuilJours`, même `StatistiquesProperties`. Test `consolide_shouldEtreCoherentAvecEndpointPoste` (consolidé vs endpoint poste). |

## Points critiques vérifiés
- Région déduite côté serveur (`appelant.getPoste().getRegion()`), aucun id client ; `AccesRefuseException` si agent introuvable/inactif.
- SQL natif : syntaxe PostgreSQL valide ; `LEFT JOIN piece` avec filtre de statut dans le `ON` (postes sans pièce conservés) ; `COUNT(p.id) FILTER (WHERE …)` et `GROUP BY` corrects ; `ORDER BY` déterministe (dépassants desc, nom, id) ; alias quotés alignés sur les getters de `StockParPosteAgrege` ; `date_depot` DATE donc `CURRENT_DATE - date_depot` entier ; conversions compatibles (`SUM` bigint -> `Long`, `AVG` numeric -> `Double`, `MAX` integer -> `Long`, `COUNT` -> `long`, `null` -> wrappers).
- Moyenne des totaux = Σ(jours) / Σ(pièces), `null` si aucune pièce (pas une moyenne de moyennes).
- `@Transactional(readOnly = true)` sur les deux méthodes du service (lazy `getPoste().getRegion()` sûr).
- Tests : `DELETE FROM piece_sequence` avant `posteRepository.deleteAll()` ; `getContentAsString(StandardCharsets.UTF_8)` partout.
- Aucune donnée citoyen exposée (compteurs, noms de poste/région).

## Findings non bloquants
1. `StatistiquesConsolideesService.consulterNationale` appelle `appelantCourant()` sans utiliser le résultat (volontaire : vérifie que l'agent est actif).
2. `VueMultiPostePage` fait deux appels réseau en série (`recupererAgentCourant` puis statistiques) : acceptable.
3. Tests d'intégration Testcontainers non exécutés (pas de Docker) : SQL validé par lecture uniquement ; la CI doit confirmer.

## Build / tests
- `mvn -q test-compile` OK ; `mvn -q test -Dtest=StatistiquesConsolideesServiceTest` OK.
- `npx vitest run` : 23 fichiers, 226 tests OK ; `npx tsc -b` OK ; `npm run lint` OK.
- `StatistiquesConsolideesIntegrationTest`, `StatistiquesPosteIntegrationTest` : non exécutés (Docker requis), à valider en CI.
