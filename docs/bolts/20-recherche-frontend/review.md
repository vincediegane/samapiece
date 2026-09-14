# Review — #20 Interface web de recherche citoyenne

APPROVE

## Critères d'acceptation

| Critère | Statut |
|---|---|
| Formulaire de recherche simple (type, nom, critères optionnels), résultat affiché sans exposer de donnée sensible | Couvert — `RecherchePubliquePage.tsx` (lignes 82-93) n'affiche que `typeDocument`, `poste.{nom,adresse,horaires,telephone}` et `referenceDossier` ; test `affiche le résultat sans donnée sensible quand la pièce est trouvée` (`RecherchePubliquePage.test.tsx:51-69`) vérifie explicitement `expect(section).not.toHaveTextContent('1234567890')` (le numéro saisi). |
| Fonctionne sur mobile bas de gamme (poids de page limité, dégradation gracieuse des images) | Couvert par revue manuelle, comme documenté dans la spec — aucune image introduite, aucune nouvelle dépendance npm (`package.json`/`package-lock.json` inchangés confirmés par `git diff`), bundle JS final 154,72 kB / gzip 48,94 kB (`npm run build`). Pas de test automatisé possible ici, conformément à l'analyse de l'architecte. |
| Proposition de créer une alerte si aucun résultat trouvé | Couvert — bouton « Recevoir une alerte si cette pièce est déposée » (ligne 100-102), n'appelle jamais `fetch`/`recherchePubliqueApi`, seulement `setAlerteProposee(true)` ; test `propose une alerte quand aucun résultat n'est trouvé` vérifie `expect(fetch).toHaveBeenCalledTimes(1)` après le clic. |
| Test composant/E2E couvrant recherche avec résultat et sans résultat | Couvert — les deux tests dédiés (lignes 51-69 et 71-89 de `RecherchePubliquePage.test.tsx`) couvrent les deux scénarios, plus 3 tests de validation et 1 test de gestion du 400 (7 tests au total, comme annoncé). |

## Vérifications ciblées

1. **Aucune fuite de donnée sensible** : confirmé. La section résultat n'affiche que les champs du contrat backend ; le test compare explicitement l'absence du numéro de document saisi dans le DOM du résultat.
2. **`recherchePubliqueApi.rechercher`** : pas d'appel à `.json()` sur la branche d'erreur (`recherchePubliqueApi.ts:23-28`), en-têtes limités à `Content-Type: application/json`, aucune trace d'`Authorization`/`localStorage` dans tout `frontend/src/features/recherche-publique/`.
3. **Validation client miroir de `estSuffisant()`** : logique identique côté backend (`RecherchePubliqueRequest.estSuffisant()`) et frontend (`validerFormulaire`), OU logique testé dans les deux sens (numéro seul via `remplirChampsRequis`, date seule via le test dédié « accepte la date de naissance comme seul discriminant »). Aucun appel `fetch` en cas d'erreurs de validation (assertions `expect(fetch).not.toHaveBeenCalled()`).
4. **Non-réinitialisation du formulaire après succès** : confirmé, `soumettreFormulaire` ne touche jamais à `formulaire` après un succès (seuls `resultat`/`alerteProposee` sont mis à jour) — comportement intentionnel documenté dans la spec et respecté par le code.
5. **Bloc « proposer une alerte »** : `onClick={() => setAlerteProposee(true)}` uniquement, pas d'import de `recherchePubliqueApi` pour ce bouton, confirmé par le test `toHaveBeenCalledTimes(1)`.
6. **Aucune nouvelle dépendance** : `git diff main..HEAD -- frontend/package.json frontend/package-lock.json` ne retourne rien.
7. **Types en miroir exact du contrat backend** : comparaison directe avec `backend/.../recherche/web/RecherchePubliqueRequest.java` et `RecherchePubliqueResponse.java` — champs et nullabilité identiques, aucun champ superflu (pas de `numeroDocument`/`dateNaissanceTitulaire` dans `RecherchePubliqueResponse`).
8. **`App.tsx`** : navigation `pieces`/`agents` préservée sans régression (ternaire imbriqué ajouté proprement, `EnregistrementPiecePage`/`AgentsPage` toujours rendus pour leurs onglets respectifs).

Aucun finding bloquant identifié.

## Build/tests

- `npm test -- --run` (frontend) → **16/16 tests passés** (7 nouveaux `RecherchePubliquePage` + 9 existants `EnregistrementPiecePage`, inchangés).
- `npm run build` (frontend) → succès (`tsc -b && vite build`), bundle 154,72 kB / gzip 48,94 kB.
- `npm run lint` (frontend) → succès, aucune erreur ESLint.
- Backend non touché par ce bolt (aucun fichier `backend/` modifié dans le diff) — `mvn -pl backend test` non nécessaire pour ce périmètre.
