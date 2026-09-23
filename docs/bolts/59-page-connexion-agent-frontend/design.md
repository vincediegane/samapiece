# Design — #59 Page de connexion agent (frontend)

## Approche

Le frontend n'a pas de librairie de routing (`App.tsx` gère la navigation via un `useState` de "vue" et un rendu conditionnel) ; on reste dans ce pattern plutôt que d'introduire `react-router` pour une seule nouvelle vue. On ajoute une vue `connexion` (nouveau module `frontend/src/features/auth/`) avec un formulaire matricule/mot de passe qui appelle `POST /api/v1/auth/login`, stocke `accessToken`/`refreshToken`/date d'expiration dans `localStorage`, puis bascule vers l'espace agent existant. Le bouton "Espace agent" (accueil, header, footer) et le bouton de déconnexion (`AgentShell`) sont branchés sur cette nouvelle vue plutôt que sur l'accueil public. Le rafraîchissement de l'access token est géré de façon proactive (timer basé sur `expiresIn`) dans `AgentShell`, qui est déjà le point central de toutes les pages agent (il y fait déjà du polling toutes les 5s pour la file offline) plutôt que d'ajouter un intercepteur `fetch` global partagé par `piecesApi.ts`/`agentsApi.ts`/`dashboardApi.ts`, ce qui éviterait de toucher ces fichiers et leurs tests existants mais laisse un angle mort si le token expire hors du cycle de refresh (cf. Risques).

## Fichiers/modules impactés

Module `frontend/src/features/auth/` n'existe pas encore — à créer :
- `frontend/src/features/auth/authApi.ts` — `login(matricule, motDePasse)` → `POST /api/v1/auth/login`, `refresh(refreshToken)` → `POST /api/v1/auth/refresh` ; classe `AuthApiError` (message + `code: 'IDENTIFIANTS_INVALIDES' | 'COMPTE_VERROUILLE' | 'INCONNU'`), sur le modèle de `PieceApiError` dans `frontend/src/features/pieces/piecesApi.ts`.
- `frontend/src/features/auth/session.ts` — helpers `enregistrerSession(reponse)`, `viderSession()`, `estSessionValide()`, `lireRefreshToken()`, `msAvantExpiration()` ; clé existante `samapiece.accessToken` conservée telle quelle (déjà lue par `dashboardApi.ts`, `agentsApi.ts`, `piecesApi.ts`), plus nouvelles clés `samapiece.refreshToken` et `samapiece.accessTokenExpiresAt`.
- `frontend/src/features/auth/types.ts` — types miroir de `LoginResponse`/`RefreshResponse` backend.
- `frontend/src/features/auth/LoginPage.tsx` — formulaire matricule/mot de passe, styles Tailwind cohérents avec `EnregistrementPiecePage.tsx` (classes `field`, `field-label`, `field-input`, `field-error`, `alert-error`, `public-cta`).
- `frontend/src/features/auth/useRafraichissementSession.ts` — hook appelé depuis `AgentShell` : programme un `setTimeout` avant expiration, appelle `refresh()`, et déclenche `onSessionExpiree()` si le refresh échoue.
- Tests associés (à la charge du codeur/spec-writer) : `LoginPage.test.tsx`, `session.test.ts`.

Fichiers existants à modifier :
- `frontend/src/app/App.tsx` — ajout de la vue `'connexion'` au type `Onglet`, rendu de `LoginPage`, logique « si session valide alors espace agent, sinon connexion » sur `onEspaceAgent`, câblage du nouveau `onDeconnexion` d'`AgentShell`.
- `frontend/src/shared/layout/AgentShell.tsx` — `seDeconnecter` utilise `viderSession()` (au lieu de `localStorage.removeItem` inline) et appelle un nouveau prop `onDeconnexion` (distinct d'`onRetourPublic`, qui reste le comportement du logo « SamaPièce » vers l'accueil public et ne doit pas changer) ; intégration du hook `useRafraichissementSession`.

Fichiers **non modifiés** (volontairement) : `frontend/src/features/pieces/piecesApi.ts`, `frontend/src/features/agents/agentsApi.ts`, `frontend/src/features/dashboard/dashboardApi.ts`, `EnregistrementPiecePage.tsx`, `DashboardPage.tsx`, `AgentsPage.tsx` — ils continuent de lire `samapiece.accessToken` directement, comme aujourd'hui ; leurs tests (`EnregistrementPiecePage.test.tsx`, `DashboardPage.test.tsx`) qui seedent ce localStorage directement restent valides sans changement.

Backend : aucun fichier impacté (`AuthController`, `AuthService`, `AuthExceptionHandler`, `LoginRequest`/`LoginResponse`/`RefreshRequest`/`RefreshResponse` déjà en place et fonctionnels sur `main`, contrat vérifié dans `backend/src/main/java/sn/samapiece/iam/`).

## Décisions clés

- **Pas de nouvelle lib de routing** : réutilisation du pattern `useState<Onglet>` déjà en place dans `App.tsx`, cohérent avec le reste du frontend (aucune dépendance routing dans `package.json`).
- **Chemins API** : `authApi.ts` utilise les chemins relatifs `/api/v1/auth/login` et `/api/v1/auth/refresh`, comme `piecesApi.ts`/`agentsApi.ts`/`dashboardApi.ts` (`BASE_URL` relatif, pas de host codé en dur) — cohérent avec la convention existante, indépendant du proxy `/api/*` du ticket #57.
- **Nouvelles clés localStorage** : en plus de `samapiece.accessToken` (conservée), ajout de `samapiece.refreshToken` et `samapiece.accessTokenExpiresAt` (timestamp epoch ms calculé côté client à partir d'`expiresIn`), pour piloter le rafraîchissement proactif sans décoder le JWT côté frontend.
- **Rafraîchissement proactif plutôt qu'intercepteur global** : le hook `useRafraichissementSession` vit dans `AgentShell` (déjà le point d'entrée commun à `pieces`/`dashboard`/`agents`) et programme le `refresh` avant expiration. Alternative écartée : intercepteur `fetch` global réagissant à tout 401 — plus robuste mais implique de retoucher `piecesApi.ts`/`agentsApi.ts`/`dashboardApi.ts` et leurs tests, hors budget de ce ticket (voir Hors périmètre).
- **Déconnexion vs logo** : nouveau prop `onDeconnexion` distinct d'`onRetourPublic` sur `AgentShellProps`, pour que le bouton de déconnexion (critère d'acceptation explicite) ramène à l'écran de connexion, sans changer le comportement actuel du clic sur le logo « SamaPièce » (retour accueil public, session non vidée).
- **Messages d'erreur** : mapping direct des codes JSON déjà renvoyés par `AuthExceptionHandler` (`IDENTIFIANTS_INVALIDES` → 401, `COMPTE_VERROUILLE` → 423) vers deux messages distincts dans `LoginPage`, via `AuthApiError.code` — plus précis que le pattern générique `Erreur ${status}` utilisé par `agentsApi.ts`/`dashboardApi.ts`.
- **Garde à l'entrée uniquement** : la validité de session n'est vérifiée qu'au clic sur « Espace agent » et par le hook de rafraîchissement — pas de vérification systématique avant chaque rendu de page agent (accepté comme limite, cf. Risques).

## Risques / points d'attention

- **Pas d'intercepteur 401 global** : si le token expire en dehors du cycle du hook (onglet en arrière-plan où les timers navigateur sont throttled, refresh token lui-même expiré/invalide en dehors d'une tentative de refresh), `dashboardApi.ts`/`agentsApi.ts` continueront de lever une erreur générique `Erreur 401` sans rediriger automatiquement vers l'écran de connexion — seul le clic explicite sur « Déconnexion » garantit le retour à l'écran de connexion. À documenter comme limite connue plutôt qu'à corriger ici.
- **Tickets connexes non mergés** : #57 (proxy `/api/*`) et #58 (`doitChangerMotDePasse` / 403 forcé) sont en PR, pas sur `main`. Le design n'en dépend pas (chemins relatifs déjà la convention, pas de gestion du 403 de #58) — mais si #58 merge après ce ticket, un suivi sera nécessaire pour que `LoginPage` gère une réponse 403 spécifique post-connexion (non construit ici, cf. Hors périmètre).
- **Offline-first** : une connexion initiale (ou un refresh) nécessite le réseau ; un agent ouvrant l'app hors-ligne sans jeton valide en cache verra l'écran de connexion bloqué sans pouvoir s'authentifier. C'est un comportement attendu (pas de session hors-ligne possible), distinct du mode offline existant qui ne concerne que la mise en file des pièces déjà authentifiées (`fileSynchronisation.ts`).
- **Minimisation des données** : ne jamais logger le mot de passe (pas de `console.log`/Sentry avec le champ `motDePasse`), transmission uniquement en corps de requête POST — point à vérifier en revue de code.
- **Dérive d'horloge** : `estSessionValide()` et le hook de refresh se basent sur `Date.now()` côté client comparé à `accessTokenExpiresAt` calculé au login ; pas de correction de décalage horloge client/serveur prévue (accepté, cohérent avec la simplicité du reste du frontend).

## Hors périmètre

- Ajout d'une librairie de routing (`react-router` ou équivalent).
- Intercepteur `fetch` global partagé par toutes les API (`piecesApi.ts`, `agentsApi.ts`, `dashboardApi.ts`) réagissant automatiquement à tout 401 en cours de session.
- Gestion du flag `doitChangerMotDePasse` / réponse 403 de renouvellement forcé (ticket #58, pas mergé).
- Mise en place ou modification du proxy `/api/*` (ticket #57, pas mergé, déjà traité ailleurs).
- Toute modification backend (`AuthController`, `AuthService`, migrations Flyway) — le contrat `/api/v1/auth/login` et `/refresh` est déjà fonctionnel sur `main`.
- « Se souvenir de moi », persistance de session différenciée, synchronisation de session entre onglets (BroadcastChannel, etc.).
