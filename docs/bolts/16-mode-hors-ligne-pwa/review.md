# Review — Ticket #16 : Mode hors-ligne (PWA offline-first) pour le formulaire agent

APPROVE

Branche revue : `bolt/issue-16-mode-hors-ligne-pwa`, base `main`.
Commits : `6cc501a` (design), `980071e` (spec), `0057b33`, `092c85d`, `b7628fe`, `c898005`.

## Critères d'acceptation

| # | Critère | Statut | Preuve |
|---|---|---|---|
| 1 | Formulaire agent en PWA installable avec service worker | Couvert | `vite.config.ts` (VitePWA, `registerType: 'autoUpdate'`, pas de `runtimeCaching` sur `/api/**`, `devOptions.enabled` absent) ; `npm run build` génère bien `dist/sw.js`, `dist/workbox-*.js`, `dist/manifest.webmanifest` avec les 3 icônes correctement dimensionnées ; procédure manuelle dans `test-manuel-coupure-reseau.md` Scénario 1. Pas de test automatisé — cohérent avec le plan de tests de la spec (installabilité non simulable en jsdom). |
| 2 | Fiche créée hors-ligne stockée en IndexedDB, visible dans une file "en attente" | Couvert | `mettreEnFile`/`listerFile` testés (`fileSynchronisation.test.ts`), `FileAttenteSynchronisation.tsx` affiche les items via polling 2s, `EnregistrementPiecePage.test.tsx` teste le chemin échec réseau -> `mettreEnFile` + reset formulaire. |
| 3 | Synchronisation automatique au retour de connexion avec retry | Couvert | `demarrerDeclencheurs` (listener `online` + minuteur 30s), `envoyerItem`/`estEligible` avec backoff `[15s, 60s, 300s]` et `NOMBRE_MAX_TENTATIVES = 4`, tous les cas testés (succès, échec réseau + retry jusqu'à succès, épuisement -> `echec_definitif`, 409 -> `conflit_doublon` sans retry, 401 -> `echec_definitif` immédiat, verrou anti-concurrence). |
| 4 | Test manuel documenté + test unitaire de la file locale | Couvert | `docs/bolts/16-mode-hors-ligne-pwa/test-manuel-coupure-reseau.md` (6 scénarios + limites connues) ; `fileSynchronisation.test.ts` (10 tests), `FileAttenteSynchronisation.test.tsx` (4 tests, tâche optionnelle réalisée), `EnregistrementPiecePage.test.tsx` (mock du module offline + nouveau test réseau). |

## Points de vigilance spécifiques — résultat de la vérification

1. **`envoyerItem`** : transitions vérifiées ligne à ligne dans `frontend/src/shared/offline/fileSynchronisation.ts:75-119` — 409 → `conflit_doublon` immédiat sans retry, 401 → `echec_definitif` immédiat, `>= 500` → traité via `marquerEchecReseau` (comme réseau), autre code HTTP → `echec_definitif` avec message générique, réseau (`TypeError`) → `marquerEchecReseau`. `item.tentatives += 1` a bien lieu avant l'appel réseau (ligne 79). Le succès supprime immédiatement l'enregistrement (`persister` puis `supprimerDeLaFile`, lignes 82-86).
2. **Backoff** : `PALIERS_BACKOFF_MS = [15_000, 60_000, 300_000]`, `NOMBRE_MAX_TENTATIVES = 4` — conforme, et le test `échec réseau puis retry avec backoff jusqu'au succès à la 4e tentative` exerce les 3 paliers avec `vi.advanceTimersByTimeAsync`.
3. **Verrou en mémoire (`idsEnCours`)** : vérifié par lecture (check-puis-add synchrone avant tout `await` dans `envoyerItem`, `finally` pour le retrait) et par exécution réelle du test « le verrou en mémoire empêche un double envoi lors d'appels concurrents » — passe, `creerPiece` appelé une seule fois.
4. **`fake-indexeddb`** : un seul import, dans `fileSynchronisation.test.ts` uniquement (`grep` confirmé) ; absent de `src/test/setup.ts`.
5. **`EnregistrementPiecePage.test.tsx`** : mock du module offline en tête de fichier (pas de vraie IndexedDB) ; les 10 tests passent (401, 400, succès, 5 validations, nouveau test réseau) — aucune régression.
6. **Distinction réseau vs HTTP** dans `EnregistrementPiecePage.tsx:79` (`!(e instanceof PieceApiError) || !navigator.onLine`) : conforme à la spec, chemin HTTP classique (`erreurServeur`, formulaire non réinitialisé) intact pour 401/400.
7. **Config PWA** : conforme au contrat de la spec ; build vérifié — `dist/sw.js` contient `NavigationRoute` avec `denylist: [/^\/api\//]` et aucun `runtimeCaching` d'API.
8. **`FileAttenteSynchronisation.tsx`** : délai de grâce de 5s implémenté via `Set` d'ids programmés + `Map` de timeouts (pas de reprogrammation à chaque poll), bouton « Réessayer maintenant » conditionné strictement à `echec_reseau`/`echec_definitif` (ligne 95), jamais pour les autres statuts — vérifié aussi par test composant dédié.
9. **Pas de donnée binaire/photo** en IndexedDB — confirmé, `FicheEnAttente.payload` est `CreerPieceRequest` (JSON pur).
10. **Message 409** générique, sans mention de candidats — confirmé dans le code et le composant.

## Build/tests

Commandes lancées dans `frontend/` :
- `npm ci` → OK (avertissements de dépréciation npm sans rapport avec le ticket).
- `npm run lint` (`eslint .`) → OK, aucune erreur.
- `npm test` (`vitest run`) → OK, 4 fichiers de tests, **31 tests passés** (aucun échec, aucune suite skippée).
- `npm run build` (`tsc -b && vite build`) → OK, génère `dist/sw.js`, `dist/workbox-*.js`, `dist/manifest.webmanifest`, aucune erreur TypeScript.
- `npm run format:check` (Prettier) → 29 fichiers signalés, dont la quasi-totalité de fichiers non touchés par ce ticket (`App.tsx`, `agentsApi.ts`, `tsconfig.json`, `package-lock.json`, etc.) et même des fichiers de config antérieurs à la branche. Confirmé que ce script **n'est pas exécuté** par `.github/workflows/frontend.yml` (seuls `lint`, `test`, `build` le sont) : non bloquant pour la CI, cohérent avec le signalement du codeur (probable écart de fin de ligne CRLF/LF lié à l'environnement Windows).

## Remarques (non bloquantes, pour information de l'humain)

- **Dette de sécurité déjà actée et documentée** : la file IndexedDB stocke nom/prénom/numéro de document en clair (non chiffré), en écart avec l'exigence générale de hachage/chiffrement des identifiants sensibles (§10.4 de `PROJET-SAMAPIECE.md`). Ce n'est pas un oubli du codeur : c'est une décision explicite de l'architecte (design.md, risque) fermée par la spec (point ouvert #4) et documentée comme dette assumée dans `test-manuel-coupure-reseau.md` (section « Limites connues »). Signalé ici pour visibilité humaine, pas comme un finding à corriger dans ce bolt.
- **Couverture de test du branchement `>= 500`** : la logique (`error.status >= 500` traité comme un échec réseau, `fileSynchronisation.ts:104-107`) n'est pas exercée par un test dédié — seuls les cas réseau pur (`TypeError`), 409 et 401 le sont. Le code partage la fonction `marquerEchecReseau` déjà testée via le chemin réseau, donc le risque de régression silencieuse est faible, mais un test explicite (`PieceApiError` avec `status: 503`) aurait complété la couverture. Ceci suit exactement le plan de tests de la spec (qui ne demandait pas ce cas précis) — c'est un gap hérité de la spec plutôt qu'un manquement du codeur, donc non bloquant, mais à noter pour un futur raffinement.

## Verdict

**APPROVE** — le code respecte fidèlement le contrat technique de `spec.md`, les critères d'acceptation du ticket sont couverts par du code et des tests qui échoueraient sans lui, le build et les tests passent localement sans exception, et les décisions de scope (indépendance vis-à-vis de #15, pas de chiffrement) sont bien celles actées en amont et correctement documentées.
