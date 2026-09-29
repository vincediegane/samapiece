# Design #73 - Onglet "Agents" visible selon le rôle

## Approche
Frontend uniquement, en copiant le pattern déjà en place pour Audit (#64), Référentiel (#71) et Vue multi-poste (#72) : un helper `peutGererAgents(role)` dans `features/agents/roles.ts`, utilisé (1) dans `AgentShell` pour conditionner le bouton, (2) dans `AgentsPage` comme garde défensive (accès direct/contournement). `AgentsPage` récupère elle-même l'agent courant via `recupererAgentCourant()` (comme `AuditPage`), ce qui évite de modifier `App.tsx`. Prix : un second appel `GET /api/v1/agents/moi` au montage de la page, accepté pour garder le diff minimal et éviter les conflits. Le backend (`AgentAdminController`, `@PreAuthorize hasAnyRole('CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')` sur GET/POST/PATCH/DELETE) est déjà correct et n'est pas touché.

## Fichiers/modules impactés
Tous sous `frontend/src/` :
- CRÉER `features/agents/roles.ts` : `ROLES_GESTION_AGENTS = ['CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL']`, `peutGererAgents(role: string | null | undefined): boolean` (même forme et même commentaire "Confort UX uniquement" que `features/audit/roles.ts`).
- MODIFIER `shared/layout/AgentShell.tsx` : envelopper le bouton "Agents" (l.~138-144) dans `{agent && peutGererAgents(agent.role) && (...)}` + 1 import. Rien d'autre.
- MODIFIER `features/agents/agentsApi.ts` : ajouter `export class ErreurApiAgents extends Error { readonly statut: number }` (calquée sur `ErreurApiAudit`) ; `listerAgents` et `desactiverAgent` la lèvent quand `!reponse.ok`. `creerAgent` : conserver `erreur?.message ?? ...` mais lever `ErreurApiAgents(statut, message)` pour que 401/403 restent distinguables.
- MODIFIER `features/agents/AgentsPage.tsx` : garde de rôle + messages 401/403 distincts.
- CRÉER `features/agents/AgentsPage.test.tsx` ; ÉTENDRE `shared/layout/AgentShell.test.tsx` ; éventuellement `features/agents/roles.test.ts` (table de vérité).
- `app/App.tsx` : NON modifié (l'onglet 'agents' reste, la garde est dans la page).

## Décisions clés
- Helper dans `features/agents/roles.ts` (un `roles.ts` par feature, cohérent avec audit/dashboard/referentiel), pas de fichier de rôles partagé (refactor hors périmètre).
- Défaut fermé : `agent` est `null` tant que `recupererAgentCourant()` n'a pas répondu (ou a échoué) et `peutGererAgents(null)` renvoie `false` -> entrée masquée pendant le chargement (léger flash d'absence accepté, identique aux autres onglets).
- `AgentsPage` : état `acces: 'attente' | 'autorise' | 'refuse'` comme `AuditPage`. `attente` -> "Chargement…" ; `refuse` (rôle non autorisé, ou échec de `recupererAgentCourant`) -> aucun appel à `listerAgents()` ni `/api/v1/postes`, affiche `role="alert"` avec "Droits insuffisants : la gestion des comptes agents est réservée aux chefs de poste et aux administrateurs." Les deux `fetch` du `useEffect` ne partent que si `acces === 'autorise'`. Vérifié : `recupererAgentCourant` (`dashboardApi.ts`) lève une `Error` générique sans statut ; on ne le modifie pas, un échec -> `refuse` (défaut fermé). `App.test.tsx` ne mentionne pas "Agents".
- Distinction erreurs dans `AgentsPage` (chargement liste, création, désactivation) : 401 -> "Session expirée ou invalide, reconnectez-vous." ; 403 -> message de droits insuffisants ci-dessus ; autre -> "Impossible de charger les agents." (le libellé actuel "jeton absent ou expiré" est supprimé). Factoriser en une petite fonction `messagePourErreur(e, contexteParDefaut)` locale à la page.
- Pas de nouvelle route/URL : l'"accès direct" = rendu de `AgentsPage` sans l'entrée de menu (ex. `onNaviguer('agents')` forcé ou onglet déjà actif après changement de session).
- Backend inchangé : la vraie sécurité reste le `@PreAuthorize` (PROJET-SAMAPIECE.md §10).

## Risques / points d'attention
- Conflits de merge : #65, #71, #72 touchent `AgentShell.tsx`, `AgentShell.test.tsx` et `App.tsx`. `git log` montre que les commits #71/#72 sont déjà dans l'historique de cette branche ; revérifier `git fetch` avant de coder. Le diff dans `AgentShell.tsx` doit rester à ~3 lignes (import + condition autour du bouton), sans reformater les boutons voisins. `App.tsx` non touché.
- `AgentShell.test.tsx` : le mock par défaut est `role: 'AGENT'` ; ajouter des cas sans réordonner les tests existants (ajout en fin de fichier pour limiter les conflits). Vérifier qu'aucun test existant (dont `App.test.tsx`) ne clique sur "Agents" avec un rôle `AGENT` (grep `Agents` dans App.test.tsx à faire par le codeur).
- Tests : `it.each(['CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL'])` entrée visible + navigation `'agents'` ; `it.each(['AGENT','AUDITEUR'])` entrée absente (`queryByRole('button',{name:'Agents'})`) ; entrée absente si `recupererAgentCourant` rejette. Page : rôle `AGENT` -> message de droits, `listerAgents` non appelé ; rôle autorisé + `listerAgents` rejetant `ErreurApiAgents(403)` -> droits ; 401 -> session ; 500 -> générique. Mocker `./agentsApi` avec `importOriginal` (comme `AuditPage.test.tsx`) pour garder `ErreurApiAgents`.
- AUDITEUR n'a pas accès (backend) : il doit donc aussi voir l'entrée masquée.
- Ne pas casser le contrat d'erreur de `creerAgent` (messages backend affichés à l'utilisateur, ex. matricule en doublon).

## Hors périmètre
- Toute modification backend, de `AgentAdminController` ou des rôles.
- Refactor de navigation (routeur, garde générique par onglet, contexte d'agent partagé), factorisation de `roles.ts`.
- Gestion 401 -> redirection auto vers login (ticket #60), autres pages, PATCH d'agent côté UI.
