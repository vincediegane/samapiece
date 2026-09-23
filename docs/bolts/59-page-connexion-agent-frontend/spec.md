# Spec — #59 Page de connexion agent (frontend)

## Résumé

Ajout d'un module `frontend/src/features/auth/` (API, session, formulaire, rafraîchissement proactif) et câblage dans `App.tsx`/`AgentShell.tsx` pour qu'un agent puisse réellement s'authentifier via `POST /api/v1/auth/login`, rester connecté grâce à `POST /api/v1/auth/refresh`, et se déconnecter proprement.

## Contrat backend (lu dans le code, ne pas dévier)

Toutes les routes sont relatives (pas de host en dur), cohérent avec `piecesApi.ts`/`dashboardApi.ts`/`agentsApi.ts`. Aucune modification backend.

### `POST /api/v1/auth/login` — `permitAll()` (`SecurityConfig.java:77`), pas de header `Authorization`

Requête (`LoginRequest`) :
```json
{ "matricule": "string (non vide)", "motDePasse": "string (non vide)" }
```

Réponse `200 OK` (`LoginResponse`) :
```json
{
  "accessToken": "string (JWT)",
  "refreshToken": "string (JWT)",
  "expiresIn": 900,
  "role": "string (ex. AGENT, SUPERVISEUR)",
  "nom": "string"
}
```
`expiresIn` est en **secondes** (`ACCESS_TOKEN_TTL` = 15 min côté backend, `JwtService.java:26`).

Réponse `401 UNAUTHORIZED` (identifiants invalides, agent inconnu, agent inactif) — body (`AuthExceptionHandler.ErreurReponse`) :
```json
{ "code": "IDENTIFIANTS_INVALIDES", "message": "Matricule ou mot de passe invalide." }
```

Réponse `423 LOCKED` (compte verrouillé après 5 échecs, `SEUIL_ECHECS = 5`, verrouillage 15 min) :
```json
{ "code": "COMPTE_VERROUILLE", "message": "Compte temporairement verrouillé." }
```

`400 BAD REQUEST` (validation Bean Validation, `matricule`/`motDePasse` vides) : **non couvert** par `AuthExceptionHandler`, tombe dans le gestionnaire d'erreurs par défaut de Spring Boot — body sans champ `code` exploitable. Ce cas ne doit normalement pas être atteint car `LoginPage` valide côté client avant tout appel réseau ; le frontend doit néanmoins avoir un fallback générique (cf. `AuthApiError` ci-dessous) qui ne suppose pas la présence de `code`/`message` pour un statut autre que 401/423.

### `POST /api/v1/auth/refresh` — `permitAll()`

Requête (`RefreshRequest`) :
```json
{ "refreshToken": "string (non vide)" }
```

Réponse `200 OK` (`RefreshResponse`) — **ne renvoie pas de nouveau `refreshToken`**, seulement un nouvel access token :
```json
{ "accessToken": "string (JWT)", "expiresIn": 900 }
```

Réponse `401 UNAUTHORIZED` — même forme que login, **même message fixe** (le handler ne réutilise pas le message métier réel, il est codé en dur) :
```json
{ "code": "IDENTIFIANTS_INVALIDES", "message": "Matricule ou mot de passe invalide." }
```
Ce cas couvre : refresh token malformé/signature invalide/expiré, type de token incorrect (un access token passé à `/refresh`), agent supprimé/désactivé. Le message affiché à l'utilisateur sera donc littéralement "Matricule ou mot de passe invalide." même si la cause réelle est un refresh token expiré — accepté tel quel (contrat backend non modifiable dans ce ticket).

Réponse `423 LOCKED` — si le compte a été verrouillé entre-temps, même body que login.

## Tâches

- [ ] `frontend/src/features/auth/types.ts` — types miroir des DTOs backend :
  ```ts
  export interface LoginResult {
    accessToken: string;
    refreshToken: string;
    expiresIn: number;
    role: string;
    nom: string;
  }
  export interface RefreshResult {
    accessToken: string;
    expiresIn: number;
  }
  export type AuthErrorCode = 'IDENTIFIANTS_INVALIDES' | 'COMPTE_VERROUILLE' | 'INCONNU';
  ```

- [ ] `frontend/src/features/auth/authApi.ts` — `login`, `refresh`, `AuthApiError`, sur le modèle de `PieceApiError` (`frontend/src/features/pieces/piecesApi.ts:5-13`). Pas de header `Authorization` (routes `permitAll`). Signatures exactes :
  ```ts
  export class AuthApiError extends Error {
    constructor(
      message: string,
      public readonly code: AuthErrorCode,
      public readonly status: number,
    );
  }

  export async function login(matricule: string, motDePasse: string): Promise<LoginResult>;
  export async function refresh(refreshToken: string): Promise<RefreshResult>;
  ```
  Comportement requis :
  - `BASE_URL = '/api/v1/auth'` ; `login` → `POST ${BASE_URL}/login` avec body `{ matricule, motDePasse }` ; `refresh` → `POST ${BASE_URL}/refresh` avec body `{ refreshToken }`. Toujours `headers: { 'Content-Type': 'application/json' }`.
  - Si `!reponse.ok` et `status` est `401` ou `423` : lire le JSON (`{ code, message }`) et lever `new AuthApiError(corps.message, corps.code as AuthErrorCode, status)` — réutiliser tel quel le `message` renvoyé par le backend (ne pas le redéfinir côté frontend, cf. contrat ci-dessus).
  - Si `!reponse.ok` pour tout autre statut (ex. `400`, `500`) : lever `new AuthApiError('Une erreur est survenue, réessayez.', 'INCONNU', status)` **sans tenter de parser le JSON** (le body peut ne pas avoir la forme `{code, message}`, cf. cas 400 ci-dessus).
  - Ne jamais logger `motDePasse` (pas de `console.log`, pas de warning contenant le payload de la requête).

- [ ] `frontend/src/features/auth/session.ts` — clés `localStorage` : `samapiece.accessToken` (conservée, déjà lue par `piecesApi.ts`, `agentsApi.ts`, `dashboardApi.ts` — ne pas renommer), `samapiece.refreshToken`, `samapiece.accessTokenExpiresAt` (chaîne représentant un timestamp epoch ms). Signatures exactes :
  ```ts
  export function enregistrerSession(resultat: LoginResult): void;
  export function enregistrerAccessToken(resultat: RefreshResult): void;
  export function viderSession(): void;
  export function estSessionValide(): boolean;
  export function lireRefreshToken(): string | null;
  export function msAvantExpiration(): number | null;
  ```
  Comportement requis :
  - `enregistrerSession` écrit les 3 clés (`accessToken`, `refreshToken`, `accessTokenExpiresAt = String(Date.now() + resultat.expiresIn * 1000)`). Appelée uniquement après un `login` réussi.
  - `enregistrerAccessToken` **ne touche qu'à** `accessToken` et `accessTokenExpiresAt` (même calcul d'expiration) ; ne modifie **pas** `refreshToken` (le backend n'en renvoie pas de nouveau sur `/refresh`). Appelée après un `refresh` réussi.
  - `viderSession` supprime les 3 clés (`removeItem` sur chacune, idempotent si déjà absentes).
  - `estSessionValide()` retourne `true` si et seulement si `samapiece.accessToken` est présent (non vide) **et** `samapiece.accessTokenExpiresAt` est présent, parseable en nombre, et `Date.now() < expiresAt`. Sinon `false` (y compris si une des clés manque ou si `accessTokenExpiresAt` n'est pas un nombre valide).
  - `lireRefreshToken()` retourne la valeur de `samapiece.refreshToken` ou `null` si absente/vide.
  - `msAvantExpiration()` retourne `expiresAt - Date.now()` (peut être négatif si déjà expiré) ou `null` si `samapiece.accessTokenExpiresAt` est absent/non parseable.

- [ ] `frontend/src/features/auth/useRafraichissementSession.ts` — hook appelé une seule fois depuis `AgentShell`. Signature exacte :
  ```ts
  interface UseRafraichissementSessionOptions {
    onSessionExpiree: () => void;
  }
  export const MARGE_RAFRAICHISSEMENT_MS = 30_000;
  export function calculerDelaiRafraichissement(msAvantExpiration: number): number;
  export function useRafraichissementSession(options: UseRafraichissementSessionOptions): void;
  ```
  `calculerDelaiRafraichissement` est une **fonction pure exportée** (pour test unitaire sans timer) : `Math.max(msAvantExpiration - MARGE_RAFRAICHISSEMENT_MS, 0)`.

  Comportement du hook (implémenté avec `useEffect(() => { ... }, [])`, un seul montage tant qu'`AgentShell` reste monté) :
  1. `planifier()` : lit `lireRefreshToken()` — si `null`, appelle `viderSession()` puis `onSessionExpiree()` et s'arrête (pas de timer). Sinon lit `msAvantExpiration()` — si `null`, même traitement (session invalide). Sinon calcule `delai = calculerDelaiRafraichissement(msAvantExpiration)` et programme `window.setTimeout(executerRafraichissement, delai)` ; conserve l'id pour le nettoyage.
  2. `executerRafraichissement()` (async) : relit `lireRefreshToken()` (peut avoir changé) — si `null`, `viderSession()` + `onSessionExpiree()`. Sinon appelle `refresh(refreshToken)` :
     - succès → `enregistrerAccessToken(resultat)` puis rappelle `planifier()` pour reprogrammer le prochain rafraîchissement avec la nouvelle expiration.
     - échec (`AuthApiError` ou erreur réseau) → `viderSession()` puis `onSessionExpiree()`.
  3. Le nettoyage de l'effet (`return () => window.clearTimeout(idCourant)`) annule le timer en attente au démontage d'`AgentShell`.
  - Ne jamais rappeler `planifier()` après un `onSessionExpiree()` (pas de reprogrammation après abandon de la session).

- [ ] `frontend/src/features/auth/LoginPage.tsx` — formulaire matricule/mot de passe. Props exactes :
  ```ts
  interface LoginPageProps {
    onConnexionReussie: () => void;
  }
  ```
  Structure et classes Tailwind (réutiliser celles d'`EnregistrementPiecePage.tsx` et d'`index.css`, ne pas en inventer de nouvelles) :
  - Conteneur plein écran centré : `<main className="flex min-h-screen items-center justify-center bg-slate-50 px-4">`.
  - Carte : `<div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-7 shadow-sm">` contenant `<h1 className="page-title">Connexion agent</h1>`.
  - Si `erreurServeur` non nul : `<p role="alert" className="alert-error">{erreurServeur}</p>` juste sous le titre (même emplacement que dans `EnregistrementPiecePage.tsx:102-106`).
  - `<form onSubmit={...} className="flex flex-col gap-5">` avec deux champs :
    - `<label className="field"><span className="field-label">Matricule</span><input type="text" className="field-input" value={matricule} onChange={...} /></label>`, erreur associée en `<span role="alert" className="field-error -mt-3">Le matricule est requis.</span>` si vide à la soumission.
    - `<label className="field"><span className="field-label">Mot de passe</span><input type="password" className="field-input" value={motDePasse} onChange={...} /></label>`, erreur `Le mot de passe est requis.` selon le même pattern.
  - Bouton : `<button type="submit" className="btn-primary self-start" disabled={enEnvoi}>{enEnvoi ? 'Connexion…' : 'Se connecter'}</button>`.
  - Validation client (bloquante, avant tout appel réseau) : `matricule.trim()` et `motDePasse` (ne **pas** trimmer le mot de passe) non vides — même pattern `validerFormulaire`/`erreursValidation` que `EnregistrementPiecePage.tsx:35-43`.
  - Soumission : `evenement.preventDefault()`, réinitialise `erreurServeur` à `null`, valide, si erreurs → `return` (pas d'appel `login`). Sinon `setEnEnvoi(true)`, `try { const resultat = await login(matricule.trim(), motDePasse); enregistrerSession(resultat); onConnexionReussie(); } catch (e) { setErreurServeur(e instanceof AuthApiError ? e.message : 'Une erreur est survenue, réessayez.'); } finally { setEnEnvoi(false); }`.

- [ ] `frontend/src/app/App.tsx` — diff attendu :
  - Import `LoginPage` depuis `'../features/auth/LoginPage'` et `estSessionValide` depuis `'../features/auth/session'`.
  - `type Onglet = 'accueil' | 'recherche' | 'connexion' | OngletAgent;` (ajout de `'connexion'`).
  - Ne **pas** ajouter `'connexion'` à `ONGLETS_AGENT` (reste `['pieces', 'dashboard', 'agents']`, inchangé) — `'connexion'` n'est pas un onglet de l'espace agent.
  - Nouvelle fonction locale (remplace les callbacks inline `() => setOnglet('pieces')` actuellement passés à `PublicHeader.onEspaceAgent` et aux deux `HomePage.onEspaceAgent`, lignes 42 et 47 du fichier actuel) :
    ```ts
    function irVersEspaceAgent() {
      setOnglet(estSessionValide() ? 'pieces' : 'connexion');
    }
    ```
    Passer `onEspaceAgent={irVersEspaceAgent}` aux deux endroits (`PublicHeader` et `HomePage`), au lieu de `() => setOnglet('pieces')`.
  - Nouvelle branche de rendu, insérée **avant** le `if (estOngletAgent(onglet))` existant :
    ```ts
    if (onglet === 'connexion') {
      return <LoginPage onConnexionReussie={() => setOnglet('pieces')} />;
    }
    ```
  - Sur `<AgentShell>`, ajouter le prop `onDeconnexion={() => setOnglet('connexion')}` (en plus d'`actif`, `onNaviguer`, `onRetourPublic` déjà présents) — `onRetourPublic` reste `() => setOnglet('accueil')`, **inchangé**.
  - Ne pas modifier `PublicHeader` (son prop `page` reste typé `'accueil' | 'recherche'`, la vue `connexion` n'y transite jamais).

- [ ] `frontend/src/shared/layout/AgentShell.tsx` — diff attendu :
  - Imports ajoutés : `import { viderSession } from '../../features/auth/session';` et `import { useRafraichissementSession } from '../../features/auth/useRafraichissementSession';`.
  - `AgentShellProps` : ajout de `onDeconnexion: () => void;` (distinct d'`onRetourPublic`, qui garde son comportement actuel — clic sur le logo « SamaPièce », `AgentShell.tsx:61-68`, inchangé).
  - Signature du composant : `function AgentShell({ actif, onNaviguer, onRetourPublic, onDeconnexion, children }: AgentShellProps)`.
  - Remplacer `seDeconnecter` (actuellement `AgentShell.tsx:53-56`, `window.localStorage.removeItem('samapiece.accessToken'); onRetourPublic();`) par :
    ```ts
    function seDeconnecter() {
      viderSession();
      onDeconnexion();
    }
    ```
    Le bouton de déconnexion existant (`AgentShell.tsx:126-133`, `onClick={seDeconnecter}`) n'est pas modifié, seule l'implémentation de `seDeconnecter` change.
  - Ajout de l'appel au hook dans le corps du composant, à côté des deux `useEffect` existants (recupération de l'agent courant et polling de la file offline, `AgentShell.tsx:29-51`) :
    ```ts
    useRafraichissementSession({ onSessionExpiree: onDeconnexion });
    ```
    Ne pas appeler `viderSession()` explicitement ici : le hook s'en charge lui-même avant d'invoquer `onSessionExpiree` (cf. tâche `useRafraichissementSession.ts` ci-dessus).
  - Aucune autre modification (le polling de la file offline toutes les 5s, `recupererAgentCourant`, le rendu de la sidebar restent identiques).

- [ ] `frontend/src/features/auth/LoginPage.test.tsx` — nouveau test, conventions `EnregistrementPiecePage.test.tsx` (Vitest, `@testing-library/react`, `userEvent`, `vi.stubGlobal('fetch', vi.fn())` en `beforeEach`).

- [ ] `frontend/src/features/auth/session.test.ts` — nouveau test unitaire pur (pas de rendu React), conventions `frontend/src/shared/offline/fileSynchronisation.test.ts`.

- [ ] `frontend/src/features/auth/useRafraichissementSession.test.ts` — nouveau test, au minimum sur `calculerDelaiRafraichissement` (fonction pure, pas de timer réel à attendre).

- [ ] `frontend/src/app/App.test.tsx` — nouveau test (n'existe pas encore) couvrant le câblage `onEspaceAgent`/session/`onDeconnexion`.

- [ ] `frontend/src/shared/layout/AgentShell.test.tsx` — nouveau test (n'existe pas encore) couvrant `seDeconnecter`.

## Plan de tests

| Critère d'acceptation (ticket #59) | Test |
|---|---|
| Écran de connexion affiché quand aucun jeton valide n'est présent, accessible depuis « Espace agent » | `App.test.tsx` : sans `samapiece.accessToken`/`samapiece.accessTokenExpiresAt` en `localStorage`, clic sur le bouton « Espace agent » du header public → les champs « Matricule »/« Mot de passe » de `LoginPage` sont affichés (`estSessionValide()` retourne `false`). Cas symétrique : avec `samapiece.accessToken` + `samapiece.accessTokenExpiresAt` valides (futur) en `localStorage`, clic sur « Espace agent » → l'espace agent (`EnregistrementPiecePage`, onglet `pieces`) s'affiche directement, pas `LoginPage`. `LoginPage.test.tsx` : rendu isolé affiche bien les 2 champs et le bouton « Se connecter ». |
| Appelle `POST /api/v1/auth/login`, stocke `accessToken`/`refreshToken`, gère l'expiration (rafraîchissement via `/refresh` ou nouvelle demande de connexion) | `LoginPage.test.tsx` : soumission avec identifiants valides → `fetch` appelé en `POST` sur `/api/v1/auth/login` avec `body` JSON `{ matricule, motDePasse }` ; après réponse `200` mockée, `localStorage.getItem('samapiece.accessToken')`, `('samapiece.refreshToken')` et `('samapiece.accessTokenExpiresAt')` sont renseignés, et `onConnexionReussie` est appelé. `session.test.ts` : `enregistrerSession` écrit les 3 clés avec le bon calcul d'expiration (`vi.setSystemTime` pour figer `Date.now()`) ; `enregistrerAccessToken` ne modifie pas `refreshToken` existant ; `estSessionValide` retourne `true`/`false` selon présence et fraîcheur ; `msAvantExpiration`/`lireRefreshToken` couverts par des cas positifs et négatifs (clés absentes) ; `viderSession` supprime bien les 3 clés. `useRafraichissementSession.test.ts` : `calculerDelaiRafraichissement(msAvantExpiration)` retourne `msAvantExpiration - MARGE_RAFRAICHISSEMENT_MS` quand positif, et `0` quand `msAvantExpiration <= MARGE_RAFRAICHISSEMENT_MS` (y compris valeur négative, session déjà expirée). |
| Messages d'erreur clairs sur identifiants invalides et compte verrouillé (l'API distingue déjà ces cas) | `LoginPage.test.tsx` : `fetch` mocké renvoyant `{ ok: false, status: 401, json: async () => ({ code: 'IDENTIFIANTS_INVALIDES', message: 'Matricule ou mot de passe invalide.' }) }` → message `Matricule ou mot de passe invalide.` affiché via `role="alert"`. Second test avec `{ ok: false, status: 423, json: async () => ({ code: 'COMPTE_VERROUILLE', message: 'Compte temporairement verrouillé.' }) }` → message `Compte temporairement verrouillé.` affiché, et assertion que les deux messages diffèrent entre les deux tests. Un test réseau (`fetch` qui rejette) vérifie le message générique `Une erreur est survenue, réessayez.`. |
| Le bouton de déconnexion existant (sidebar agent) ramène bien à cet écran après avoir vidé le jeton | `AgentShell.test.tsx` : `localStorage` pré-rempli avec `samapiece.accessToken`/`refreshToken`/`accessTokenExpiresAt`, mock de `recupererAgentCourant` (`vi.mock('../../features/dashboard/dashboardApi', ...)`, pattern déjà utilisé pour `fileSynchronisation` dans `EnregistrementPiecePage.test.tsx`) résolvant un agent factice pour afficher le bouton de déconnexion ; clic sur le bouton (`title="Déconnexion"`) → `onDeconnexion` (prop mocké `vi.fn()`) est appelé, et les 3 clés `localStorage` sont supprimées. `App.test.tsx` (test d'intégration complémentaire, optionnel mais recommandé) : après un clic simulé sur le bouton de déconnexion dans l'espace agent rendu par `App`, l'écran affiché redevient `LoginPage`. |

## Écarts identifiés

- Le design mentionne que `authApi.ts` s'inspire de `PieceApiError` « (message + `code`) », mais ne précise pas si le champ `message` de l'erreur doit être le texte brut renvoyé par le backend ou un texte redéfini côté frontend. Cette spec tranche : **réutiliser tel quel** le `message` du body JSON backend (déjà rédigé pour un utilisateur final dans `AuthExceptionHandler.java`), plutôt que de dupliquer des chaînes côté frontend — évite une désynchronisation future si le message backend change.
- Le design ne précise pas explicitement que `RefreshResponse` ne renvoie **pas** de nouveau `refreshToken` (contrairement à `LoginResponse`). C'est vérifié dans `backend/src/main/java/sn/samapiece/iam/web/RefreshResponse.java` : cette spec introduit donc `enregistrerAccessToken` comme fonction distincte d'`enregistrerSession` pour éviter que le codeur n'écrase par erreur `samapiece.refreshToken` avec `undefined`/vide après un rafraîchissement.
- Le design ne dit pas si la vue `'connexion'` doit conserver le `PublicHeader` (permettant de revenir à l'accueil) ou être une page autonome. Cette spec tranche pour une page autonome (branche de rendu dédiée dans `App.tsx`, avant la branche agent), cohérent avec la contrainte de ne pas modifier le typage `page` de `PublicHeaderProps` (`'accueil' | 'recherche'`) et de rester dans le budget minimal du ticket. Pas de bouton « retour à l'accueil » sur `LoginPage` — absent des critères d'acceptation, considéré hors périmètre.
- Aucun autre écart entre `design.md` et les critères d'acceptation du ticket : les 4 critères sont couverts par les tâches ci-dessus.
