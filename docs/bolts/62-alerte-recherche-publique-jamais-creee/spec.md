# Spec — #62 Le bouton « Recevoir une alerte » ne crée jamais d'alerte

## Résumé

Le bouton "Recevoir une alerte..." de `RecherchePubliquePage` demande un numéro de téléphone puis
appelle réellement `POST /api/v1/alertes` avec les critères déjà saisis, affiche la confirmation
renvoyée par l'API en cas de succès et le message d'erreur spécifique du backend en cas d'échec, en
remplacement du texte statique "Cette fonctionnalité arrive bientôt.".

## Tâches

- [ ] **1. Types** — `frontend/src/features/recherche-publique/types.ts` : ajouter
  `CreerAlerteRequest` et `CreerAlerteResponse` (voir Contrat technique ci-dessous), miroir des
  records backend `sn.samapiece.alertes.web.CreerAlerteRequest` / `CreerAlerteResponse`.
- [ ] **2. Appel API** — `frontend/src/features/recherche-publique/recherchePubliqueApi.ts` :
  - ajouter une constante `ALERTES_URL = '/api/v1/alertes'` (distincte de `BASE_URL`) ;
  - ajouter la classe `AlerteApiError extends Error` portant `status: number` et `code: string | null`
    (nom distinct de `RecherchePubliqueApiError`/`CaptchaRequisApiError`, conformément à la décision
    du design) ;
  - ajouter `export async function creerAlerte(payload: CreerAlerteRequest): Promise<CreerAlerteResponse>`
    qui `POST` vers `ALERTES_URL` avec `Content-Type: application/json`, et sur réponse non-`ok` lit
    le corps JSON `{ code, message }` et lève `new AlerteApiError(message, status, code)` — avec un
    message de repli (`'Erreur lors de la création de l'alerte.'`) uniquement si le corps JSON est
    absent/invalide ou si `message` est vide (`try/catch` autour du `reponse.json()`).
- [ ] **3. UI — section "Aucun résultat"** — `frontend/src/features/recherche-publique/RecherchePubliquePage.tsx` :
  - retirer le bloc conditionnel `{alerteProposee ? <p>Cette fonctionnalité arrive bientôt.</p> : <button ...>}` ;
  - nouvel état local : `contactAlerte: string` (champ téléphone), `enEnvoiAlerte: boolean`,
    `erreurAlerte: string | null`, `confirmationAlerte: string | null` (remplace l'usage de
    `alerteProposee` comme simple booléen — conserver `alerteProposee` comme déclencheur d'affichage
    du mini-formulaire, ou le fusionner avec `confirmationAlerte === null && erreurAlerte === null`
    tant que la section est affichée) ;
  - au clic sur "Recevoir une alerte si cette pièce est déposée", afficher un mini-formulaire inline
    (label "Téléphone", `<input type="tel">`, bouton "Confirmer l'alerte") au lieu du texte statique ;
  - à la soumission du mini-formulaire : validation locale non vide (si vide, `erreurAlerte =
    'Le numéro de téléphone est requis.'`, ne pas appeler l'API) ; sinon `enEnvoiAlerte = true`,
    construire le payload à partir de `formulaire` (typeDocument, nomTitulaire, prenomTitulaire,
    numeroDocument, dateNaissanceTitulaire — même normalisation trim/`|| null` que dans
    `soumettreFormulaire`) + `contact: contactAlerte.trim()`, appeler `creerAlerte(payload)` ;
  - en cas de succès : `confirmationAlerte = reponse.message`, masquer le mini-formulaire (ne plus
    afficher le bouton "Recevoir une alerte..." ni de bouton pour renvoyer une alerte) ;
  - en cas d'échec (`AlerteApiError` ou autre) : `erreurAlerte = e.message` (le message métier FR du
    backend, ou le message de repli), garder le mini-formulaire affiché pour permettre une nouvelle
    tentative, **ne pas** toucher à `erreurServeur` ni à `resultat` ;
  - dans tous les cas, `enEnvoiAlerte = false` en `finally`.
- [ ] **4. Réinitialisation de l'état alerte entre deux recherches** — dans
  `RecherchePubliquePage.tsx`, réinitialiser tous les états liés à l'alerte (`alerteProposee`,
  `contactAlerte`, `enEnvoiAlerte`, `erreurAlerte`, `confirmationAlerte`) à chaque nouveau
  `setResultat(reponse)` réussi (déjà fait pour `alerteProposee` dans `soumettreFormulaire` et
  `soumettreReponseCaptcha` — étendre aux nouveaux états ajoutés à l'étape 3), pour éviter qu'une
  confirmation/erreur d'alerte d'une recherche précédente reste affichée sur un nouveau résultat.
- [ ] **5. Tests unitaires du module API** — si un fichier de test dédié à
  `recherchePubliqueApi.ts` existe déjà, y ajouter des cas pour `creerAlerte` (succès 201, erreur
  400 avec corps `{ code, message }`, erreur avec corps JSON invalide/absent → message de repli).
  S'il n'en existe pas, couvrir ces cas via les tests de composant de la tâche 6 (ne pas créer de
  nouveau fichier de test dédié pour un seul module déjà testé indirectement — vérifier d'abord
  l'existence de `recherchePubliqueApi.test.ts` avant de décider).
- [ ] **6. Tests de composant** — `frontend/src/features/recherche-publique/RecherchePubliquePage.test.tsx` :
  - réécrire le test existant « propose une alerte quand aucun résultat n'est trouvé » (lignes
    ~71-89) : après le clic sur le bouton, vérifier que le mini-formulaire (champ téléphone)
    apparaît et que le texte statique "Cette fonctionnalité arrive bientôt." n'est plus jamais
    rendu (`expect(screen.queryByText('Cette fonctionnalité arrive bientôt.')).not.toBeInTheDocument()`) ;
  - ajouter un test "crée une alerte avec succès et affiche la confirmation" : mock du 1er `fetch`
    (recherche → `RESULTAT_NON_TROUVE_MOCK`), clic sur "Recevoir une alerte...", saisie d'un
    téléphone, clic sur "Confirmer l'alerte", mock du 2e `fetch` (`POST /api/v1/alertes` → 201,
    `{ message: 'Alerte enregistrée. Un lien de désinscription a été envoyé par SMS.' }`),
    vérifier que le message de confirmation s'affiche et que le mini-formulaire disparaît ;
    vérifier aussi le corps de la requête POST envoyée (`typeDocument`, `nomTitulaire`,
    `numeroDocument`/`dateNaissanceTitulaire`, `contact`) ;
  - ajouter un test "affiche l'erreur métier renvoyée par l'API en cas de critères insuffisants" :
    2e `fetch` → `ok: false, status: 400, json: async () => ({ code: 'CRITERES_INSUFFISANTS',
    message: 'Critères de recherche insuffisants : type, nom, et numéro ou date de naissance sont
    requis.' })`, vérifier que ce message exact est affiché (pas un message générique), que le
    mini-formulaire reste affiché, et que `erreurServeur`/`resultat` restent inchangés ;
  - ajouter un test "affiche l'erreur métier renvoyée par l'API en cas de contact invalide" : 2e
    `fetch` → `ok: false, status: 400, json: async () => ({ code: 'CONTACT_INVALIDE', message: 'Le
    contact fourni est invalide.' })`, vérifier l'affichage de ce message ;
  - ajouter un test "bloque la soumission du formulaire d'alerte si le téléphone est vide" :
    clic sur "Recevoir une alerte...", clic direct sur "Confirmer l'alerte" sans saisir de
    téléphone, vérifier qu'aucun 2e appel `fetch` n'est fait et qu'un message d'erreur de validation
    local s'affiche.

## Contrat technique

**Types frontend** (`types.ts`) :

```ts
export interface CreerAlerteRequest {
  typeDocument: TypeDocument | null;
  nomTitulaire: string;
  prenomTitulaire: string | null;
  numeroDocument: string | null;
  dateNaissanceTitulaire: string | null; // format YYYY-MM-DD, tel que produit par <input type="date">
  contact: string;
}

export interface CreerAlerteResponse {
  message: string;
}
```

**Endpoint** : `POST /api/v1/alertes`, public (`permitAll()`, aucun header requis), pas de CAPTCHA.

- Succès : `201 Created`, corps `CreerAlerteResponse` — `{ "message": "Alerte enregistrée. Un lien
  de désinscription a été envoyé par SMS." }`.
- Échec `400 Bad Request`, corps `{ "code": string, "message": string }` :
  - `code = "CRITERES_INSUFFISANTS"` → `message = "Critères de recherche insuffisants : type, nom,
    et numéro ou date de naissance sont requis."`
  - `code = "CONTACT_INVALIDE"` → `message = "Le contact fourni est invalide."`
- Ces deux `message` doivent être affichés tels quels à l'utilisateur (pas de reformulation ni de
  message générique par `code`).

**Fonction d'appel** (`recherchePubliqueApi.ts`) :

```ts
export class AlerteApiError extends Error {
  constructor(message: string, public readonly status: number, public readonly code: string | null) { ... }
}

export async function creerAlerte(payload: CreerAlerteRequest): Promise<CreerAlerteResponse>;
```

**RBAC** : aucun — endpoint public, aucune modification de `SecurityConfig` nécessaire.

**UI** : dans la `section aria-label="Aucun résultat"`, remplacer :

```tsx
{alerteProposee ? (
  <p>Cette fonctionnalité arrive bientôt.</p>
) : (
  <button type="button" className="btn-outline" onClick={() => setAlerteProposee(true)}>
    Recevoir une alerte si cette pièce est déposée
  </button>
)}
```

par : bouton déclencheur inchangé dans son intitulé et son `onClick` (affiche le mini-formulaire) →
mini-formulaire avec `<label>` "Téléphone" + `<input type="tel">` + bouton "Confirmer l'alerte"
(`disabled={enEnvoiAlerte}`) → après succès, remplacement par un message de confirmation
(`role` non requis, simple `<p>`) affichant `confirmationAlerte` → en cas d'erreur, un `<span
role="alert">` (ou équivalent aux `field-error`/`alert-error` déjà utilisés ailleurs dans ce
composant) affichant `erreurAlerte`, à côté du mini-formulaire toujours visible.

## Plan de tests

| Critère d'acceptation (ticket #62) | Test |
|---|---|
| Le clic demande un moyen de contact et appelle réellement `POST /api/v1/alertes` avec les critères déjà saisis | Test de composant "crée une alerte avec succès" (tâche 6) : vérifie l'apparition du champ téléphone et le corps exact de la requête POST envoyée à `/api/v1/alertes` |
| Confirmation affichée après création réussie, basée sur le message de l'API | Même test : assertion sur le texte de confirmation = `CreerAlerteResponse.message` mocké |
| Erreurs affichées clairement, pas génériquement (ex. `AlerteCriteresInsuffisantsException`) | Tests de composant "erreur critères insuffisants" et "erreur contact invalide" (tâche 6) : assertion sur le message FR exact renvoyé par le mock, distinct entre les deux cas |
| Le message statique "Cette fonctionnalité arrive bientôt." est retiré | Test de composant réécrit (tâche 6, 1er point) : `queryByText('Cette fonctionnalité arrive bientôt.')` absent ; recherche globale dans le composant (revue de code) qu'aucune autre occurrence ne subsiste |
| (implicite) Validation locale minimale du contact avant appel API | Test "bloque la soumission du formulaire d'alerte si le téléphone est vide" (tâche 6, dernier point) |
| (implicite) Pas de pollution de l'état de recherche/captcha par une erreur d'alerte | Couvert dans les tests d'erreur (tâche 6) : assertion additionnelle que `resultat`/section "Aucun résultat" reste affichée et qu'aucun message générique de `erreurServeur` n'apparaît |
| (implicite) Pas de fuite du numéro de téléphone en dehors du payload API (pas de `console.log`, pas de `localStorage`) | Revue de code manuelle à l'implémentation — pas de test automatisé pertinent pour une absence de log |

## Écarts identifiés

Aucun écart entre `design.md` et le ticket : le design couvre les quatre critères d'acceptation de
façon explicite et cohérente (contact demandé + appel réel, confirmation via message API, erreurs
spécifiques non génériques, retrait du texte statique). Un point mineur à trancher au moment du
codage plutôt qu'un vrai écart : le design ne précise pas le libellé exact du bouton de soumission
du mini-formulaire ni le message d'erreur de validation locale du téléphone vide — cette spec fixe
ces libellés ("Confirmer l'alerte", "Le numéro de téléphone est requis.") comme valeurs par défaut,
à ajuster librement par le codeur si un besoin UX plus précis apparaît, sans impact sur le contrat
d'API ni les tests d'intégration.
