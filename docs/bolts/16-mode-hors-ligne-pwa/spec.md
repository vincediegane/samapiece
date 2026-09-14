# Spec — Ticket #16 : Mode hors-ligne (PWA offline-first) pour le formulaire agent

## Résumé

Le formulaire `EnregistrementPiecePage` devient installable en PWA (service worker précachant l'app shell) et, en cas d'échec réseau à la soumission, met la fiche en file locale IndexedDB (`frontend/src/shared/offline/`) affichée sous le formulaire, synchronisée automatiquement au retour de connexion avec retry/backoff borné et distinction 401 (session expirée) / 409 (conflit doublon générique, sans détail candidats) / échec réseau.

## Décisions de fermeture des points ouverts du design

Ces décisions sont actées et ne doivent pas être rouvertes par le codeur.

1. **Point ouvert #1 (dépendance #15)** : déjà tranché par l'utilisateur avant cette spec — #16 reste indépendant de #15. Le 409 est traité de façon générique et terminale (statut `conflit_doublon`), sans lecture du corps JSON, sans `numerosFicheCandidats`, sans `confirmerMalgreDoublon`. Raffinement futur, hors périmètre ici.
2. **Point ouvert #2 (paliers de backoff)** : fixé à **4 tentatives réseau au total** (1 tentative initiale + 3 relances automatiques), avec délai avant chaque relance suivant les paliers **15 s → 1 min → 5 min**. Après la 4ᵉ tentative en échec, statut `echec_definitif`. C'est un ajustement mineur par rapport à la proposition de l'architecte (« 3 tentatives, paliers 15s/1min/5min ») : avec 3 tentatives il n'y aurait que 2 paliers utilisables, ce qui gaspillait le palier 5 min ; avec 4 tentatives, les 3 paliers proposés sont tous utilisés, et l'analogie avec `SmsRabbitConfig` (3 paliers puis dead-letter, en plus de l'envoi initial) est respectée à l'identique. Constantes fixes en dur dans le code (pas de configuration externalisée) : `NOMBRE_MAX_TENTATIVES = 4`, `PALIERS_BACKOFF_MS = [15_000, 60_000, 300_000]`.
3. **Point ouvert #3 (visibilité post-synchronisation)** : les fiches passées au statut `synchronise` sont **retirées immédiatement d'IndexedDB** dès la confirmation du succès, mais restent affichées dans la liste du composant pendant **5 secondes** via un état local du composant (`setTimeout` de filtrage), sans jamais être réécrites en base pour cela. Voir mécanisme détaillé dans « Contrat technique — FileAttenteSynchronisation.tsx ».
4. **Point ouvert #4 (chiffrement)** : **pas de chiffrement de la file IndexedDB dans ce ticket.** Dette documentée explicitement (voir section Risques du design + rappel dans le test manuel) : cohérent avec l'absence de chiffrement client ailleurs dans l'app. Aucune tâche de chiffrement dans cette spec.
5. **Traitement du 409** : distingué **uniquement par `error.status === 409`** sur l'exception levée par `piecesApi.ts` (pas de lecture du corps de réponse, sa forme n'étant pas garantie sur cette branche). Message affiché à l'agent dans la file : *« Conflit détecté (doublon potentiel) — nécessite une vérification manuelle au poste. »* Statut terminal `conflit_doublon`, jamais retenté automatiquement, pas de bouton « Réessayer » pour cet item (relancer produirait le même 409, sans action utile possible sans #15).

## Tâches

- [ ] `frontend/package.json` — ajouter `idb` en dépendance de production (dernière version stable compatible, ex. `^8.x`), `vite-plugin-pwa` et `fake-indexeddb` en devDependencies (dernières versions stables compatibles avec `vite@^5.4.10` / `vitest@^2.1.4`, ex. `vite-plugin-pwa@^0.21.x`, `fake-indexeddb@^6.x` — à ajuster si conflit de peer-deps constaté à l'installation).
- [ ] `frontend/src/shared/offline/types.ts` (nouveau) — types `StatutFicheEnAttente` et `FicheEnAttente` (voir Contrat technique).
- [ ] `frontend/src/shared/offline/db.ts` (nouveau) — ouverture de la base IndexedDB via `idb`/`openDB`, un seul object store `fiches-en-attente` (keyPath `id`) (voir Contrat technique).
- [ ] `frontend/src/shared/offline/fileSynchronisation.ts` (nouveau) — `mettreEnFile`, `listerFile`, `synchroniser`, `reessayerItem`, `reessayerTout`, `demarrerDeclencheurs`, constantes de backoff, verrou en mémoire, fonctions internes `envoyerItem`/`estEligible`/`supprimerDeLaFile` (voir Contrat technique).
- [ ] `frontend/src/shared/offline/fileSynchronisation.test.ts` (nouveau) — voir Plan de tests.
- [ ] `frontend/src/features/pieces/piecesApi.ts` — aucun changement de comportement requis (le statut HTTP est déjà exposé via `PieceApiError.status`, y compris pour 409/5xx via la branche `throw new PieceApiError(...)` générique) ; vérifier seulement, à l'implémentation, que `PieceApiError.status` reste bien accessible et que l'échec réseau pur (pas de `Response`, `fetch` qui rejette avec `TypeError`) n'est **pas** intercepté/enveloppé par `creerPiece` (il doit se propager tel quel, non `PieceApiError`) — c'est déjà le cas dans le code actuel, ne rien changer sauf régression constatée.
- [ ] `frontend/src/features/pieces/EnregistrementPiecePage.tsx` — détection échec réseau vs erreur HTTP, appel `mettreEnFile`, intégration de `<FileAttenteSynchronisation />` (voir Contrat technique).
- [ ] `frontend/src/features/pieces/FileAttenteSynchronisation.tsx` (nouveau) — composant d'affichage de la file (voir Contrat technique).
- [ ] `frontend/src/features/pieces/EnregistrementPiecePage.test.tsx` — adapter les tests existants pour mocker le module offline (voir « Écarts identifiés » et Plan de tests) + ajouter un test de mise en file sur échec réseau.
- [ ] `frontend/vite.config.ts` — ajout du plugin `VitePWA` (voir Contrat technique).
- [ ] `frontend/src/vite-env.d.ts` — ajout de `/// <reference types="vite-plugin-pwa/client" />` (en plus de la ligne existante `vite/client`).
- [ ] `frontend/index.html` — ajout `<meta name="theme-color" content="#0f172a" />` et `<link rel="apple-touch-icon" href="/pwa-192x192.png" />`.
- [ ] `frontend/public/pwa-192x192.png`, `frontend/public/pwa-512x512.png`, `frontend/public/pwa-512x512-maskable.png` (nouveaux, placeholders) — icônes carrées simples (couleur unie + éventuellement initiale « SP »), branding définitif hors périmètre (cf. design, Risques).
- [ ] `frontend/src/main.tsx` — enregistrement du service worker via `virtual:pwa-register` et appel de `demarrerDeclencheurs()` (voir Contrat technique).
- [ ] `docs/bolts/16-mode-hors-ligne-pwa/test-manuel-coupure-reseau.md` (nouveau) — procédure de test manuel (voir Contrat technique, dernière sous-section).

Tâche optionnelle (non requise par les critères d'acceptation, à ne faire que si le temps le permet — ne pas bloquer la review dessus) :
- [ ] `frontend/src/features/pieces/FileAttenteSynchronisation.test.tsx` — test composant léger avec `fileSynchronisation` mocké (pas de vraie IndexedDB), vérifiant l'affichage des statuts et la présence/absence conditionnelle du bouton « Réessayer ».

## Contrat technique

### `frontend/src/shared/offline/types.ts`

```ts
export type StatutFicheEnAttente =
  | 'en_attente'
  | 'en_cours'
  | 'echec_reseau'
  | 'conflit_doublon'
  | 'echec_definitif'
  | 'synchronise';

export interface FicheEnAttente {
  id: string;                          // uuid v4 local, crypto.randomUUID()
  payload: CreerPieceRequest;          // JSON exact produit par EnregistrementPiecePage, sans transformation
  statut: StatutFicheEnAttente;
  tentatives: number;                  // nombre de tentatives réseau déjà effectuées, démarre à 0
  creeLeLocal: string;                 // ISO 8601, horloge du poste client
  derniereErreur: string | null;
  numeroFicheServeur: string | null;   // renseigné une fois synchronisé (PieceResponse.numeroFiche)
  prochaineTentativeAuPlusTotLe: string | null; // ISO 8601 ; date/heure la plus tôt pour la prochaine relance automatique (null = éligible immédiatement, ou statut terminal)
}
```

Note : `prochaineTentativeAuPlusTotLe` est un ajout de cette spec par rapport au modèle du design (nécessaire pour que `synchroniser()`, appelé périodiquement, sache si le délai de backoff d'un item est écoulé, sans dépendre d'un `setTimeout` par item qui ne survivrait pas à un rechargement de page).

### `frontend/src/shared/offline/db.ts`

- `openDB` de `idb`, base nommée `samapiece-offline`, version `1`.
- Un seul object store : `fiches-en-attente`, `keyPath: 'id'`, pas d'index supplémentaire (le volume attendu par poste agent est faible, un scan complet via `getAll()` suffit).
- Export d'une fonction unique `ouvrirBase(): Promise<IDBPDatabase<...>>` réutilisée par toutes les fonctions de `fileSynchronisation.ts` (ne pas ouvrir une connexion différente à chaque appel — `idb`/le navigateur gèrent le multiplexage, mais centraliser l'ouverture simplifie le test et la maintenance).

### `frontend/src/shared/offline/fileSynchronisation.ts`

Constantes exportées :
```ts
export const NOMBRE_MAX_TENTATIVES = 4;
export const PALIERS_BACKOFF_MS: readonly number[] = [15_000, 60_000, 300_000];
export const DUREE_RAFRAICHISSEMENT_MS = 30_000; // minuteur de secours
```

Fonctions publiques :
```ts
export async function mettreEnFile(payload: CreerPieceRequest): Promise<FicheEnAttente>;
export async function listerFile(): Promise<FicheEnAttente[]>; // triée par creeLeLocal croissant (FIFO)
export async function synchroniser(options?: { ignorerDelaiBackoff?: boolean }): Promise<void>;
export async function reessayerItem(id: string): Promise<void>;
export async function reessayerTout(): Promise<void>;
export function demarrerDeclencheurs(): () => void; // retourne une fonction de nettoyage (removeEventListener + clearInterval)
```

Fonctions internes (non exportées, ou exportées uniquement si nécessaire aux tests via un export nommé distinct — à la discrétion du codeur, mais garder la responsabilité claire) :
```ts
async function envoyerItem(item: FicheEnAttente): Promise<void>;
function estEligible(item: FicheEnAttente, maintenant: number, ignorerDelaiBackoff: boolean): boolean;
async function supprimerDeLaFile(id: string): Promise<void>;
```

Comportements exacts à implémenter :

1. **`mettreEnFile(payload)`** : crée un `FicheEnAttente` avec `id = crypto.randomUUID()`, `statut = 'en_attente'`, `tentatives = 0`, `creeLeLocal = new Date().toISOString()`, `derniereErreur = null`, `numeroFicheServeur = null`, `prochaineTentativeAuPlusTotLe = null` ; l'écrit dans le store via `db.put(...)` ; le retourne. N'appelle jamais `synchroniser()` elle-même (laisser les déclencheurs s'en charger, pour ne pas coupler mise en file et tentative immédiate — utile aussi pour les tests qui veulent contrôler le timing).
2. **`listerFile()`** : `db.getAll('fiches-en-attente')`, trié par `creeLeLocal` ascendant.
3. **Verrou en mémoire anti-concurrence** : une variable de module `const idsEnCours = new Set<string>();`. Avant tout appel réseau pour un item, `if (idsEnCours.has(item.id)) return;` puis `idsEnCours.add(item.id)`, et `idsEnCours.delete(item.id)` dans un `finally`. Ce verrou est en mémoire par onglet (limite multi-onglets déjà documentée dans le design, non traitée ici).
4. **`estEligible(item, maintenant, ignorerDelaiBackoff)`** : `true` si `!idsEnCours.has(item.id)` ET (`item.statut === 'en_attente'` OU (`item.statut === 'echec_reseau'` ET (`ignorerDelaiBackoff` OU `item.prochaineTentativeAuPlusTotLe === null` OU `new Date(item.prochaineTentativeAuPlusTotLe).getTime() <= maintenant`))). Les statuts `en_cours`, `conflit_doublon`, `echec_definitif`, `synchronise` ne sont **jamais** repris automatiquement.
5. **`synchroniser(options)`** : liste tous les items, filtre ceux éligibles via `estEligible`, puis pour chacun (séquentiellement, `await` en boucle — pas de `Promise.all`, pour éviter une rafale de requêtes simultanées vers l'API depuis un même poste) : marque `statut = 'en_cours'` (persisté), appelle `envoyerItem(item)`.
6. **`envoyerItem(item)`** — logique de transition, en s'appuyant exclusivement sur `piecesApi.creerPiece` :
   - `item.tentatives += 1` avant l'appel réseau.
   - Appel `await creerPiece(item.payload)`.
   - **Succès** : `item.statut = 'synchronise'`, `item.numeroFicheServeur = reponse.numeroFiche`, `item.derniereErreur = null` ; persister puis **supprimer immédiatement l'enregistrement d'IndexedDB** (`await supprimerDeLaFile(item.id)`). Ne pas laisser un enregistrement `synchronise` traîner en base — la confirmation visuelle de 5 s est portée exclusivement par l'état local du composant (voir plus bas), pas par IndexedDB.
   - **Échec avec `error instanceof PieceApiError`** :
     - `error.status === 409` → `item.statut = 'conflit_doublon'`, `item.derniereErreur = 'Conflit détecté (doublon potentiel) — nécessite une vérification manuelle au poste.'`, `item.prochaineTentativeAuPlusTotLe = null`. Persister. Pas de relance automatique possible (statut exclu de `estEligible`).
     - `error.status === 401` → `item.statut = 'echec_definitif'`, `item.derniereErreur = 'Session expirée : reconnectez-vous puis cliquez sur Réessayer.'`, `item.prochaineTentativeAuPlusTotLe = null`. Persister.
     - `error.status >= 500` (erreur serveur transitoire) → traité **comme un échec réseau** (voir branche ci-dessous), car potentiellement transitoire.
     - Tout autre statut (ex. `400`, `403`, `404`) → `item.statut = 'echec_definitif'`, `item.derniereErreur = \`Erreur du serveur (code ${error.status}) — vérifiez les données ou contactez le support.\``, `item.prochaineTentativeAuPlusTotLe = null`. Persister. (Une erreur 400 signifie des données structurellement invalides ; retenter automatiquement le même payload ne peut pas réussir.)
   - **Échec réseau** (`error` n'est pas une `PieceApiError` — typiquement `TypeError` levée par `fetch`, ou statut serveur `>= 500` traité comme ci-dessus) :
     - Si `item.tentatives >= NOMBRE_MAX_TENTATIVES` → `item.statut = 'echec_definitif'`, `item.derniereErreur = 'Échec définitif après plusieurs tentatives — vérifiez la connexion puis réessayez manuellement.'`, `item.prochaineTentativeAuPlusTotLe = null`.
     - Sinon → `item.statut = 'echec_reseau'`, `item.derniereErreur = 'Échec réseau, nouvelle tentative automatique programmée.'`, `item.prochaineTentativeAuPlusTotLe = new Date(Date.now() + PALIERS_BACKOFF_MS[item.tentatives - 1]).toISOString()`.
     - Persister.
7. **`reessayerItem(id)`** : lit l'item ; si son statut n'est pas `echec_reseau` ou `echec_definitif`, ne rien faire (no-op silencieux). Sinon, réinitialise `tentatives = 0`, `prochaineTentativeAuPlusTotLe = null`, `statut = 'en_attente'`, persiste, puis appelle `envoyerItem` immédiatement sur cet item (sans attendre le prochain tick de `synchroniser`).
8. **`reessayerTout()`** : pour chaque item en `echec_reseau` ou `echec_definitif`, applique la même réinitialisation que `reessayerItem`, puis appelle `synchroniser({ ignorerDelaiBackoff: true })` une seule fois à la fin (pas un appel par item).
9. **`demarrerDeclencheurs()`** : à l'appel, déclenche immédiatement `synchroniser()` une première fois (fire-and-forget, ne pas `await` dans l'appelant), enregistre `window.addEventListener('online', () => { synchroniser({ ignorerDelaiBackoff: true }); })`, démarre `setInterval(() => { synchroniser(); }, DUREE_RAFRAICHISSEMENT_MS)`. Retourne une fonction qui fait `window.removeEventListener('online', ...)` et `clearInterval(...)` (utile pour les tests, pas nécessairement appelée en production puisque l'app ne « démonte » jamais ce listener globalement).

### `frontend/src/features/pieces/EnregistrementPiecePage.tsx`

- Importer `mettreEnFile` depuis `../../shared/offline/fileSynchronisation` et `FileAttenteSynchronisation` (même dossier).
- Dans le `catch` de `soumettreFormulaire`, distinguer deux cas :
  - **Échec réseau** : `e` n'est **pas** une instance de `PieceApiError` (typiquement `TypeError: Failed to fetch`), **ou** `!navigator.onLine` est vrai au moment de l'appel. Dans ce cas : appeler `await mettreEnFile(payload)`, puis traiter comme un succès de mise en file — réinitialiser le formulaire (`setFormulaire(FORMULAIRE_INITIAL)`, `setErreursValidation({})`), et afficher un message non bloquant (pas `role="alert"` d'erreur, plutôt un message informatif du type « Pas de connexion : la fiche a été enregistrée localement, elle sera synchronisée automatiquement. ») via un nouvel état local, par ex. `const [messageMiseEnFile, setMessageMiseEnFile] = useState<string | null>(null)`.
  - **Erreur HTTP classique** (`e instanceof PieceApiError`, réseau disponible) : comportement inchangé — `setErreurServeur(e.message)`, le formulaire n'est pas réinitialisé.
- Ajouter `<FileAttenteSynchronisation />` sous le formulaire (ou sous le bloc reçu), sans condition d'affichage particulière (le composant gère lui-même l'absence d'items en ne rendant rien).
- Ne pas dupliquer la logique de payload : le bloc `const payload: CreerPieceRequest = {...}` existant est réutilisé tel quel pour l'appel à `mettreEnFile`.

### `frontend/src/features/pieces/FileAttenteSynchronisation.tsx`

- Composant sans props, autonome.
- État local : `itemsAffiches: FicheEnAttente[]`.
- `useEffect` au montage : lance un `setInterval` (ex. toutes les 2 s) qui appelle `listerFile()` et met à jour l'état, avec la règle de fusion suivante (nécessaire car `envoyerItem` supprime immédiatement les items `synchronise` d'IndexedDB, cf. point ouvert #3) :
  - Récupérer `enBase = await listerFile()`.
  - Conserver dans le nouvel état tous les items de `enBase`.
  - Pour chaque item présent dans l'état précédent (`itemsAffiches`) avec `statut === 'synchronise'` mais **absent** de `enBase` (car supprimé de la base après succès) : le garder affiché tel quel, sauf si son délai de grâce de 5 s (déclenché au moment où il a été détecté `synchronise` pour la première fois) est écoulé.
  - Concrètement : quand un item passe (ou apparaît déjà) au statut `synchronise`, programmer immédiatement `setTimeout(() => { /* retirer cet id de itemsAffiches */ }, 5000)` une seule fois par id (garder un `Set` local des ids déjà programmés pour ne pas reprogrammer le timeout à chaque poll de 2 s).
  - Nettoyer l'intervalle et tout timeout en attente au démontage.
- Rendu :
  - Si `itemsAffiches.length === 0`, ne rien rendre (`return null`).
  - Sinon, une section `<section aria-label="File d'attente de synchronisation">` avec un titre, un bouton global « Réessayer tout maintenant » (appelle `reessayerTout()`, visible seulement s'il existe au moins un item en `echec_reseau` ou `echec_definitif`), et une liste (`<ul>`) d'items. Pour chaque item :
    - Libellé de statut (table ci-dessous), état/erreur (`derniereErreur` si présent), numéro de fiche serveur si `synchronise`.
    - Bouton « Réessayer maintenant » (appelle `reessayerItem(item.id)`) affiché **uniquement** si `item.statut === 'echec_reseau' || item.statut === 'echec_definitif'`. Jamais affiché pour `en_attente`, `en_cours`, `conflit_doublon`, `synchronise`.

Table des libellés de statut (utilisée telle quelle dans le composant) :

| `statut`          | Libellé affiché à l'agent |
|---|---|
| `en_attente`      | En attente de connexion |
| `en_cours`        | Envoi en cours… |
| `echec_reseau`    | Échec réseau — nouvelle tentative automatique programmée |
| `conflit_doublon` | Conflit détecté (doublon potentiel) — nécessite une vérification manuelle au poste |
| `echec_definitif` | `derniereErreur` (message déjà spécifique : session expirée / échec définitif / erreur serveur) |
| `synchronise`     | Synchronisé — numéro de fiche : `{numeroFicheServeur}` |

### `frontend/vite.config.ts`

```ts
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      injectRegister: false, // enregistrement manuel via virtual:pwa-register dans main.tsx
      includeAssets: ['vite.svg'],
      manifest: {
        name: 'SamaPièce',
        short_name: 'SamaPièce',
        start_url: '/',
        display: 'standalone',
        background_color: '#ffffff',
        theme_color: '#0f172a',
        icons: [
          { src: '/pwa-192x192.png', sizes: '192x192', type: 'image/png' },
          { src: '/pwa-512x512.png', sizes: '512x512', type: 'image/png' },
          { src: '/pwa-512x512-maskable.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      workbox: {
        navigateFallback: '/index.html',
        navigateFallbackDenylist: [/^\/api\//],
        globPatterns: ['**/*.{js,css,html,svg,png,ico}'],
        // Pas de runtimeCaching : aucune réponse d'API n'est mise en cache HTTP (cf. design, décision 3).
      },
    }),
  ],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
});
```

Points de vigilance pour le codeur :
- Ne pas activer `devOptions.enabled` : le service worker reste désactivé en `vite dev` (comportement par défaut de `vite-plugin-pwa`). Le test manuel et toute vérification d'installabilité **doivent** passer par `npm run build && npm run preview`, jamais par `npm run dev`.
- Vérifier après ajout que `npm run build` (qui exécute `tsc -b && vite build`) n'échoue pas (risque mentionné par le design, non vérifié en amont faute de config existante).

### `frontend/src/vite-env.d.ts`

```ts
/// <reference types="vite/client" />
/// <reference types="vite-plugin-pwa/client" />
```

### `frontend/index.html`

Ajouter dans `<head>`, après la balise `viewport` existante :
```html
<meta name="theme-color" content="#0f172a" />
<link rel="apple-touch-icon" href="/pwa-192x192.png" />
```
(Le lien `<link rel="manifest">` est injecté automatiquement au build par `vite-plugin-pwa`, ne pas l'ajouter manuellement.)

### `frontend/src/main.tsx`

```ts
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { registerSW } from 'virtual:pwa-register';
import { demarrerDeclencheurs } from './shared/offline/fileSynchronisation';
import './index.css';
import App from './app/App';

registerSW({ immediate: true });
demarrerDeclencheurs();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
```

### `docs/bolts/16-mode-hors-ligne-pwa/test-manuel-coupure-reseau.md`

Structure attendue (contenu à rédiger par le codeur en suivant ce plan) :

1. **Prérequis** : `npm run build && npm run preview` dans `frontend/` (le service worker n'est actif qu'en build de production) ; navigateur Chrome/Edge avec DevTools ; un compte agent valide.
2. **Scénario 1 — Installation PWA** : ouvrir l'app via `npm run preview`, vérifier dans DevTools > Application > Manifest que le manifest est valide et que les icônes se chargent ; vérifier qu'un service worker est enregistré et actif (Application > Service Workers) ; vérifier la présence d'un bouton/icône d'installation dans la barre d'adresse du navigateur.
3. **Scénario 2 — Création d'une fiche hors-ligne** : dans DevTools > Network, passer en mode « Offline » ; remplir et soumettre le formulaire ; vérifier l'affichage du message de mise en file (pas d'erreur bloquante) ; vérifier dans DevTools > Application > IndexedDB > `samapiece-offline` > `fiches-en-attente` la présence de l'enregistrement avec `statut: "en_attente"` ; vérifier que la fiche apparaît dans la section « File d'attente de synchronisation » avec le libellé « En attente de connexion ».
4. **Scénario 3 — Synchronisation automatique au retour de connexion** : repasser Network en « Online » ; vérifier que la fiche passe automatiquement à « Envoi en cours… » puis disparaît de la file après affichage bref du statut « Synchronisé » (~5 s) ; vérifier côté backend (ou via l'écran de recherche de pièces) que la fiche existe bien avec le bon numéro.
5. **Scénario 4 — Échec réseau intermittent avec retry** : passer Offline, soumettre une fiche, repasser brièvement Online puis Offline avant la fin du délai de backoff (15 s), vérifier que la tentative échoue à nouveau et que le compteur de tentatives progresse (visible en inspectant l'enregistrement IndexedDB, champ `tentatives` et `prochaineTentativeAuPlusTotLe`) ; laisser suffisamment de temps (plusieurs minutes) pour observer le passage en `echec_definitif` après la 4ᵉ tentative si la coupure persiste, et vérifier que le bouton « Réessayer maintenant » apparaît alors.
6. **Scénario 5 — Conflit doublon (409)** : nécessite de simuler manuellement un 409 (ex. soumettre deux fois la même fiche hors-ligne puis reconnecter, si le backend actuel détecte déjà un doublon exact sur num éro de document — sinon, documenter ce scénario comme « à rejouer une fois #15 mergée », et noter qu'il peut aussi être vérifié via un test réseau simulé dans DevTools en interceptant la requête et forçant une réponse 409). Vérifier que le statut affiché est « Conflit détecté (doublon potentiel)… » et qu'aucun bouton « Réessayer » n'apparaît pour cet item.
7. **Scénario 6 — Session expirée (401) pendant une synchronisation en attente** : invalider/supprimer le jeton en `localStorage` (`samapiece.accessToken`) pendant qu'un item est en attente, forcer une synchronisation (bouton global ou attendre le minuteur), vérifier le passage immédiat en `echec_definitif` avec le message de reconnexion.
8. **Limites connues à mentionner explicitement dans le document** : faux positifs de détection online/offline (réseau local sans sortie internet réelle) ; pas de synchronisation en tâche de fond si l'onglet est complètement fermé ; données en clair dans IndexedDB (pas de chiffrement, dette documentée) ; pas de résolution de conflit multi-onglets.

## Plan de tests

| Critère d'acceptation du ticket | Test couvrant |
|---|---|
| Formulaire agent en PWA installable avec service worker | Manuel — `test-manuel-coupure-reseau.md`, Scénario 1. Pas de test automatisé pertinent (installabilité dépend du navigateur/manifest, non simulable de façon fiable en Vitest/jsdom). |
| Fiche créée hors-ligne stockée localement (IndexedDB), visible dans une file « en attente de synchronisation » | `fileSynchronisation.test.ts` — tests `mettreEnFile` et `listerFile` (unitaires, ci-dessous) + `EnregistrementPiecePage.test.tsx` — nouveau test « appelle mettreEnFile et réinitialise le formulaire en cas d'échec réseau » + Manuel, Scénario 2. |
| Synchronisation automatique au retour de connexion avec gestion des échecs (retry) | `fileSynchronisation.test.ts` — tests succès, échec réseau + retry/backoff, épuisement des tentatives, 409, 401 (ci-dessous) + Manuel, Scénarios 3, 4, 5, 6. |
| Test manuel documenté (coupure réseau simulée) + test unitaire de la logique de file locale | `docs/bolts/16-mode-hors-ligne-pwa/test-manuel-coupure-reseau.md` (livrable) + `fileSynchronisation.test.ts` (livrable). |

### `frontend/src/shared/offline/fileSynchronisation.test.ts` — cas détaillés

Rappels d'implémentation (points de vigilance du design, à respecter strictement) :
- `import 'fake-indexeddb/auto';` (ou l'import équivalent selon la version installée) **en tête de ce fichier uniquement**, jamais dans `src/test/setup.ts` (pour ne pas polluer les autres suites avec un `indexedDB` global persistant).
- `beforeEach` : supprimer/réinitialiser la base entre chaque test (ex. `await indexedDB.deleteDatabase('samapiece-offline')` puis rouvrir), pour garantir l'indépendance des tests.
- Utiliser `vi.useFakeTimers()` pour les tests de backoff (avancer le temps avec `vi.advanceTimersByTimeAsync(...)` plutôt que d'attendre réellement 15 s/1 min/5 min).
- Mocker `piecesApi.creerPiece` (via `vi.mock('../../features/pieces/piecesApi')` ou équivalent) plutôt que `fetch` directement, pour rester au niveau du contrat utilisé par `fileSynchronisation.ts`.

Cas de test :
1. `mettreEnFile` crée un enregistrement avec `statut: 'en_attente'`, `tentatives: 0`, `id` généré, `payload` conservé sans transformation.
2. `listerFile` retourne les enregistrements triés par `creeLeLocal` croissant.
3. `synchroniser` — succès : `creerPiece` résout avec une `PieceResponse` ; vérifier que l'item passe par `statut: 'synchronise'`, `numeroFicheServeur` renseigné, **puis** que `listerFile()` ne le retourne plus (suppression immédiate d'IndexedDB).
4. `synchroniser` — échec réseau puis retry avec backoff : `creerPiece` rejette avec une erreur non-`PieceApiError` (ex. `new TypeError('Failed to fetch')`) sur les 3 premières tentatives puis résout en succès à la 4ᵉ ; avancer le temps de 15 s puis 1 min puis 5 min entre les tentatives (déclenchées via `synchroniser()` rappelé après chaque avancée de temps, en simulant le minuteur de secours) ; vérifier la séquence de statuts `echec_reseau` → `echec_reseau` → `echec_reseau` → `synchronise`, et que `prochaineTentativeAuPlusTotLe` est cohérent avec chaque palier.
5. `synchroniser` — épuisement des tentatives : `creerPiece` rejette systématiquement avec une erreur réseau ; après 4 tentatives (en avançant le temps sur les 3 paliers), vérifier `statut: 'echec_definitif'`, `tentatives: 4`, `prochaineTentativeAuPlusTotLe: null`, et qu'un nouvel avancement de temps + appel à `synchroniser()` ne redéclenche **pas** `creerPiece` (statut exclu de `estEligible`).
6. `synchroniser` — conflit doublon (409) : `creerPiece` rejette avec `new PieceApiError('...', 409)` dès la première tentative ; vérifier passage immédiat à `statut: 'conflit_doublon'`, message attendu dans `derniereErreur`, `tentatives: 1`, et qu'un avancement de temps + nouvel appel à `synchroniser()` ne redéclenche pas `creerPiece` pour cet item.
7. `synchroniser` — session expirée (401) : `creerPiece` rejette avec `new PieceApiError('...', 401)` ; vérifier passage immédiat (dès la 1ʳᵉ tentative, sans attendre 4 échecs) à `statut: 'echec_definitif'` avec le message de reconnexion, et absence de retry ultérieur.
8. `reessayerItem` : sur un item en `echec_definitif`, vérifie que l'appel réinitialise `tentatives` à 0 et redéclenche immédiatement une tentative (mock `creerPiece` en succès → `statut: 'synchronise'`) ; sur un item en `conflit_doublon` ou `synchronise`, vérifie que l'appel est un no-op (`creerPiece` non appelé).
9. Verrou en mémoire anti-concurrence : appeler `synchroniser()` deux fois en parallèle (`Promise.all`) avec `creerPiece` mocké pour résoudre après un délai artificiel (ex. `await new Promise(r => setTimeout(r, 100))` sous fake timers, ou une promesse contrôlée manuellement) ; vérifier que `creerPiece` n'est appelé qu'une seule fois pour un même item malgré les deux appels concurrents à `synchroniser`.

### `frontend/src/features/pieces/EnregistrementPiecePage.test.tsx` — ajustements

- Ajouter en tête de fichier un mock du module offline pour ne pas dépendre d'une vraie IndexedDB (absente par défaut dans l'environnement `jsdom` de ce fichier, cf. « Écarts identifiés ») :
  ```ts
  vi.mock('../../shared/offline/fileSynchronisation', () => ({
    mettreEnFile: vi.fn().mockResolvedValue({}),
    listerFile: vi.fn().mockResolvedValue([]),
    reessayerItem: vi.fn(),
    reessayerTout: vi.fn(),
    demarrerDeclencheurs: vi.fn(() => () => {}),
  }));
  ```
- Ajouter un test : « en cas d'échec réseau, met la fiche en file et réinitialise le formulaire » — mocker `fetch` pour qu'il rejette avec `new TypeError('Failed to fetch')`, soumettre le formulaire rempli, vérifier que `mettreEnFile` (le mock) a été appelé avec le payload attendu, et que le champ `Nom du titulaire` est réinitialisé (`toHaveValue('')`), sans message d'erreur bloquant (`role="alert"` de type erreur serveur absent).
- Les tests existants (401, 400, succès, validations) restent inchangés dans leur logique ; seul l'ajout du mock ci-dessus est requis pour éviter une régression liée au rendu de `<FileAttenteSynchronisation />`.

## Écarts identifiés

- **Le design ne mentionne pas l'impact sur `EnregistrementPiecePage.test.tsx` existant.** En ajoutant `<FileAttenteSynchronisation />` (qui appelle `listerFile()` → `openDB()` → nécessite un `indexedDB` global) dans `EnregistrementPiecePage.tsx`, les tests existants de ce composant casseraient dans l'environnement `jsdom` par défaut, puisque la décision 9 du design (reprise au point de fermeture #4 ci-dessus) exclut volontairement tout polyfill IndexedDB global dans `src/test/setup.ts`. Résolu dans cette spec : `EnregistrementPiecePage.test.tsx` doit mocker le module `shared/offline/fileSynchronisation` (voir Plan de tests). Le codeur doit appliquer ce mock dès la modification du composant, pas après coup en réaction à un échec CI.
- **Aucun écart de fond entre `design.md` et les critères d'acceptation du ticket** : les 4 critères sont couverts (PWA installable, stockage local visible, sync auto avec retry, tests manuel + unitaire). Les 4 points ouverts du design sont fermés ci-dessus par des décisions définitives et actionnables.
