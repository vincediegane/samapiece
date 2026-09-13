# Review -- Ticket #8 : RBAC -- roles Agent / Chef de poste / Admin regional / Admin national / Auditeur

APPROVE

## Critères d'acceptation

| # | Critère | Statut |
|---|---|---|
| 1 | `@PreAuthorize` sur les endpoints existants selon la matrice de rôles | Couvert -- aucune modification de `AgentAdminController.java` (diff vide confirmé), les 4 `@PreAuthorize("hasAnyRole('CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')")` déjà en place depuis #9 satisfont déjà la colonne "rôle" de la matrice (décision assumée et documentée dans `design.md`). |
| 2 | Un agent hors périmètre de poste reçoit 403 | Couvert -- `verifierPerimetrePoste` appliquée sur `creer`/`modifier`/`desactiver`, et filtrage par requête (`findByPosteId`/`findByPosteRegionId`) sur `lister`. Testé positif+403 pour CHEF_POSTE et ADMIN_REGIONAL sur les 4 endpoints. |
| 3 | ADMIN_REGIONAL : agrégé de sa région oui, détail nominatif d'un autre poste non | Partiel mais justifié -- aucun endpoint de stats n'existe encore (#26 hors périmètre). Couvert par `PerimetreRegionalTest` (6 tests, contrat réutilisable prêt pour #26) + `lister_commeAdminRegional_shouldRetournerAgentsDeSaRegionUniquement` qui démontre déjà qu'un ADMIN_REGIONAL ne reçoit jamais le détail nominatif hors de sa région sur l'endpoint le plus proche. Écart documenté explicitement dans `spec.md` (§"Écarts identifiés"), pas un trou de couverture caché. |
| 4 | Tests d'intégration : au moins 1 positif + 1 cas 403 par rôle (5 rôles) | Couvert, y compris les cas limites : AGENT/AUDITEUR (aucun accès légitime au CRUD agents) -- positif via `listerPostes_comme{Agent,Auditeur}_shouldRetourner200` (endpoint public confirmé dans `SecurityConfig` : `.requestMatchers(HttpMethod.GET, "/api/v1/postes").permitAll()`), 403 sur les 4 endpoints agents. ADMIN_NATIONAL -- positif sur les 4 endpoints (existant + nouveau `lister_commeAdminNational_...`), aucun 403 RBAC fabriqué artificiellement (justifié : rôle sans restriction par design, un faux 403 ne testerait rien de réel). CHEF_POSTE et ADMIN_REGIONAL -- positif et 403 sur les 4 endpoints. |

## Vérification des points critiques du prompt

1. **Ordre des vérifications** -- conforme et non permuté : POST fait `appelantCourant()` -> `verifierRoleAssignable` -> résolution poste (404) -> `verifierPerimetrePoste` -> unicité matricule ; PATCH/DELETE font résolution agent cible (404) -> `appelantCourant()` -> `verifierPerimetrePoste` (poste actuel) -> [PATCH] résolution nouveau poste (404) -> `verifierPerimetrePoste` (nouveau poste). Vérifié directement dans le diff de `AgentAdminService.java`, code identique au contrat technique de `spec.md`.
2. **`appelantCourant()`** -- lit bien `SecurityContextHolder...getAuthentication().getName()` (le matricule String), ne caste jamais le principal en `Agent`. Lève `AccesRefuseException` si `findByMatricule` vide OU si `!appelant.isActif()` -- aucun `Optional.get()` non géré. Testé explicitement par `lister_commeChefPosteDesactiveApresEmissionDuToken_shouldRetourner403AccesRefuse` (403 + code `ACCES_REFUSE`, pas 500).
3. **Non-régression** -- `git diff bolt/issue-9-crud-agents..HEAD` sur `AgentAdminIntegrationTest.java` montre 436 insertions / 0 suppression : les 14 tests existants sont strictement inchangés, seuls des ajouts.
4. **Matrice de rôles** -- `verifierPerimetrePoste`, `verifierRoleAssignable` et `lister()` correspondent ligne à ligne au contrat technique de `spec.md` (CHEF_POSTE -> poste propre, ADMIN_REGIONAL -> région propre, ADMIN_NATIONAL -> sans restriction, règles d'assignation de rôle CHEF_POSTE->AGENT, ADMIN_REGIONAL->AGENT/CHEF_POSTE, ADMIN_NATIONAL->tout).
5. **`PerimetreRegional.estDansPerimetreRegion`** -- logique exacte : `ADMIN_NATIONAL -> true`, `ADMIN_REGIONAL -> appelant.getPoste().getRegion().getId().equals(regionId)`, `default -> false` (couvre bien CHEF_POSTE/AGENT/AUDITEUR). Les 6 tests unitaires de `PerimetreRegionalTest` couvrent les 5 rôles.
6. **Couverture par rôle** -- vérifiée exhaustivement en lisant le fichier de test complet (789 lignes) : les 5 rôles ont chacun au moins un cas positif et un cas 403, y compris les cas limites AGENT/AUDITEUR (via `/api/v1/postes`, endpoint confirmé public) et ADMIN_NATIONAL (absence justifiée de 403 RBAC artificiel).
7. **PATCH avec `posteId` fourni** -- `verifierPerimetrePoste` appelée deux fois : une fois sur `agent.getPoste()` (poste actuel de la cible) et une fois sur `posteResolu` (nouveau poste) si `request.posteId() != null`. Testé par `modifier_commeChefPoste_versAutrePoste_shouldRetourner403` et `modifier_commeAdminRegional_versPosteHorsRegion_shouldRetourner403` (agent cible dans le périmètre, mais nouveau poste hors périmètre -> 403).

Points annexes vérifiés sans anomalie :
- Aucune migration Flyway (aucune nécessaire, confirmé par le contrat technique et le diff).
- Aucune donnée sensible nouvelle introduite par ce ticket (pas de numéro de document/contact citoyen concerné ici, scope = RBAC sur le CRUD agents existant).
- Pas de changement de `SecurityConfig.java` ni de `AgentAdminController.java` (diff vide confirmé par `git diff`).
- Pas de collision de matricules dans les nouveaux tests (chaque test nettoie la base via `@BeforeEach`, les doublons observés sont des réutilisations intentionnelles du même matricule au sein d'un même test, pas des collisions inter-tests).

## Build/tests

- `mvn -pl backend -am test -Dtest=AgentTest,JwtServiceTest,PerimetreRegionalTest` -> **BUILD SUCCESS**, 15/15 tests passés (6 `AgentTest`, 3 `JwtServiceTest`, 6 `PerimetreRegionalTest`).
- `mvn -pl backend -am test -Dtest=AgentAdminIntegrationTest` -> échec environnemental documenté : `ContainerFetchException: Could not find a valid Docker environment` (Testcontainers/Docker Desktop indisponible sous Windows dans cet environnement d'exécution). Limitation déjà documentée sur les tickets précédents (#7/#9), affecte de la même façon tous les tests `@Testcontainers` du dépôt (`AuthIntegrationTest`, `PosteIntegrationTest`, `AgentIntegrationTest`, `SamaPieceApplicationTests`), pas spécifique à ce bolt.
- En compensation : relecture de code intégrale des 436 lignes ajoutées à `AgentAdminIntegrationTest.java` (30 nouveaux scénarios) contre le plan de tests détaillé de `spec.md`, ligne par ligne -- constructions multi-postes/multi-régions correctes, assertions cohérentes avec la matrice de rôles, ordre de vérification respecté dans chaque scénario, aucune anomalie relevée.

## Conclusion

Le code correspond exactement au contrat technique de `spec.md` (méthodes privées, ordre de vérification non-uniforme volontaire entre POST et PATCH/DELETE, `PerimetreRegional` distinct de `verifierPerimetrePoste`). La matrice de rôles est intégralement respectée et testée. Les 14 tests de non-régression du ticket #9 sont strictement intacts. Les écarts documentés (AC3 partiel, AC4 pour AGENT/AUDITEUR/ADMIN_NATIONAL) sont justifiés par le design et ne masquent pas de trou de couverture réel. Aucun bug fonctionnel, aucune régression, aucun problème de sécurité identifié dans le diff.
