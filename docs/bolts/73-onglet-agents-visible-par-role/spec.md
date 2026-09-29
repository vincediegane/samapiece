# Spec #73 - Onglet "Agents" visible selon le rôle

## Résumé
L'entrée de menu "Agents" n'est affichée que pour CHEF_POSTE, ADMIN_REGIONAL et ADMIN_NATIONAL, et `AgentsPage` refuse l'accès direct aux autres rôles avec un message clair de droits insuffisants (sans appel API), en distinguant 401 (session), 403 (droits) et autres erreurs. Frontend uniquement, tous les chemins sous `frontend/src/`.

## Tâches

- [ ] 1. Créer `frontend/src/features/agents/roles.ts` (calqué sur `features/audit/roles.ts`) : `ROLES_GESTION_AGENTS = ['CHEF_POSTE', 'ADMIN_REGIONAL', 'ADMIN_NATIONAL']` et `export function peutGererAgents(role: string | null | undefined): boolean { return role != null && ROLES_GESTION_AGENTS.includes(role); }`, avec le commentaire "Confort UX uniquement : le contrôle d'accès réel est le @PreAuthorize du backend."
- [ ] 2. Créer `frontend/src/features/agents/roles.test.ts` (table de vérité, même forme que `features/dashboard/roles.test.ts`).
- [ ] 3. Modifier `frontend/src/features/agents/agentsApi.ts` : ajouter `ErreurApiAgents` (voir contrat) ; `listerAgents` et `desactiverAgent` lèvent `new ErreurApiAgents(reponse.status)` quand `!reponse.ok` ; `creerAgent` lève `new ErreurApiAgents(reponse.status, erreur?.message)` (message backend conservé).
- [ ] 4. Modifier `frontend/src/shared/layout/AgentShell.tsx` : ajouter `import { peutGererAgents } from '../../features/agents/roles';` (à côté des autres imports `roles`, ordre alphabétique de chemin : avant `audit/roles`) et envelopper le bouton "Agents" (l.136-143) dans `{agent && peutGererAgents(agent.role) && ( ... )}`. Aucun autre changement, ne pas reformater les boutons voisins ; ne pas changer l'ordre du menu.
- [ ] 5. Modifier `frontend/src/features/agents/AgentsPage.tsx` :
  - importer `recupererAgentCourant` (`../dashboard/dashboardApi`), `ErreurApiAgents`, `peutGererAgents` ;
  - état `acces: 'attente' | 'autorise' | 'refuse'` (init `'attente'`) ; un premier `useEffect` appelle `recupererAgentCourant()` (garde `annule` comme `AuditPage`) : succès -> `peutGererAgents(agent.role) ? 'autorise' : 'refuse'` ; échec -> `'refuse'` ;
  - le `useEffect` existant (listerAgents + `fetch('/api/v1/postes')`) devient `if (acces !== 'autorise') return;` avec dépendance `[acces]` ;
  - rendu anticipé après les hooks : `attente` -> `<div className="p-8 text-sm text-slate-500">Chargement…</div>` ; `refuse` -> `<div className="p-8"><div role="alert" className="alert-error">{MESSAGE_DROITS_INSUFFISANTS}</div></div>` (aucun formulaire, aucun tableau) ;
  - ajouter la fonction locale `messagePourErreur` (voir contrat) et l'utiliser aux 3 endroits : catch de `listerAgents` (défaut "Impossible de charger les agents."), catch de `creerAgent`, catch de `desactiverAgent` ;
  - supprimer le libellé "jeton absent ou expiré". Le catch de `/api/v1/postes` reste inchangé ("Impossible de charger les postes.").
- [ ] 6. Créer `frontend/src/features/agents/AgentsPage.test.tsx` (voir Plan de tests). Mock `../dashboard/dashboardApi` (`recupererAgentCourant: vi.fn()`) et `./agentsApi` avec `importOriginal` (garder `ErreurApiAgents`, mocker `listerAgents`, `creerAgent`, `desactiverAgent`) ; `vi.stubGlobal('fetch', ...)` ou `vi.spyOn(globalThis, 'fetch')` pour `/api/v1/postes` (renvoie `{ ok: true, json: async () => [] }`), `mockReset` dans `beforeEach`.
- [ ] 7. Étendre `frontend/src/shared/layout/AgentShell.test.tsx` : ajouter EN FIN de `describe` (ne pas réordonner l'existant) les 3 tests décrits au Plan de tests, même structure que les tests "Vue multi-poste".
- [ ] 8. Vérification finale : `npm run test` (Vitest), `npm run lint` et `npm run build` (tsc) dans `frontend/` verts. `frontend/src/app/App.tsx` inchangé (`git diff` vide sur ce fichier).

## Contrat technique

### `features/agents/roles.ts`
```ts
export const ROLES_GESTION_AGENTS = ['CHEF_POSTE', 'ADMIN_REGIONAL', 'ADMIN_NATIONAL'];
export function peutGererAgents(role: string | null | undefined): boolean;
```
Défaut fermé : `null`, `undefined`, `''`, `AGENT`, `AUDITEUR` -> `false`.

### `features/agents/agentsApi.ts`
```ts
export class ErreurApiAgents extends Error {
  readonly statut: number;
  constructor(statut: number, message?: string) {
    super(message ?? `Erreur ${statut}`);
    this.statut = statut;
  }
}
```
Exportée (nécessaire pour `instanceof` et pour les tests). Les signatures de `listerAgents`, `creerAgent`, `desactiverAgent` ne changent pas. Pour `creerAgent`, le message backend (ex. matricule en doublon) reste le `message` de l'erreur.

### Messages (libellés exacts)
| Constante / cas | Texte |
|---|---|
| `MESSAGE_DROITS_INSUFFISANTS` (garde de rôle, et HTTP 403) | `Droits insuffisants : la gestion des comptes agents est réservée aux chefs de poste et aux administrateurs.` |
| `MESSAGE_SESSION_INVALIDE` (HTTP 401) | `Session expirée ou invalide, reconnectez-vous.` |
| Chargement liste, autre erreur | `Impossible de charger les agents.` |
| Création, non-Error | `Erreur inconnue lors de la création.` (inchangé) |
| Désactivation, non-Error | `Erreur inconnue lors de la désactivation.` (inchangé) |
| Attente du rôle | `Chargement…` |

Le texte `jeton absent ou expiré` ne doit plus apparaître nulle part dans le code (`grep` = 0 résultat).

### `messagePourErreur` (locale à `AgentsPage.tsx`)
```ts
function messagePourErreur(e: unknown, parDefaut: string, utiliserMessage = false): string
```
- `e instanceof ErreurApiAgents && e.statut === 401` -> `MESSAGE_SESSION_INVALIDE`
- `e instanceof ErreurApiAgents && e.statut === 403` -> `MESSAGE_DROITS_INSUFFISANTS`
- sinon si `utiliserMessage && e instanceof Error` -> `e.message`
- sinon `parDefaut`.
Usages : liste `(e, 'Impossible de charger les agents.')` ; création `(e, 'Erreur inconnue lors de la création.', true)` ; désactivation `(e, 'Erreur inconnue lors de la désactivation.', true)`.

### Comportement `AgentsPage`
- `recupererAgentCourant` (rôle AGENT/AUDITEUR/échec) : `listerAgents` et `fetch('/api/v1/postes')` ne sont JAMAIS appelés.
- Rôle autorisé : comportement actuel inchangé (formulaire, tableau, mot de passe temporaire).
- Coût accepté : un second `GET /api/v1/agents/moi` au montage (le shell fait déjà le sien).

### Backend
Aucun changement. `AgentAdminController` : `@PreAuthorize("hasAnyRole('CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')")` déjà en place.

## Plan de tests

| Critère d'acceptation | Test | Fichier |
|---|---|---|
| Entrée "Agents" visible uniquement pour CHEF_POSTE, ADMIN_REGIONAL, ADMIN_NATIONAL | `it.each(['CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL'])` : `findByRole('button', { name: 'Agents' })` présent, clic -> `onNaviguer` appelé avec `'agents'` | `shared/layout/AgentShell.test.tsx` |
| Un AGENT ne voit pas cette entrée | `it.each(['AGENT','AUDITEUR'])` : après `findByText('Commissariat Central Dakar')`, `queryByRole('button', { name: 'Agents' })` absent | `shared/layout/AgentShell.test.tsx` |
| Test d'affichage conditionnel selon le rôle (défaut fermé) | `recupererAgentCourant` rejette (`new Error('Erreur 500')`) -> entrée "Agents" absente (`await Promise.resolve()` puis `queryByRole`, comme les tests existants) | `shared/layout/AgentShell.test.tsx` |
| Table de vérité du helper | 3 rôles autorisés `true` ; `AGENT`, `AUDITEUR`, `''` `false` ; `null` et `undefined` `false` | `features/agents/roles.test.ts` |
| Accès direct non autorisé : message clair de droits, jamais le message de session | rôle `AGENT` (et `AUDITEUR`) : `findByRole('alert')` contient `MESSAGE_DROITS_INSUFFISANTS` ; `listerAgents` non appelé ; `fetch` non appelé ; `queryByText(/jeton|session/i)` absent | `features/agents/AgentsPage.test.tsx` |
| Idem si le rôle ne peut pas être déterminé | `recupererAgentCourant` rejette -> même message de droits, `listerAgents` non appelé | `features/agents/AgentsPage.test.tsx` |
| Pas de régression pour les rôles autorisés | `it.each` des 3 rôles : `listerAgents` résolu avec 1 agent -> matricule affiché, `listerAgents` appelé 1 fois | `features/agents/AgentsPage.test.tsx` |
| Messages distincts 401/403/autre | rôle autorisé + `listerAgents` rejette `ErreurApiAgents(403)` -> droits insuffisants ; `(401)` -> "Session expirée ou invalide, reconnectez-vous." ; `(500)` -> "Impossible de charger les agents." ; dans les trois cas absence de `jeton absent ou expiré` | `features/agents/AgentsPage.test.tsx` |
| Contrat d'erreur de création préservé | `creerAgent` rejette `new ErreurApiAgents(409, 'Matricule déjà utilisé')` -> alerte affichant ce message | `features/agents/AgentsPage.test.tsx` |
| Non-régression `App` | Suite `App.test.tsx` existante inchangée et verte (aucun test n'y clique sur "Agents") | `app/App.test.tsx` (exécution) |
| Comportement visuel bout en bout (session réelle AGENT vs CHEF_POSTE) | Manuel : se connecter en AGENT (pas d'entrée "Agents"), puis en CHEF_POSTE (entrée présente, page fonctionnelle) | manuel |

## Écarts identifiés

1. **Note "conflits #65/#71/#72" du design obsolète** : ces PRs sont déjà dans la branche. `AgentShell.tsx` contient déjà les gardes `peutVoirVueMultiPoste`, `peutConsulterAudit`, `peutGererReferentiel` ; le nouvel import s'insère à côté. Aucune revérification `git fetch` requise.
2. **Vérification `App.test.tsx`** : le fichier ne contient aucune occurrence de "Agents"/"agents" (seuls `recupererAgentCourant` et un cas AUDITEUR l.88). Aucun test existant ne clique sur "Agents" avec un rôle AGENT. `AgentShell.test.tsx` non plus (le rôle par défaut du mock est `AGENT`, et "Agents" n'y est jamais cliqué).
3. **Précision sur `creerAgent` (design ambigu)** : le design dit de "conserver `erreur?.message`" tout en distinguant 401/403. Tranché dans la spec : `ErreurApiAgents` porte le message backend ; `messagePourErreur(..., utiliserMessage = true)` affiche les messages 401/403 normalisés, et le message backend pour les autres statuts (400/409...).
4. **Signature de `ErreurApiAgents`** : le design la dit "calquée sur `ErreurApiAudit`", dont le constructeur ne prend que `(statut)`. Ici le constructeur prend en plus un `message` optionnel, nécessaire pour préserver le message backend de `creerAgent`.
5. **Appel `/api/v1/postes` sans en-tête Authorization** (existant) : un échec y produit "Impossible de charger les postes." et peut écraser l'erreur de la liste. Non traité (hors périmètre), signalé pour information.
6. **`dashboardApi.recupererAgentCourant`** lève une `Error` sans statut : un 401 sur `/agents/moi` est donc traité comme `refuse` (message de droits, pas de session invalide) dans `AgentsPage`. Conforme au défaut fermé du design, mais un utilisateur à session expirée verra "Droits insuffisants" sur ce chemin précis ; le cas est couvert par le ticket #60, hors périmètre.
