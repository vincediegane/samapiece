# Review — #26 Vue "stock courant" par poste (Dashboard)

APPROVE

## Critères d'acceptation

| Critère | Statut |
|---|---|
| `GET /api/v1/statistiques/poste/{id}` renvoie nombre de pièces en attente, ancienneté moyenne/max, réservé aux rôles du poste concerné | Couvert — `PieceRepository.agregerStockParPoste`/`compterDepassantSeuil`, `StatistiquesPosteService.consulter`, `StatistiquesController` avec `@PreAuthorize` + `PerimetrePoste.estDansPerimetre` ; testé par `StatistiquesPosteIntegrationTest` (8 cas RBAC + 404 + calcul + valeurs nulles) |
| Interface frontend affichant les indicateurs pour le poste de l'agent connecté | Couvert — `DashboardPage.tsx` (`useEffect` → `recupererAgentCourant()` → `getStatistiquesPoste(agent.posteId)`), testé par `DashboardPage.test.tsx` |
| Alerte visuelle sur pièces dépassant un seuil d'ancienneté configurable | Couvert — `StatistiquesProperties.seuilAncienneteJours` (défaut 180, configurable via `SAMAPIECE_SEUIL_ANCIENNETE_JOURS`), badge `role="alert"` conditionnel dans `DashboardPage.tsx`, testé côté back (`nombrePiecesDepassantSeuil`/`seuilAncienneteJours` en JSON) et côté front (présence/absence du badge) |
| Test d'intégration sur le calcul des indicateurs avec un jeu de données de test | Couvert — `StatistiquesPosteIntegrationTest.consulter_avecPiecesConnues_shouldRetournerIndicateursCorrects` (jeu A/B/C conforme à la spec) et `consulter_sansPieceEnAttente_shouldRetournerValeursNulles` |

Aucun critère non couvert ou partiel.

## Relecture détaillée (points de vigilance du prompt)

1. **Littéraux SQL en minuscules** : vérifié ligne par ligne. `PieceRepository.agregerStockParPoste`/`compterDepassantSeuil` utilisent `'disponible'`/`'reclamee'` en minuscules exactes, cohérent avec `StatutPieceConverter.convertToDatabaseColumn` (`name().toLowerCase()`) et la contrainte CHECK de `V4__create_piece.sql` (`DEFAULT 'disponible' CHECK (statut IN ...)`). Pas de `StatutPiece.X.name()` utilisé dans le SQL natif. OK.
2. **`AVG`/`MAX` sur 0 ligne** : `StockPosteAgrege` déclare `Double getAncienneteMoyenneJours()`/`Long getAncienneteMaxJours()` (types objets, pas primitifs), propagés tels quels dans `StatistiquesPosteResponse` (record avec `Double`/`Long`), donc sérialisation JSON `null` sans logique supplémentaire. Testé explicitement par `consulter_sansPieceEnAttente_shouldRetournerValeursNulles`.
3. **RBAC à deux niveaux** : `@PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')")` au niveau contrôleur (exclut AUDITEUR), `PerimetrePoste.estDansPerimetre` dans le service pour le contrôle fin poste/région (`default -> false` couvre AUDITEUR en profondeur si jamais atteint). Pas de duplication ni de contradiction. Les 8 cas RBAC du test d'intégration couvrent toutes les combinaisons attendues.
4. **Pas de nouveau `@RestControllerAdvice`** : confirmé, aucun handler ajouté dans `reporting`. `AgentAdminExceptionHandler` reste le seul `@RestControllerAdvice` gérant `AccesRefuseException`/`PosteIntrouvableException`, application globale (pas de `basePackages`). Le build backend démarre sans ambiguïté Spring (compile + test-compile OK).
5. **`GET /api/v1/agents/moi`** : `AgentSelfController`/`AgentSelfService` sont des classes dédiées dans `sn.samapiece.iam`/`sn.samapiece.iam.web`, distinctes de `AgentAdminController`/`AgentAdminService`. Aucun `@PreAuthorize` sur la route, retombe sur `.anyRequest().authenticated()`. Pas de collision de route avec `AgentAdminController` (`@GetMapping` sans path = liste, pas de `@GetMapping("/{id}")`/`@GetMapping("/moi")` existant). Testé par `AgentSelfIntegrationTest` (AGENT actif 200, AUDITEUR 200, sans token 401, agent désactivé après émission du token 403 ACCES_REFUSE).
6. **Fiabilité temporelle des tests** : `LocalDate aujourdHui = LocalDate.now()` capturé une seule fois en tête de chaque test qui en a besoin, dates de dépôt dérivées via `minusDays(N)`, valeurs attendues calculées via `ChronoUnit.DAYS.between(...)` — aucune valeur codée en dur qui dépendrait du jour d'exécution. Conforme à la décision de spec (pas de `Clock` injectable).
7. **Ordre de nettoyage** : `pieceRepository.deleteAll()` → `jdbcTemplate.update("DELETE FROM piece_sequence")` → `agentRepository.deleteAll()` → `posteRepository.deleteAll()` → `regionRepository.deleteAll()`. `piece_sequence` (FK vers `poste`) est bien vidée avant `posteRepository.deleteAll()`. `getContentAsString(StandardCharsets.UTF_8)` utilisé pour les deux tests lisant du JSON avec assertions de contenu.
8. **Frontend** : `DashboardPage.tsx` affiche "Aucune pièce en attente." quand `ancienneteMoyenneJours`/`ancienneteMaxJours` sont `null` (pas de `NaN`/`null` brut) ; badge d'alerte avec `role="alert"` distinct du message d'erreur réseau (également `role="alert"` mais rendu conditionnellement uniquement en cas d'erreur réseau — les deux ne coexistent jamais dans le même rendu puisque `statistiques` et `erreur` s'excluent mutuellement dans le flux normal), présent seulement si `nombrePiecesDepassantSeuil > 0`. Vérifié par 5 tests Vitest, tous passants.
9. **Pas de nouveau module frontend partagé** : `recupererAgentCourant` vit directement dans `dashboardApi.ts`, pas de `features/agent-courant/`.
10. **`App.tsx`** : `Onglet` étendu avec `'dashboard'`, bouton de nav ajouté, rendu conditionnel en chaîne `? :` cohérent avec le pattern existant, pas de router introduit.

Aucun écart non justifié par rapport à `spec.md`. Le seul débordement de périmètre (endpoint `/api/v1/agents/moi` dans `iam`) est explicitement tranché et justifié dans `spec.md` §Décisions tranchées, et correctement implémenté conformément à cette décision.

## Sécurité des données (§10 PROJET-SAMAPIECE.md)

`StatistiquesPosteResponse` n'expose que des agrégats (compteurs, moyennes/max en jours, nom du poste) — aucune donnée nominative de titulaire, numéro de document ou contact citoyen. Pas de nouvelle donnée sensible stockée en clair. Cohérent avec le principe de minimisation.

## Build/tests

- `mvn -q -pl . -am compile` (dans `backend/`) → succès.
- `mvn -q -pl . -am test-compile` (dans `backend/`) → succès.
- `mvn -q -pl . -am test -Dtest='!*IntegrationTest'` (dans `backend/`) → 187 tests exécutés, 0 échec ; les 2 seules erreurs (`SamaPieceApplicationTests`, `PieceNumeroFicheGeneratorTest`) sont dues à l'absence de Docker dans ce sandbox (`ContainerFetchException` Testcontainers), non liées au code du bolt. `StatistiquesPosteIntegrationTest`/`AgentSelfIntegrationTest` n'ont pas pu être exécutés pour la même raison (Testcontainers indisponible) ; compensé par relecture manuelle ligne à ligne, en particulier des littéraux SQL et de l'ordre de nettoyage FK (point 1 et 7 ci-dessus).
- `npm run build` (dans `frontend/`) → succès (tsc -b + vite build).
- `npm run lint` (dans `frontend/`) → succès, aucun avertissement.
- `npm test -- --run` (dans `frontend/`) → 3 fichiers, 21 tests, tous passants (dont les 5 nouveaux `DashboardPage.test.tsx`).

## Conclusion

Implémentation fidèle à `spec.md`, point critique des littéraux SQL minuscules correctement traité, RBAC à deux niveaux cohérent, pas de duplication de `@RestControllerAdvice`, tests backend/frontend couvrant tous les critères d'acceptation avec un jeu de données déterministe (pas de dépendance à la date d'exécution codée en dur). Build et tests exécutables localement passent sans régression.
