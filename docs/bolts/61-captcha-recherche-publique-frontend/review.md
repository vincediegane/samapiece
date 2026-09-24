# Review — #61 CAPTCHA de la recherche publique (frontend)

## Verdict

APPROVE

## Résumé

Le diff (`git diff main...bolt/issue-61-captcha-recherche-publique-frontend`) est strictement conforme au contrat technique de `spec.md` : `recherchePubliqueApi.ts` reproduit le code de la spec caractère pour caractère (`CaptchaRequisApiError`, `obtenirDefiCaptcha()`, ordre de vérification 428 → 400 → fallback dans `rechercher()`), `RecherchePubliquePage.tsx` implémente l'état et les handlers exactement comme décrits (`defiCaptcha`, `reponseCaptcha`, `payloadEnAttente`, `chargerNouveauDefi`, `soumettreReponseCaptcha`), et les messages utilisateur correspondent mot pour mot au tableau de la spec. Aucun code HTTP brut n'est jamais affiché. Build, lint et tests sont verts.

## Critères d'acceptation

| # | Critère | Statut |
|---|---|---|
| 1 | Sur 428, le frontend récupère un défi CAPTCHA (`GET /captcha`) et le présente | **Couvert** — `soumettreFormulaire` catch `CaptchaRequisApiError` → `chargerNouveauDefi()` → `GET ${BASE_URL}/captcha` (recherchePubliqueApi.ts:31-37) ; UI conditionnelle `defiCaptcha && (...)` affiche `defiCaptcha.question` (RecherchePubliquePage.tsx:138-158). Testé dans « parcours complet ». |
| 2 | La recherche suivante inclut la réponse dans `X-Captcha-Token`/`X-Captcha-Reponse` | **Couvert** — `rechercher(payload, captcha)` pose les deux en-têtes (recherchePubliqueApi.ts:56-60/62-70) ; test « parcours complet » vérifie `vi.mocked(fetch).mock.calls[2]` (le vrai 2e POST, index 2 car `calls[0]`=1er POST 428, `calls[1]`=GET captcha) avec les valeurs exactes `token-A` / `5`. |
| 3 | Message clair à chaque étape, jamais de code HTTP brut | **Couvert** — les 3 messages (défi requis, réponse incorrecte, échec chargement défi) correspondent mot pour mot au tableau de la spec (RecherchePubliquePage.tsx:58/86/114) ; assertion `queryByText(/Erreur 428/)` absente dans les deux nouveaux tests (lignes 197 et 240 du test). |
| 4 | Test du parcours complet 5 échecs → défi → réponse correcte → recherche acceptée | **Partiel, jugé acceptable** — voir section dédiée ci-dessous. |

## Évaluation du point AC4 (comptage des 5 échecs)

L'interprétation du spec-writer est raisonnable et je la valide : le compteur d'échecs consécutifs par IP est une responsabilité 100 % backend (`EchecRechercheCounterService`, déjà livrée et testée par #19), et le frontend ne réagit qu'à un unique statut HTTP 428 — il n'existe aucune branche de code frontend qui « compte » les tentatives, donc rejouer 5 vrais appels dans le test n'exercerait rigoureusement rien de plus que le test actuel (428 dès le 1er appel mocké). Le test « parcours complet » couvre bien la vraie mécanique frontend (réception 428 → GET défi → renvoi avec en-têtes → succès), ce qui est le seul comportement testable côté frontend.

Le trou de couverture réel (vérifier qu'en environnement réel, avec Redis et le vrai compteur, la 6e requête déclenche effectivement le défi et que le frontend l'affiche correctement) est documenté dans `spec.md` comme tâche de test manuel, non exécutée faute de backend/Redis dans le sandbox du codeur. Je ne considère pas cela bloquant pour cette étape du pipeline (branche prête à relire / PR draft) car :
- le contrat consommé (`GET /captcha`, `428` + en-têtes) est celui déjà spécifié et testé côté backend par #19 ;
- le risque résiduel porte sur l'intégration bout-en-bout (jamais couverte par des tests automatisés dans ce dépôt faute de Testcontainers combinant les deux stacks), pas sur la logique frontend elle-même ;
- la spec liste explicitement ce test manuel comme condition à vérifier « avant fermeture du ticket ».

**Recommandation ferme avant merge définitif** : exécuter le test manuel décrit (5 recherches infructueuses réelles via l'UI en local/staging avec Redis actif → 6e requête → défi → réponse correcte → résultat) et consigner le résultat, faute de quoi AC4 reste formellement non vérifié en conditions réelles. Ce n'est pas un motif de `CHANGES_REQUESTED` à ce stade du pipeline, mais une action de suivi à ne pas oublier avant que l'humain merge.

## Vérifications détaillées

1. **Contrat technique (`recherchePubliqueApi.ts`)** — conforme à la lettre : signatures, en-têtes (casse `X-Captcha-Token`/`X-Captcha-Reponse` respectée), ordre 428 avant 400 avant fallback générique (lignes 40-46 du diff), `captcha` optionnel en fin de signature (non-régression des appels existants confirmée par les tests déjà présents qui continuent de passer sans modification).
2. **UI (`RecherchePubliquePage.tsx`)** — formulaire principal et son bouton bien désactivés via `disabled={enEnvoi || defiCaptcha !== null}` sur tous les champs (lignes 253, 279, 295, 306, 319, 331). Le nouveau défi remplace systématiquement l'ancien : `chargerNouveauDefi()` est appelé après chaque 428 (premier essai et réponses ultérieures), jamais de réutilisation d'un token précédent — confirmé par le test « réponse incorrecte » qui vérifie la disparition de l'ancienne question et l'apparition de la nouvelle. Messages identiques mot pour mot au tableau de la spec, aucun `Erreur ${status}` visible côté UI pour le cas 428.
3. **Tests** — les deux nouveaux tests exercent réellement `mock.calls[2][1]?.headers` (le 2e POST, pas seulement le statut), avec assertion sur les valeurs exactes des deux en-têtes. L'assertion `queryByText(/Erreur 428/)).not.toBeInTheDocument()` est présente et pertinente dans les deux tests.
4. **Non-régression** — les 7 tests déjà présents avant ce ticket (champs, résultat trouvé, alerte, validations, 400) passent toujours sans modification ; `rechercher(payload)` sans 2e argument reste valide (paramètre optionnel).
5. **Qualité** — style cohérent avec le fichier existant (mêmes conventions de nommage, structure JSX identique aux autres champs `field`/`field-label`/`field-input`), pas de sur-ingénierie (pas de routeur, pas de modale, défi affiché en ligne comme prescrit), `Content-Type: application/json` toujours posé même quand les en-têtes captcha sont ajoutés (l'objet `headers` est construit puis étendu, jamais remplacé).

## Build/tests

Commandes lancées depuis `frontend/` :
- `npx vitest run` → **67 tests passés / 11 fichiers**, dont les 9 tests de `RecherchePubliquePage.test.tsx` (7 existants + 2 nouveaux).
- `npx eslint .` → aucune sortie, **aucune erreur/warning**.
- `npx prettier --check .` → warnings sur ~46 fichiers, **mais comportement identique sur `main`** (vérifié via `git worktree add` sur `main` : 52 fichiers en warning avant ce diff, incluant déjà des fichiers hors périmètre comme `tsconfig.json`, `vite.config.ts`). Ce n'est pas une régression introduite par ce ticket ; probablement lié à l'environnement (fins de ligne CRLF sous Windows) plutôt qu'au style du code. Non bloquant.
- `npm run build` (= `tsc -b && vite build`) → **succès**, aucune erreur TypeScript, bundle généré normalement (PWA/service worker inclus).

## Note incidentelle (processus de review, sans impact sur le code livré)

Durant l'investigation du point prettier, une commande `git checkout main -- .` lancée par erreur depuis la racine du repo a temporairement mis en staging un retour des 4 fichiers de la branche vers leur version `main`. Aucun commit n'a été fait ; l'opération a été immédiatement annulée via `git restore --staged --worktree .`, et `git status`/`git diff --stat` confirment que le working tree correspond exactement au dernier commit de la branche (`88e1000`). Aucun impact sur le code réellement livré.
