# Design #72 - Vue multi-poste du dashboard (ADMIN_REGIONAL / ADMIN_NATIONAL)

_Rédigé par le bolt-architect (sans outil d'écriture) ; transcrit dans ce fichier par l'orchestrateur. Constats vérifiés : `StatistiquesProperties`, `StatistiquesPosteService`, `StockPosteAgrege` (projection existante) et `PieceRepository` présents ; `features/audit/roles.ts` et `features/referentiel/roles.ts` existent._

## Approche
Deux nouveaux endpoints en lecture seule dans `StatistiquesController` renvoient, pour une portée (région de l'appelant, ou national), la liste des postes avec leurs stats et des totaux consolidés. Les stats viennent d'**une seule requête SQL groupée par poste** (LEFT JOIN `poste` -> `piece`, donc pas de N+1). Elle reprend les conditions de #26 : statuts `disponible`/`reclamee`, ancienneté `CURRENT_DATE - date_depot`, dépassement strict `> seuil`, seuil = `StatistiquesProperties.seuilAncienneteJours` (`samapiece.reporting.seuil-anciennete-jours`, 180 par défaut). Côté UI, un **nouvel onglet dédié** est visible pour ces deux rôles ; `DashboardPage` (poste unique) n'est pas touché.

Prix : la condition SQL de #26 est répétée dans une 2e requête native (impossible de partager un fragment entre `@Query` natifs). Un test de cohérence avec l'endpoint poste verrouille cette duplication.

## Fichiers/modules impactés
Backend (`backend/src/main/java/sn/samapiece/`) :
- `enregistrement/PieceRepository.java` : ajouter les méthodes groupées par poste (national, par région). `agregerStockParPoste(UUID)` et `compterDepassantSeuil` existants inchangés.
- `reporting/StockParPosteAgrege.java` (nouveau, projection) : posteId, posteNom, regionId, regionNom, nombrePieces, ancienneteMoyenneJours, ancienneteMaxJours, nombreDepassant. (Vérifier la cohérence de style avec `StockPosteAgrege` existant.)
- `reporting/StatistiquesConsolideesResponse.java` (nouveau record) : `portee`, `regionId`, `regionNom` (null en national), `seuilAncienneteJours`, `totaux`, `postes` ; record de ligne par poste : posteId, posteNom, regionNom, nombrePiecesEnAttente, ancienneteMoyenneJours, ancienneteMaxJours, nombrePiecesDepassantSeuil.
- `reporting/StatistiquesConsolideesService.java` (nouveau) : résolution de l'appelant, périmètre, calcul des totaux. Ne pas modifier `StatistiquesPosteService` (copier `appelantCourant()` ou l'extraire dans un petit helper commun).
- `reporting/web/StatistiquesController.java` : 2 nouveaux handlers.
- Test : `backend/src/test/java/sn/samapiece/reporting/StatistiquesConsolideesIntegrationTest.java` (modèle : `StatistiquesPosteIntegrationTest`).

Frontend (`frontend/src/`) :
- `features/dashboard/roles.ts` (nouveau) : `peutVoirVueMultiPoste(role)` vrai pour ADMIN_REGIONAL et ADMIN_NATIONAL (pattern `features/audit/roles.ts`).
- `features/dashboard/VueMultiPostePage.tsx` (nouveau) ; `types.ts` : types consolidés ; `dashboardApi.ts` : `getStatistiquesRegionale`, `getStatistiquesNationale`.
- Tests Vitest pour la nouvelle page.
- `shared/layout/AgentShell.tsx` : `'vue-multi-poste'` dans `OngletAgent` + bouton conditionné par `peutVoirVueMultiPoste(agent.role)` (à côté d'Audit et Référentiel).
- `app/App.tsx` : onglet dans `ONGLETS_AGENT` et au rendu.
- Pas de migration Flyway (`idx_piece_poste_id` existe, V4 ; un scan agrégé suffit).

## Décisions clés
1. **Contrat** (préfixe `/api/v1/statistiques`) :
   - `GET /regionale` : `@PreAuthorize("hasRole('ADMIN_REGIONAL')")`. Région **déduite côté serveur** de `appelant.getPoste().getRegion()` ; le client n'envoie aucun id (rien à falsifier). `AgentCourant` n'expose pas `regionId`. Inutile d'appeler `PerimetreRegional.estDansPerimetreRegion` ici.
   - `GET /nationale` : `@PreAuthorize("hasRole('ADMIN_NATIONAL')")`, tous les postes.
   - Autres rôles (AGENT, CHEF_POSTE, AUDITEUR…) : 403 via `@PreAuthorize` ; sans authentification : 401 existant. ADMIN_NATIONAL sur `/regionale` : 403 (utiliser `/nationale`).
   - Agent appelant introuvable ou inactif : `AccesRefuseException`, comme `StatistiquesPosteService`.
2. **Réponse** : `postes` trié par `nombrePiecesDepassantSeuil` décroissant puis `posteNom`. Les **postes à 0 pièce sont inclus** (LEFT JOIN) : `nombrePiecesEnAttente=0`, moyenne/max `null`. `totaux` : `nombrePiecesEnAttente` (somme), `ancienneteMoyenneJours` (moyenne pondérée par le nombre de pièces, calculée en Java, `null` si 0 pièce), `ancienneteMaxJours` (max des max), `nombrePiecesDepassantSeuil` (somme), `nombrePostes`, `nombrePostesEnDepassement`.
3. **Seuil et alertes (#26)** : un seul seuil global lu dans `StatistiquesProperties`, passé en `:seuilJours` et renvoyé dans `seuilAncienneteJours`. Mêmes statuts et comparaison stricte que `compterDepassantSeuil`. Dépassement en SQL : `COUNT(*) FILTER (WHERE (CURRENT_DATE - p.date_depot) > :seuilJours)`. **Test obligatoire** : sur un même jeu de données, chaque ligne du consolidé égale la réponse de `GET /poste/{id}` (nombres, moyenne, max, dépassants).
4. **Requête** : native, `FROM poste po JOIN region r ... LEFT JOIN piece p ON p.poste_id = po.id AND p.statut IN ('disponible','reclamee') [WHERE po.region_id = :regionId] GROUP BY po.id, po.nom, r.id, r.nom`. Deux méthodes de repository (national ; par région) pour éviter le piège du paramètre UUID `null` en PostgreSQL (alternative : `CAST(:regionId AS uuid) IS NULL`).
5. **UI** : onglet dédié « Vue multi-poste » (pas de remplacement conditionnel) : `DashboardPage` reste strictement inchangé pour AGENT/CHEF_POSTE ; l'admin garde son propre poste dans « Tableau de bord ». La page lit le rôle via `recupererAgentCourant()` puis appelle `/regionale` ou `/nationale`. Bannière `role="alert"` si totaux dépassants > 0 (même formulation que #26), tuiles de totaux, tableau par poste (colonne Région uniquement en national), lignes en dépassement en style `danger` existant, textes `sr-only` conservés. Contrôle de rôle front = confort UX (même commentaire que les `roles.ts` existants).
6. **Audit** : aucun impact. `GET /poste/{id}` n'est pas `@ActionAuditee` ; pas de nouvelle action d'audit ni de changement d'`AuditAspect`. Auditer les consultations agrégées = ticket séparé si le PO le souhaite.

## Risques / points d'attention
- Dérive entre la logique SQL de #26 et la nouvelle requête : couverte par le test de cohérence (décision 3).
- Types PostgreSQL : `AVG(CURRENT_DATE - date_depot)` = numeric, lu en `Double` comme l'existant ; `COUNT` = `long` ; `MAX` sur zéro ligne = `null` => wrappers `Long`/`Double`.
- Tests `@SpringBootTest` créant des `Piece` : vider `piece_sequence` avant de supprimer les `Poste` (FK), sinon échec seulement en CI. MockMvc : `getContentAsString(StandardCharsets.UTF_8)`.
- Cas de test : ADMIN_REGIONAL ne voit que sa région (autre région avec pièces exclue) ; ADMIN_NATIONAL voit tout ; AGENT et CHEF_POSTE => 403 sur les 2 endpoints ; ADMIN_NATIONAL sur `/regionale` => 403 ; région sans pièce => totaux 0 et moyenne/max `null`.
- `OngletAgent` utilisé par `AgentShell` et `App.test.tsx` : ne mettre à jour les tests existants que si nécessaire ; aucun test du dashboard poste unique ne doit changer.
- Réponse sans donnée personnelle (compteurs, noms de postes/régions). Vue admin en ligne uniquement, pas de cache IndexedDB.

## Hors périmètre
- Modifier `GET /poste/{id}`, `StatistiquesPosteService`, `DashboardPage` ou son API.
- Seuil par poste/région, filtres, pagination, tri côté client, export, graphiques, historique.
- Endpoint paramétré `/region/{id}` (drill-down national), ajout de `regionId` à `/agents/moi`.
- Audit des consultations, migration Flyway, cache.
