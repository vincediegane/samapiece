# Spec — #20 Interface web de recherche citoyenne

## Résumé

Livrer un onglet public « Recherche publique » dans le frontend existant, consommant tel quel
`POST /api/v1/recherche-publique` (#18), avec validation client miroir du backend, aucune donnée
sensible affichée, et une proposition d'alerte non branchée quand aucun résultat n'est trouvé.

## Tâches

- [ ] `frontend/src/features/recherche-publique/types.ts` — créer les types `RecherchePubliqueRequest`,
      `PosteResume`, `RecherchePubliqueResponse` en miroir exact du contrat backend (voir Contrat
      technique). Importer `TypeDocument` depuis `../pieces/types` (pas de redéfinition de l'union de
      8 valeurs).
- [ ] `frontend/src/features/recherche-publique/recherchePubliqueApi.ts` — créer `RecherchePubliqueApiError`
      (classe `Error` avec `status: number`, calquée sur `PieceApiError`) et la fonction
      `rechercher(payload: RecherchePubliqueRequest): Promise<RecherchePubliqueResponse>` : `fetch`
      `POST` vers `/api/v1/recherche-publique`, en-tête `Content-Type: application/json` uniquement
      (pas d'`Authorization`), pas de lecture du corps sur une réponse d'erreur, message spécifique
      pour 400, message générique `Erreur ${status}` pour tout le reste.
- [ ] `frontend/src/features/recherche-publique/RecherchePubliquePage.tsx` — composant fonctionnel
      avec formulaire de recherche, validation client bloquante avant tout appel réseau, affichage du
      résultat (poste + référence de dossier uniquement), bloc « proposer une alerte » quand
      `trouve === false` (voir Contrat technique pour la structure exacte).
- [ ] `frontend/src/features/recherche-publique/RecherchePubliquePage.test.tsx` — tests RTL/Vitest
      couvrant les 4 scénarios du Plan de tests, sur le patron de
      `frontend/src/features/pieces/EnregistrementPiecePage.test.tsx` (mock `fetch` global via
      `vi.stubGlobal`).
- [ ] `frontend/src/app/App.tsx` — étendre `type Onglet = 'pieces' | 'agents' | 'recherche'`, ajouter
      un bouton de nav « Recherche publique » (`onClick={() => setOnglet('recherche')}`,
      `disabled={onglet === 'recherche'}`, même patron que les boutons existants), et rendre
      `RecherchePubliquePage` quand `onglet === 'recherche'`.

## Contrat technique

### `types.ts`

```ts
import type { TypeDocument } from '../pieces/types';

export interface RecherchePubliqueRequest {
  typeDocument: TypeDocument | null;
  nomTitulaire: string;
  prenomTitulaire: string | null;
  numeroDocument: string | null;
  dateNaissanceTitulaire: string | null; // format "YYYY-MM-DD", même convention que CreerPieceRequest
}

export interface PosteResume {
  nom: string;
  adresse: string;
  horaires: string;
  telephone: string;
}

export interface RecherchePubliqueResponse {
  trouve: boolean;
  typeDocument: TypeDocument | null;
  poste: PosteResume | null;
  referenceDossier: string | null;
}
```

Ces champs sont un miroir exact de `RecherchePubliqueRequest`/`RecherchePubliqueResponse` côté
backend (`backend/src/main/java/sn/samapiece/recherche/web/`) : ne jamais ajouter de champ qui
n'existe pas dans ces records (notamment jamais de `numeroDocument`/`dateNaissanceTitulaire` dans la
réponse — le backend ne les renvoie pas, donc le composant n'a physiquement rien de sensible à
afficher tant qu'il n'invente pas de champ).

### `recherchePubliqueApi.ts`

```ts
export class RecherchePubliqueApiError extends Error {
  constructor(message: string, public readonly status: number) {
    super(message);
    this.name = 'RecherchePubliqueApiError';
  }
}

export async function rechercher(
  payload: RecherchePubliqueRequest,
): Promise<RecherchePubliqueResponse>;
```

- `BASE_URL = '/api/v1/recherche-publique'`.
- En-têtes : `{ 'Content-Type': 'application/json' }` uniquement — pas de `localStorage`, pas de
  jeton, pas d'`Authorization` (portail public).
- Si `!reponse.ok` :
  - `status === 400` → `throw new RecherchePubliqueApiError('Vérifiez les critères de recherche saisis.', 400)`.
  - tout autre code (401/403/404/429/428/500...) → `throw new RecherchePubliqueApiError(\`Erreur ${reponse.status}\`, reponse.status)`. Aucune branche spécifique 429/428 : le fallback générique suffit tant que #19 n'est pas mergé.
  - jamais de `await reponse.json()` sur la branche d'erreur.
- Si `reponse.ok` → `return reponse.json()`.

### `RecherchePubliquePage.tsx`

**État local**

```ts
interface FormState {
  typeDocument: TypeDocument | '';
  nomTitulaire: string;
  prenomTitulaire: string;
  numeroDocument: string;
  dateNaissanceTitulaire: string; // input type="date", format natif "YYYY-MM-DD"
}
```

Autres `useState` : `erreursValidation` (voir ci-dessous), `erreurServeur: string | null`,
`enEnvoi: boolean`, `resultat: RecherchePubliqueResponse | null`, `alerteProposee: boolean`
(état du bouton « proposer une alerte », voir plus bas).

**Champs de formulaire** (labels exacts, requis pour `getByLabelText` dans les tests) :

| Champ | Type d'input | Label | Obligatoire à la saisie |
|---|---|---|---|
| `typeDocument` | `<select>` avec les options de `TYPE_DOCUMENT_LABELS` (import depuis `../pieces/types`) | `Type de document` | oui |
| `nomTitulaire` | `<input type="text">` | `Nom du titulaire` | oui |
| `prenomTitulaire` | `<input type="text">` | `Prénom du titulaire` | non |
| `numeroDocument` | `<input type="text">` | `Numéro du document` | non (mais discriminant, voir validation) |
| `dateNaissanceTitulaire` | `<input type="date">` | `Date de naissance du titulaire` | non (mais discriminant, voir validation) |

Bouton de soumission : `<button type="submit">Rechercher</button>`.

**Validation client (miroir exact de `estSuffisant()` backend)**, exécutée dans le handler de
soumission avant tout appel réseau :

```ts
function validerFormulaire(f: FormState): { typeDocument?: string; nomTitulaire?: string; discriminant?: string } {
  const erreurs: { typeDocument?: string; nomTitulaire?: string; discriminant?: string } = {};
  if (!f.typeDocument) erreurs.typeDocument = 'Le type de document est requis.';
  if (!f.nomTitulaire.trim()) erreurs.nomTitulaire = 'Le nom du titulaire est requis.';
  if (!f.numeroDocument.trim() && !f.dateNaissanceTitulaire) {
    erreurs.discriminant = 'Renseignez le numéro du document ou la date de naissance du titulaire.';
  }
  return erreurs;
}
```

- Si `Object.keys(erreurs).length > 0` : afficher chaque message via `<span role="alert">` sous le
  champ concerné (message `discriminant` affiché sous le champ `Numéro du document`, ou dans un
  bloc dédié juste au-dessus du bouton — au choix du codeur, mais toujours avec `role="alert"` pour
  être détectable par les tests), ne jamais appeler `fetch`, ne pas modifier `resultat`/`erreurServeur`.
- Sinon : construire le payload (`prenomTitulaire`/`numeroDocument`/`dateNaissanceTitulaire` vides →
  `null`, jamais chaîne vide envoyée au backend), appeler `rechercher(payload)`.

**Comportement après soumission valide** :
- Avant l'appel : `setErreurServeur(null)`, ne pas effacer `resultat` précédent tant que la nouvelle
  réponse n'est pas arrivée (évite un flash vide), `setEnEnvoi(true)`.
- Succès : `setResultat(reponse)`, réinitialiser `alerteProposee` à `false`. **Ne pas réinitialiser
  les champs du formulaire** après une recherche réussie (contrairement à
  `EnregistrementPiecePage` qui vide le formulaire après un enregistrement) : l'utilisateur doit
  pouvoir affiner sa recherche sans tout ressaisir.
- Échec réseau : `catch`, `setErreurServeur(e.message)`, ne pas modifier `resultat`.
- `finally` : `setEnEnvoi(false)`.

**Affichage du résultat** (`resultat !== null`) :
- Si `resultat.trouve === true` : `<section aria-label="Résultat de la recherche">` affichant
  uniquement : le libellé du type de document (`TYPE_DOCUMENT_LABELS[resultat.typeDocument]`), le
  nom du poste (`resultat.poste.nom`), l'adresse (`resultat.poste.adresse`), les horaires
  (`resultat.poste.horaires`), le téléphone (`resultat.poste.telephone`), la référence de dossier
  (`resultat.referenceDossier`). Ne jamais afficher `numeroDocument`/`dateNaissanceTitulaire` saisis
  par l'utilisateur comme s'ils venaient de la réponse — la réponse backend ne les contient pas, donc
  ne rien ajouter au-delà des champs listés ci-dessus.
- Si `resultat.trouve === false` : `<section aria-label="Aucun résultat">` avec un texte du type
  « Aucune pièce correspondant à ces critères n'a été retrouvée. » suivi du bloc « proposer une
  alerte » :
  - `<button type="button">Recevoir une alerte si cette pièce est déposée</button>`, `onClick` met
    `alerteProposee` à `true` (pas d'appel réseau, pas d'import de `recherchePubliqueApi` pour ce
    bouton).
  - Si `alerteProposee === true`, afficher un `<p>Cette fonctionnalité arrive bientôt.</p>` (ou texte
    équivalent stable) à la place du bouton ou juste en dessous.

**Erreur serveur** : `erreurServeur && <p role="alert">{erreurServeur}</p>` affiché en haut du
composant, même patron que `EnregistrementPiecePage`.

### `App.tsx`

```ts
type Onglet = 'pieces' | 'agents' | 'recherche';
```

Ajouter dans `<nav>` :

```tsx
<button type="button" onClick={() => setOnglet('recherche')} disabled={onglet === 'recherche'}>
  Recherche publique
</button>
```

Rendu conditionnel étendu (ternaire imbriqué ou équivalent) pour inclure
`onglet === 'recherche' ? <RecherchePubliquePage /> : ...`.

## Plan de tests

| Critère d'acceptation du ticket | Test |
|---|---|
| Formulaire de recherche simple, résultat affiché sans donnée sensible | `RecherchePubliquePage.test.tsx` — test « affiche le résultat sans donnée sensible quand la pièce est trouvée » : mock `fetch` renvoyant `{ trouve: true, typeDocument: 'CNI', poste: { nom, adresse, horaires, telephone }, referenceDossier }`, remplir les champs requis (type + nom + numéro), soumettre, vérifier que le poste et la référence s'affichent et qu'aucun texte contenant le numéro de document saisi ou une date de naissance n'apparaît dans le DOM du résultat. |
| Fonctionne sur mobile bas de gamme (poids de page limité, dégradation gracieuse des images) | Pas de test automatisé pertinent : aucune image n'est introduite par cette page, donc « dégradation gracieuse des images » ne s'applique pas littéralement (documenté comme tel dans `design.md`, Risques). Le respect du budget de poids se vérifie par relecture (aucune nouvelle dépendance npm dans `package.json`, pas d'import d'image) — **vérification manuelle en revue de code**, pas de test automatisé. |
| Proposition de créer une alerte si aucun résultat trouvé | `RecherchePubliquePage.test.tsx` — test « propose une alerte quand aucun résultat n'est trouvé » : mock `fetch` renvoyant `{ trouve: false, typeDocument: null, poste: null, referenceDossier: null }`, soumettre une recherche valide, vérifier la présence du bouton « Recevoir une alerte si cette pièce est déposée », cliquer dessus, vérifier l'apparition du message « Cette fonctionnalité arrive bientôt. » et l'absence de tout second appel à `fetch` (`expect(fetch).toHaveBeenCalledTimes(1)`). |
| Test composant/E2E couvrant recherche avec résultat et sans résultat | Les deux tests ci-dessus (« avec résultat » et « sans résultat ») couvrent ce critère directement. |
| (dérivé du design) Validation client miroir de `estSuffisant()` | `RecherchePubliquePage.test.tsx` — au moins 3 tests : (1) soumission avec seulement `nomTitulaire` rempli → erreur « Le type de document est requis. », pas d'appel `fetch` ; (2) soumission avec `typeDocument` + `nomTitulaire` mais sans `numeroDocument` ni `dateNaissanceTitulaire` → erreur discriminant, pas d'appel `fetch` ; (3) soumission avec `typeDocument` + `nomTitulaire` + `dateNaissanceTitulaire` (sans `numeroDocument`) → validation passe, `fetch` appelé (vérifie que le OU logique entre les deux discriminants fonctionne dans les deux sens). |
| (dérivé du design) Gestion du 400 avec message générique | `RecherchePubliquePage.test.tsx` — test « affiche un message générique sur 400 » : mock `fetch` avec `{ ok: false, status: 400, json: async () => { throw new Error('ne doit pas être appelé'); } }`, soumettre une recherche valide, vérifier l'affichage de « Vérifiez les critères de recherche saisis. » et que `json` n'a jamais été invoqué (le throw dans le mock garantit qu'un appel accidentel ferait échouer le test). |

## Écarts identifiés

- Aucun écart bloquant entre `design.md` et les critères d'acceptation du ticket. Le seul point à
  noter (déjà documenté par l'architecte en Risques) : le critère « dégradation gracieuse des
  images » n'a pas de traduction concrète dans cette implémentation puisqu'aucune image n'est
  affichée par cette page — ce n'est pas un trou fonctionnel, seulement une clause du ticket sans
  objet ici ; à ne pas transformer en fausse tâche (ne pas ajouter d'image factice pour justifier une
  logique de dégradation).
- Le critère « poids de page limité » n'est vérifiable par aucun test automatisé existant dans ce
  dépôt (pas d'outillage de mesure de bundle configuré) ; il est couvert uniquement par la contrainte
  « aucune nouvelle dépendance npm » de la tâche composant, à valider en revue de code plutôt que par
  un test.
