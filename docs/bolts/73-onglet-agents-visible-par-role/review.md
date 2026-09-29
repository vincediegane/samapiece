# Review #73 - Onglet "Agents" visible selon le rôle

**Verdict : APPROVE**

_Rapport produit par le bolt-reviewer (sans outil d'écriture) ; transcrit dans ce fichier par l'orchestrateur._

## Critères d'acceptation

| Critère | Statut | Code / test |
|---|---|---|
| Entrée "Agents" visible uniquement pour CHEF_POSTE, ADMIN_REGIONAL, ADMIN_NATIONAL | Couvert | `AgentShell.tsx` : bouton enveloppé dans `{agent && peutGererAgents(agent.role) && ...}`. Test `it.each` des 3 rôles : bouton présent, clic => `onNaviguer('agents')`. |
| Un AGENT ne voit pas l'entrée | Couvert | `it.each(['AGENT','AUDITEUR'])` : `queryByRole` renvoie absent après `findByText` du poste. |
| Accès direct non autorisé : message de droits, jamais celui de session invalide | Couvert | `AgentsPage.tsx` : état `acces` (`attente` / `autorise` / `refuse`), défaut fermé. Tests AGENT, AUDITEUR, échec de `recupererAgentCourant` : alerte de droits, `listerAgents` et `fetch` non appelés, aucun texte jeton/session. |
| Test d'affichage conditionnel selon le rôle | Couvert | 3 tests dans `AgentShell.test.tsx` + `roles.test.ts` (table de vérité incl. `null`, `undefined`, `''`). |

## Spec : 8 tâches
- Tâches 1 à 7 réalisées comme spécifié (`roles.ts`, `agentsApi.ts` / `ErreurApiAgents`, `AgentsPage.tsx`, libellés exacts des messages, `messagePourErreur` aux 3 endroits prévus). Message backend de `creerAgent` préservé (`ErreurApiAgents(status, erreur?.message)`), test sur 409.
- Tâche 8 : vitest, tsc, lint verts.
- Diff de `AgentShell.tsx` minimal (1 import + 1 enveloppe, ordre du menu inchangé) ; `src/app/App.tsx` et `backend/` non touchés (diff vide).
- Aucun appel API tant que le rôle n'est pas autorisé (`useEffect` en retour anticipé si `acces !== 'autorise'`, drapeau `annule` au démontage).
- « jeton absent ou expiré » supprimé de `AgentsPage.tsx` (reste dans l'assertion négative du test et dans `ReferentielPage.tsx:29`).

## Findings non bloquants
1. Hors périmètre : `frontend/src/features/referentiel/ReferentielPage.tsx:29` contient encore « Impossible de charger les régions (jeton absent ou expiré). » — même message trompeur sur la page Référentiel. À traiter dans un ticket séparé.
2. `AgentShell.test.tsx` : blocs `<AgentShell ...>` des deux derniers tests indentés de 8 espaces au lieu de 6 (cosmétique, lint OK).
3. Aucun test du mapping 401/403 de `desactiverAgent` / `creerAgent` (seul `listerAgents` est testé) ; logique partagée via `messagePourErreur`, risque faible.
4. Info (écarts 5 et 6 de la spec) : l'appel `/api/v1/postes` reste sans en-tête Authorization, donc son erreur peut écraser celle de la liste ; un 401 sur `/agents/moi` donne « Droits insuffisants » dans `AgentsPage` (défaut fermé voulu).

## Build / tests
- `npx vitest run` : 25 fichiers, 249 tests OK ; `npx tsc -b` OK ; `npm run lint` OK.
- `git diff main...HEAD -- src/app ../backend` : vide.
