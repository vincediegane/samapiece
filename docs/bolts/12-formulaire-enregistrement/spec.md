# Spec — Ticket #12 : Formulaire agent d'enregistrement (frontend)

## Résumé

Ajout du module `frontend/src/features/pieces/` (types, appel API, formulaire + reçu à l'écran, test composant) consommant `POST /api/v1/pieces`, avec introduction de l'outillage Vitest/RTL et navigation par onglets dans `App.tsx`.

## Tâches

- [ ] **Outillage de test** — `frontend/package.json` : ajouter les devDependencies `vitest` (`^2.1.4`), `@testing-library/react` (`^16.0.1`), `@testing-library/jest-dom` (`^6.6.2`), `@testing-library/user-event` (`^14.5.2`), `jsdom` (`^25.0.1`) ; ajouter le script `"test": "vitest run"`. Exécuter `npm install` dans `frontend/` pour régénérer `frontend/package-lock.json` (requis pour que `npm ci` en CI reste cohérent).
- [ ] **Config Vitest** — `frontend/vite.config.ts` : remplacer l'import `defineConfig` de `'vite'` par celui de `'vitest/config'` (seul moyen propre de typer le bloc `test` sans triple-slash reference) et ajouter la config `test` (voir Contrat technique).
- [ ] **Setup RTL** — créer `frontend/src/test/setup.ts` important `@testing-library/jest-dom/vitest` (enregistre les matchers ET leurs types pour l'`expect` de Vitest, sans passer par des globals).
- [ ] **CI** — `.github/workflows/frontend.yml` : ajouter une étape `Test` (`run: npm test`) entre `Lint` et `Build`, en retirant le commentaire `NOTE:` obsolète (ce ticket est celui qui introduit Vitest, annoncé par ce commentaire).
- [ ] **Types** — créer `frontend/src/features/pieces/types.ts` : `TypeDocument`, `TYPE_DOCUMENT_LABELS`, `EtatDocumentOption`, `ETAT_DOCUMENT_OPTIONS`, `CreerPieceRequest`, `PieceResponse` (voir Contrat technique).
- [ ] **API** — créer `frontend/src/features/pieces/piecesApi.ts` : classe `PieceApiError` + fonction `creerPiece(payload)` (voir Contrat technique).
- [ ] **Formulaire** — créer `frontend/src/features/pieces/EnregistrementPiecePage.tsx` : composant page avec état de formulaire, validation client exhaustive, soumission, affichage du reçu, gestion des erreurs (voir Contrat technique).
- [ ] **Test composant** — créer `frontend/src/features/pieces/EnregistrementPiecePage.test.tsx` : scénarios de remplissage/soumission réussie, 401, 400, et un test par champ requis manquant (voir Plan de tests).
- [ ] **Navigation** — modifier `frontend/src/app/App.tsx` : remplacer le rendu direct de `AgentsPage` par un état local (`useState<'agents' | 'pieces'>('pieces')`) avec deux boutons pour basculer entre `AgentsPage` et `EnregistrementPiecePage` (voir Contrat technique).

## Contrat technique

### `frontend/src/features/pieces/types.ts`

```ts
export type TypeDocument =
  | 'CNI'
  | 'PASSEPORT'
  | 'PERMIS_CONDUIRE'
  | 'CARTE_ELECTEUR'
  | 'EXTRAIT_NAISSANCE'
  | 'CARTE_GRISE'
  | 'CARTE_CONSULAIRE'
  | 'AUTRE';

export const TYPE_DOCUMENT_LABELS: Record<TypeDocument, string> = {
  CNI: "Carte Nationale d'Identité (CNI)",
  PASSEPORT: 'Passeport',
  PERMIS_CONDUIRE: 'Permis de conduire',
  CARTE_ELECTEUR: "Carte d'électeur",
  EXTRAIT_NAISSANCE: 'Extrait de naissance',
  CARTE_GRISE: 'Carte grise',
  CARTE_CONSULAIRE: 'Carte consulaire',
  AUTRE: 'Autre',
};

export type EtatDocumentOption = 'Bon état' | 'Endommagé' | 'Illisible partiellement';

export const ETAT_DOCUMENT_OPTIONS: EtatDocumentOption[] = [
  'Bon état',
  'Endommagé',
  'Illisible partiellement',
];

export interface CreerPieceRequest {
  typeDocument: TypeDocument;
  nomTitulaire: string;
  prenomTitulaire: string;
  numeroDocument: string;
  dateNaissanceTitulaire: string | null; // "YYYY-MM-DD"
  dateDepot: string; // "YYYY-MM-DD"
  etatDocument: string | null;
  remarques: string | null;
}

export interface PieceResponse {
  id: string;
  numeroFiche: string;
  posteId: string;
  agentCreateurId: string;
  typeDocument: string;
  nomTitulaire: string;
  prenomTitulaire: string;
  numeroDocumentMasque: string;
  dateNaissanceTitulaire: string | null;
  dateDepot: string;
  etatDocument: string | null;
  statut: string;
  remarques: string | null;
  creeLe: string;
}
```

Miroir exact de `CreerPieceRequest.java` (8 champs, mêmes nullabilités : `@NotNull`/`@NotBlank` → requis, le reste optionnel) et `PieceResponse.java`. `typeDocument` dans `PieceResponse` reste `string` (valeur brute `.name()` renvoyée par le backend) ; le composant fait le lookup dans `TYPE_DOCUMENT_LABELS` avec un repli sur la valeur brute si absente.

### `frontend/src/features/pieces/piecesApi.ts`

```ts
import type { CreerPieceRequest, PieceResponse } from './types';

const BASE_URL = '/api/v1/pieces';

export class PieceApiError extends Error {
  constructor(message: string, public readonly status: number) {
    super(message);
    this.name = 'PieceApiError';
  }
}

function enTeteAutorisation(): HeadersInit {
  const jeton = window.localStorage.getItem('samapiece.accessToken') ?? '';
  return { Authorization: `Bearer ${jeton}`, 'Content-Type': 'application/json' };
}

export async function creerPiece(payload: CreerPieceRequest): Promise<PieceResponse> {
  const reponse = await fetch(BASE_URL, {
    method: 'POST',
    headers: enTeteAutorisation(),
    body: JSON.stringify(payload),
  });
  if (!reponse.ok) {
    if (reponse.status === 401) {
      throw new PieceApiError('Session expirée, reconnectez-vous.', 401);
    }
    if (reponse.status === 400) {
      throw new PieceApiError('Vérifiez les informations saisies.', 400);
    }
    throw new PieceApiError(`Erreur ${reponse.status}`, reponse.status);
  }
  return reponse.json();
}
```

Point important : contrairement à `agentsApi.creerAgent`, **ne jamais appeler `reponse.json()` sur une réponse en erreur** (ni pour 401 ni pour 400) — c'est la décision actée en design (corps non garanti dans les deux cas). Le message affiché à l'agent vient exclusivement du `status`.

### `frontend/src/features/pieces/EnregistrementPiecePage.tsx`

État du composant :

```ts
type ChampRequis = 'typeDocument' | 'nomTitulaire' | 'prenomTitulaire' | 'numeroDocument' | 'dateDepot';

interface FormState {
  typeDocument: TypeDocument | '';
  nomTitulaire: string;
  prenomTitulaire: string;
  numeroDocument: string;
  dateNaissanceTitulaire: string; // input type="date", '' si vide
  dateDepot: string;
  etatDocument: EtatDocumentOption | '';
  remarques: string;
}

const FORMULAIRE_INITIAL: FormState = {
  typeDocument: '',
  nomTitulaire: '',
  prenomTitulaire: '',
  numeroDocument: '',
  dateNaissanceTitulaire: '',
  dateDepot: '',
  etatDocument: '',
  remarques: '',
};

const [formulaire, setFormulaire] = useState<FormState>(FORMULAIRE_INITIAL);
const [erreursValidation, setErreursValidation] = useState<Partial<Record<ChampRequis, string>>>({});
const [erreurServeur, setErreurServeur] = useState<string | null>(null);
const [enEnvoi, setEnEnvoi] = useState(false);
const [recu, setRecu] = useState<PieceResponse | null>(null);
```

Fonction de validation (appelée en tout premier dans le submit handler, avant tout `fetch`) :

```ts
function validerFormulaire(f: FormState): Partial<Record<ChampRequis, string>> {
  const erreurs: Partial<Record<ChampRequis, string>> = {};
  if (!f.typeDocument) erreurs.typeDocument = 'Le type de document est requis.';
  if (!f.nomTitulaire.trim()) erreurs.nomTitulaire = 'Le nom du titulaire est requis.';
  if (!f.prenomTitulaire.trim()) erreurs.prenomTitulaire = 'Le prénom du titulaire est requis.';
  if (!f.numeroDocument.trim()) erreurs.numeroDocument = 'Le numéro du document est requis.';
  if (!f.dateDepot) erreurs.dateDepot = 'La date de dépôt est requise.';
  return erreurs;
}
```

Handler de soumission :

```ts
async function soumettreFormulaire(evenement: FormEvent) {
  evenement.preventDefault();
  setErreurServeur(null);
  const erreurs = validerFormulaire(formulaire);
  setErreursValidation(erreurs);
  if (Object.keys(erreurs).length > 0) return; // bloque avant tout fetch

  setEnEnvoi(true);
  try {
    const payload: CreerPieceRequest = {
      typeDocument: formulaire.typeDocument as TypeDocument,
      nomTitulaire: formulaire.nomTitulaire.trim(),
      prenomTitulaire: formulaire.prenomTitulaire.trim(),
      numeroDocument: formulaire.numeroDocument.trim(),
      dateNaissanceTitulaire: formulaire.dateNaissanceTitulaire || null,
      dateDepot: formulaire.dateDepot,
      etatDocument: formulaire.etatDocument || null,
      remarques: formulaire.remarques.trim() || null,
    };
    const resultat = await creerPiece(payload);
    setRecu(resultat);
    setFormulaire(FORMULAIRE_INITIAL);
    setErreursValidation({});
  } catch (e) {
    setErreurServeur(e instanceof Error ? e.message : 'Erreur inconnue lors de l’enregistrement.');
  } finally {
    setEnEnvoi(false);
  }
}
```

Champs du formulaire (chaque `<input>`/`<select>`/`<textarea>` enveloppé dans un `<label>` comme dans `AgentsPage`, pour que `screen.getByLabelText` fonctionne dans les tests) :

| Champ | Élément | Label affiché | Requis | Erreur affichée si vide |
|---|---|---|---|---|
| `typeDocument` | `<select>` avec option vide `disabled` en premier + une `<option>` par entrée de `TYPE_DOCUMENT_LABELS` | "Type de document" | oui | `erreursValidation.typeDocument`, `<span role="alert">` juste sous le select |
| `nomTitulaire` | `<input type="text">` | "Nom du titulaire" | oui | idem |
| `prenomTitulaire` | `<input type="text">` | "Prénom du titulaire" | oui | idem |
| `numeroDocument` | `<input type="text">` | "Numéro du document" | oui | idem |
| `dateNaissanceTitulaire` | `<input type="date">` | "Date de naissance du titulaire" | non | — |
| `dateDepot` | `<input type="date">` | "Date de dépôt" | oui | idem |
| `etatDocument` | `<select>` avec option vide en premier + `ETAT_DOCUMENT_OPTIONS` | "État du document" | non | — |
| `remarques` | `<textarea>` | "Remarques" | non | — |

Bouton `<button type="submit" disabled={enEnvoi}>Enregistrer la pièce</button>`.

Affichage des erreurs et du reçu :

```tsx
{erreurServeur && <p role="alert">{erreurServeur}</p>}

{recu && (
  <section aria-label="Reçu d'enregistrement">
    <h2>Fiche enregistrée</h2>
    <p>Numéro de fiche : <strong>{recu.numeroFiche}</strong></p>
    <p>Type de document : {TYPE_DOCUMENT_LABELS[recu.typeDocument as TypeDocument] ?? recu.typeDocument}</p>
    <p>Titulaire : {recu.prenomTitulaire} {recu.nomTitulaire}</p>
    <p>Numéro de document : {recu.numeroDocumentMasque}</p>
    <p>Date de dépôt : {recu.dateDepot}</p>
    <p>Statut : {recu.statut}</p>
  </section>
)}
```

Le reçu reste affiché jusqu'à la soumission suivante (pas de bouton "fermer" — hors périmètre du ticket).

### `frontend/vite.config.ts`

```ts
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
});
```

Ne pas ajouter `globals: true` (décision de design : imports explicites `describe`/`it`/`expect`/`vi` depuis `'vitest'` dans les fichiers de test, pas de globals, pas de modification d'`eslint.config.js`).

### `frontend/src/test/setup.ts`

```ts
import '@testing-library/jest-dom/vitest';
```

### `.github/workflows/frontend.yml`

Ajouter, entre l'étape `Lint` et l'étape `Build` :

```yaml
      - name: Test
        run: npm test
```

et supprimer le bloc de commentaire `# NOTE: pas d'étape "test" au sens strict ici — ...` devenu obsolète.

### `frontend/src/app/App.tsx`

```tsx
import { useState } from 'react';
import AgentsPage from '../features/agents/AgentsPage';
import EnregistrementPiecePage from '../features/pieces/EnregistrementPiecePage';

type Onglet = 'pieces' | 'agents';

function App() {
  const [onglet, setOnglet] = useState<Onglet>('pieces');

  return (
    <div>
      <nav>
        <button type="button" onClick={() => setOnglet('pieces')} disabled={onglet === 'pieces'}>
          Enregistrement pièces
        </button>
        <button type="button" onClick={() => setOnglet('agents')} disabled={onglet === 'agents'}>
          Gestion agents
        </button>
      </nav>
      {onglet === 'pieces' ? <EnregistrementPiecePage /> : <AgentsPage />}
    </div>
  );
}

export default App;
```

`EnregistrementPiecePage` devient l'onglet par défaut (page d'atterrissage naturelle pour un agent) ; `AgentsPage` reste accessible via le second bouton.

## Plan de tests

Tous dans `frontend/src/features/pieces/EnregistrementPiecePage.test.tsx`, via `@testing-library/react` + `@testing-library/user-event`, en mockant `global.fetch` (`vi.fn()`) et en posant un jeton factice dans `window.localStorage` (`samapiece.accessToken`) avant chaque test.

| Critère d'acceptation du ticket | Test | Assertion clé |
|---|---|---|
| Formulaire avec tous les champs, validation côté client avant envoi | `it('affiche les 8 champs du formulaire')` | `getByLabelText` pour chacun des 8 champs du tableau ci-dessus est présent |
| idem — validation bloquante | 5 tests, un par champ requis : `it('bloque la soumission si typeDocument est manquant')`, idem `nomTitulaire`, `prenomTitulaire`, `numeroDocument`, `dateDepot` | Après `submit` avec un seul champ requis vide (les autres requis remplis) : le message d'erreur correspondant est affiché (`getByText`), et `fetch` **n'a pas été appelé** (`expect(fetch).not.toHaveBeenCalled()`) |
| Affichage du reçu/numéro de fiche après soumission réussie | `it('affiche le reçu après soumission réussie')` : remplit uniquement les 5 champs requis (les optionnels restent vides), `fetch` mocké résout `{ ok: true, status: 201, json: async () => ({...PieceResponse mocké, numeroFiche: 'PC-ABCDEF01-2026-00001'}) }`, submit | `getByText(/PC-ABCDEF01-2026-00001/)` présent à l'écran ; le formulaire est réinitialisé (`getByLabelText('Nom du titulaire')` a la valeur `''`) |
| Gestion des erreurs serveur — session expirée | `it('affiche un message de session expirée sur 401')` : `fetch` mocké résout `{ ok: false, status: 401 }` (pas de `.json()` appelé sur le mock, ou mock qui rejette si appelé, pour vérifier que le composant ne le lit pas) | `getByText('Session expirée, reconnectez-vous.')` présent |
| Gestion des erreurs serveur — champ invalide / erreur générique | `it('affiche un message générique sur 400')` : `fetch` mocké résout `{ ok: false, status: 400 }` | `getByText('Vérifiez les informations saisies.')` présent |
| Test composant couvrant remplissage + soumission réussie | Le test "affiche le reçu après soumission réussie" ci-dessus, exécuté via `userEvent.type`/`userEvent.selectOptions` pour chaque champ requis puis `userEvent.click` sur le bouton submit | Couvre l'intégralité du parcours remplissage → soumission → affichage |

Tests additionnels recommandés (non exigés par un critère précis mais couverts par le même fichier sans coût significatif) :
- `it('n\'exige pas les champs optionnels')` : soumission réussie sans `dateNaissanceTitulaire`/`etatDocument`/`remarques` renseignés (déjà couvert par le test de succès principal si celui-ci ne remplit que les champs requis — ne pas dupliquer).

## Écarts identifiés

- **Étape CI de test non tranchée par le design** : `design.md` (section Risques) signale le risque sans trancher ("vérifier que la CI n'échoue pas faute d'exécuter ce script"). Le commentaire déjà présent dans `.github/workflows/frontend.yml` indique explicitement que ce ticket est celui qui doit ajouter l'étape `npm test`. Cette spec tranche : ajouter l'étape `Test` dans le workflow (tâche dédiée ci-dessus) plutôt que de laisser le script `test` inexploité par la CI.
- **Message d'erreur "champ invalide" du critère d'acceptation vs. décision de design** : le critère d'acceptation du ticket mentionne un message clair pour "champ invalide", ce qui suggère une granularité par champ également côté serveur. Le design a tranché que le backend actuel (`PieceController`, pas de `@ExceptionHandler(MethodArgumentNotValidException)`, `server.error.include-message` non configuré) ne permet pas de restituer un détail par champ sur un 400 résiduel (contournement de la validation client) : seul un message générique ("Vérifiez les informations saisies.") sera affiché dans ce cas. C'est une limitation connue et assumée par le design, pas comblée par ce ticket (modification du backend hors périmètre) ; la validation client exhaustive (tâche "Formulaire") est le mécanisme qui couvre en pratique la quasi-totalité des cas réels de "champ invalide" pour un agent utilisant l'UI normalement. À signaler si le product owner exige un message serveur précis par champ — nécessiterait un ticket backend séparé.
