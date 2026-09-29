# Design #64 - Interface de consultation du journal d'audit

## Approche
Ajouter une page frontend `audit` (onglet de plus dans `AgentShell`/`App.tsx`) qui consomme `GET /api/v1/audit/evenements`, et ajouter côté backend deux filtres optionnels (`action`, `entiteCible`). Le filtrage est fait côté serveur : filtrer côté client une page de 20 lignes serait faux (résultats incomplets) et le journal est volumineux par nature. Prix : un petit changement backend + tests, mais contrat rétro-compatible (paramètres optionnels). Le RBAC réel reste porté par le backend (`@PreAuthorize`), le frontend ne fait que masquer l'entrée et afficher un refus propre.

## Constats sur l'existant (vérifiés)
- `backend/.../audit/web/AuditController.java` : `GET /api/v1/audit/evenements`, `@PreAuthorize("hasAnyRole('AUDITEUR','ADMIN_NATIONAL')")`, `@PageableDefault(size=20, sort="horodatage", DESC)`. Retourne `Page<EvenementAuditResponse>` (`id, acteurId, typeActeur, action, entiteCible, entiteCibleId, details, adresseIp, horodatage`). **Aucun filtre supporté** aujourd'hui.
- `EvenementAuditRepository extends Repository<...>` (volontairement sans delete/update, garanti par `EvenementAuditRepositoryContractTest`) : seulement `save`, `findAll(Pageable)`, `findById`.
- Index existants (V10) : `(entite_cible, entite_cible_id)`, `acteur_id`, `horodatage DESC`. Pas d'index sur `action` (inutile à ce volume, pas de migration).
- Actions émises aujourd'hui (uniquement `PieceController`, entité `PIECE`) : `PIECE_CREEE, PIECE_CONSULTEE, PIECE_RETIREE, PIECE_SIGNALEE, PIECE_RECU_GENERE, PIECE_DEBLOQUEE`.
- Frontend : pas de router. `App.tsx` = state `onglet` ; `AgentShell.tsx` = sidebar avec 4 boutons en dur (`pieces, fiche, dashboard, agents`), **aucune notion de rôle ni de guard** (même l'onglet Agents est visible de tous). Le rôle n'est connu que via `recupererAgentCourant()` (`features/dashboard/dashboardApi.ts`, `AgentCourant.role: string`), déjà appelé dans `AgentShell`. Précédent d'usage du rôle côté UI : `FichePieceCard` (`roleAgentCourant === 'CHEF_POSTE'`).
- Conventions d'API front : fichier `xxxApi.ts` avec `fetch` + `Authorization: Bearer <samapiece.accessToken>` (voir `features/agents/agentsApi.ts`), `types.ts`, page + `*.test.tsx` (Vitest + Testing Library). Libellés/messages en français, classes `alert-error`, `sidebar-link(-active)`.

## Fichiers/modules impactés
Backend (modifiés) :
- `backend/src/main/java/sn/samapiece/audit/web/AuditController.java` : ajouter `@RequestParam(required=false) String action, entiteCible`.
- `backend/src/main/java/sn/samapiece/audit/EvenementAuditService.java` : `lister(action, entiteCible, pageable)`.
- `backend/src/main/java/sn/samapiece/audit/EvenementAuditRepository.java` : ajouter `findByAction`, `findByEntiteCible`, `findByActionAndEntiteCible` (Page, Pageable). Ne pas ajouter de méthode `delete*`/`update*`.
- Tests : `backend/src/test/java/sn/samapiece/audit/AuditEndpointIntegrationTest.java` (filtres, combinaison, absence de filtre inchangée, 403 pour AGENT/CHEF_POSTE).

Frontend (créés) :
- `frontend/src/features/audit/types.ts`, `auditApi.ts`, `AuditPage.tsx`, `AuditPage.test.tsx`.
Frontend (modifiés) :
- `frontend/src/shared/layout/AgentShell.tsx` : type `OngletAgent` += `'audit'` ; bouton "Journal d'audit" (icône existante ou nouvelle dans `shared/icons.tsx`) rendu seulement si `agent?.role` ∈ {`AUDITEUR`,`ADMIN_NATIONAL`}.
- `frontend/src/app/App.tsx` : ajouter `'audit'` à `ONGLETS_AGENT` et le rendu `<AuditPage />`.
- Tests existants à compléter : `frontend/src/app/App.test.tsx` (+ test d'AgentShell si présent, sinon dans App.test).

## Décisions clés
1. **Filtres côté backend**, paramètres optionnels `action` et `entiteCible` (égalité stricte, combinables en AND). Implémentation par méthodes dérivées du repository et branchement dans le service (pas de `JpaSpecificationExecutor`, qui pourrait exposer des `delete` et casser le test de contrat ; pas de `@Query ... :p is null` qui pose des soucis de typage sur PostgreSQL). Tri par défaut conservé (`horodatage DESC`) ; les paramètres `page`/`size` Spring restent supportés.
2. **Filtre UI** : liste déroulante d'actions (constante front des 6 actions connues + "Toutes") et champ/liste pour l'entité (seule valeur actuelle : `PIECE` + "Toutes"). Changement de filtre remet la page à 0. Pas de filtre acteur/date (hors périmètre).
3. **Pagination UI** : précédent/suivant + "page X sur N" à partir de `number`, `totalPages`, `totalElements` de la `Page` Spring ; taille fixe 20.
4. **Visibilité/guard** : helper unique (ex. `peutConsulterAudit(role)` dans `features/audit/`) utilisé par la sidebar ET par `App.tsx`. Comme la navigation est un state (pas d'URL), "accès direct refusé" = `AuditPage` (ou un garde dans `AgentShell`) vérifie le rôle via `recupererAgentCourant()` et affiche un message "Accès réservé aux auditeurs et administrateurs nationaux" sans appeler l'API si rôle non autorisé ; si l'API répond 403/401, même message (pas de crash, pas de stack). Ne pas introduire de router pour ce ticket.
5. **Affichage** : colonnes horodatage (format local fr), action, entité (+ id court), acteur (`typeActeur` + `acteurId`), IP, détails. `details` affiché tel quel, échappé (rendu texte React, jamais HTML).
6. **Pas de migration Flyway**, pas de changement de modèle.

## Risques / points d'attention
- Le rôle vient d'un appel async (`recupererAgentCourant`) : tant qu'il n'est pas chargé, l'entrée doit être masquée (défaut fermé) et la page ne doit pas déclencher l'appel d'audit.
- Le RBAC front n'est qu'un confort UX ; ne jamais s'en servir comme sécurité. Ne pas élargir le `@PreAuthorize` backend.
- Données personnelles : `details`, `acteurId` et `adresseIp` sont sensibles ; ne pas les logger côté front, ne pas les mettre en cache offline (IndexedDB/service worker) - l'écran est en ligne uniquement. Vérifier que le service worker/PWA ne met pas en cache `/api/v1/audit`.
- Consulter le journal n'est pas audité aujourd'hui (aucun `@ActionAuditee` sur `AuditController`) ; ne pas l'ajouter ici (le ferait croître à chaque affichage).
- Valeurs d'action codées en dur côté front : risque de dérive avec de nouvelles actions backend ; acceptable, à documenter dans le code.
- Les filtres avec valeur inconnue renvoient simplement une page vide (pas de 400).
- Tests MockMvc : utiliser `getContentAsString(StandardCharsets.UTF_8)`. Un test `@SpringBootTest` créant des `Piece` doit vider `piece_sequence` avant de supprimer les `Poste` (mémoire projet).
- Prévoir l'état vide ("Aucun événement"), l'état chargement et l'état erreur.

## Hors périmètre
- Filtres acteur/plage de dates, recherche texte, export CSV, tri utilisateur.
- Auditer la consultation du journal lui-même.
- Introduction d'un router ou d'un système générique de guards par rôle ; masquage des autres entrées (ex. Agents) selon le rôle.
- Nouveau endpoint (liste des actions distinctes), index ou migration.
- Modification de `EvenementAuditResponse`, de la rétention/immutabilité du journal, ou du rôle `ADMIN_REGIONAL` (non autorisé).
