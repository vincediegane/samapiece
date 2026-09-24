# Spec — #61 CAPTCHA de la recherche publique (frontend)

## Résumé

`RecherchePubliquePage` gère désormais le cycle 428 -> défi CAPTCHA -> réponse -> nouvelle tentative de `POST /api/v1/recherche-publique`, sans jamais afficher de code HTTP brut au citoyen.

## Tâches

- [ ] `frontend/src/features/recherche-publique/types.ts` — ajouter `CaptchaDefi` (`{ captchaToken: string; question: string }`, miroir de `CaptchaController.CaptchaDefiResponse`) et `CaptchaReponsePayload` (`{ captchaToken: string; captchaReponse: string }`).
- [ ] `frontend/src/features/recherche-publique/recherchePubliqueApi.ts` — ajouter la classe `CaptchaRequisApiError extends RecherchePubliqueApiError` (statut figé à 428, message par défaut `'Vérification supplémentaire requise avant de poursuivre la recherche.'`).
- [ ] `frontend/src/features/recherche-publique/recherchePubliqueApi.ts` — ajouter `obtenirDefiCaptcha(): Promise<CaptchaDefi>` qui fait `GET /api/v1/recherche-publique/captcha` (chemin en dur, cohérent avec `BASE_URL` déjà codé en dur) et lève `RecherchePubliqueApiError('Erreur ${status}', status)` si `!reponse.ok`.
- [ ] `frontend/src/features/recherche-publique/recherchePubliqueApi.ts` — faire évoluer `rechercher(payload, captcha?: CaptchaReponsePayload)` : si `captcha` est fourni, ajouter les en-têtes `X-Captcha-Token` et `X-Captcha-Reponse` à la requête `POST` ; sur statut 428, lever `CaptchaRequisApiError` (avant le test du 400, avant le fallback générique).
- [ ] `frontend/src/features/recherche-publique/RecherchePubliquePage.tsx` — ajouter les états `defiCaptcha: CaptchaDefi | null`, `reponseCaptcha: string`, `payloadEnAttente: RecherchePubliqueRequest | null`.
- [ ] `frontend/src/features/recherche-publique/RecherchePubliquePage.tsx` — dans `soumettreFormulaire`, catch dédié : si `e instanceof CaptchaRequisApiError`, mémoriser `payload` dans `payloadEnAttente`, afficher le message neutre `'Vérification supplémentaire requise avant de poursuivre la recherche.'` via `erreurServeur`, puis charger un nouveau défi (`chargerNouveauDefi`).
- [ ] `frontend/src/features/recherche-publique/RecherchePubliquePage.tsx` — ajouter la fonction interne `chargerNouveauDefi()` : appelle `obtenirDefiCaptcha()`, en cas de succès fait `setDefiCaptcha(defi)` et `setReponseCaptcha('')` ; en cas d'échec, affiche `'Impossible de charger la vérification de sécurité. Réessayez plus tard.'` dans `erreurServeur` et réinitialise `defiCaptcha`/`payloadEnAttente` à `null` (le formulaire redevient utilisable).
- [ ] `frontend/src/features/recherche-publique/RecherchePubliquePage.tsx` — ajouter le handler `soumettreReponseCaptcha(evenement: FormEvent)` : appelle `rechercher(payloadEnAttente, { captchaToken: defiCaptcha.captchaToken, captchaReponse: reponseCaptcha.trim() })` ; en cas de succès, `setResultat`, `setAlerteProposee(false)` et réinitialiser tout l'état captcha (`defiCaptcha`, `reponseCaptcha`, `payloadEnAttente` à `null`/`''`) ; en cas de `CaptchaRequisApiError`, afficher le message d'échec `'Réponse incorrecte ou expirée. Une nouvelle question a été générée.'` puis appeler `chargerNouveauDefi()` (le nouveau défi remplace l'ancien, le token précédent n'est jamais réutilisé) ; toute autre erreur suit le chemin générique déjà existant (`e.message` ou message par défaut).
- [ ] `frontend/src/features/recherche-publique/RecherchePubliquePage.tsx` — bloc JSX conditionnel affiché quand `defiCaptcha !== null` : `<section aria-label="Vérification de sécurité">` contenant un `<form onSubmit={soumettreReponseCaptcha}>` avec un `<label className="field">` dont le texte est `defiCaptcha.question`, un `<input type="text">` lié (contrôlé par `reponseCaptcha`), et un bouton `type="submit"` `Valider` (désactivé si `enEnvoi`).
- [ ] `frontend/src/features/recherche-publique/RecherchePubliquePage.tsx` — désactiver tous les champs du formulaire de recherche existant et son bouton `Rechercher` (`disabled={enEnvoi || defiCaptcha !== null}`) tant que `defiCaptcha` n'est pas `null`, pour empêcher toute divergence entre le payload affiché et `payloadEnAttente`.
- [ ] `frontend/src/features/recherche-publique/RecherchePubliquePage.test.tsx` — nouveau test « parcours complet » : 1er `POST` -> 428, `GET /captcha` -> 200 (défi A), 2e `POST` avec en-têtes captcha -> 200 (résultat trouvé) ; vérifie l'affichage de la question, l'envoi des bons en-têtes sur le 2e `POST`, et l'affichage final du résultat.
- [ ] `frontend/src/features/recherche-publique/RecherchePubliquePage.test.tsx` — nouveau test « réponse incorrecte » : 1er `POST` -> 428, `GET /captcha` -> 200 (défi A), 2e `POST` avec en-têtes -> 428 à nouveau, `GET /captcha` -> 200 (défi B, question différente) ; vérifie le message d'échec affiché et le remplacement de la question par celle du défi B (jamais réutilisation du token A).
- [ ] `frontend/src/features/recherche-publique/RecherchePubliquePage.test.tsx` — assertion transverse dans les deux tests ci-dessus : aucun texte de la forme `Erreur 428` n'apparaît dans le document (`queryByText(/Erreur 428/)` doit être `null`).
- [ ] Test manuel (voir Plan de tests / Écarts identifiés) : valider en local/staging le parcours réel « 5 recherches infructueuses via l'UI -> 6e requête déclenche le défi -> réponse correcte -> recherche acceptée », puisque le seuil de 5 échecs consécutifs est une logique backend (#19) non reproductible fidèlement dans un test frontend mocké.

## Contrat technique

### Endpoints consommés (contrat backend existant, non modifié)

- `GET /api/v1/recherche-publique/captcha` → `200 OK`, corps `{ "captchaToken": string, "question": string }` (`CaptchaController.CaptchaDefiResponse`).
- `POST /api/v1/recherche-publique` :
  - `200 OK` → `RecherchePubliqueResponse` inchangé.
  - `428 PRECONDITION REQUIRED` → corps `{ "code": "CAPTCHA_REQUIS", "message": string, "captchaChallengeUrl": "/api/v1/recherche-publique/captcha" }` (`RecherchePubliqueExceptionHandler.CaptchaRequisReponse`). Le frontend ne lit que le statut HTTP, pas le corps de cette réponse d'erreur : `captchaChallengeUrl` est ignoré, `obtenirDefiCaptcha()` appelle le chemin en dur.
  - En-têtes de requête attendus quand un captcha est fourni : `X-Captcha-Token: <captchaToken>`, `X-Captcha-Reponse: <reponse texte>` (noms exacts confirmés dans `RecherchePubliqueCaptchaFilter`, ne pas typo-fauter la casse — Fetch normalise mais gardons la casse du backend pour lisibilité).

### `recherchePubliqueApi.ts`

```ts
export class CaptchaRequisApiError extends RecherchePubliqueApiError {
  constructor() {
    super('Vérification supplémentaire requise avant de poursuivre la recherche.', 428);
    this.name = 'CaptchaRequisApiError';
  }
}

export async function obtenirDefiCaptcha(): Promise<CaptchaDefi> {
  const reponse = await fetch(`${BASE_URL}/captcha`);
  if (!reponse.ok) {
    throw new RecherchePubliqueApiError(`Erreur ${reponse.status}`, reponse.status);
  }
  return reponse.json();
}

export async function rechercher(
  payload: RecherchePubliqueRequest,
  captcha?: CaptchaReponsePayload,
): Promise<RecherchePubliqueResponse> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (captcha) {
    headers['X-Captcha-Token'] = captcha.captchaToken;
    headers['X-Captcha-Reponse'] = captcha.captchaReponse;
  }
  const reponse = await fetch(BASE_URL, { method: 'POST', headers, body: JSON.stringify(payload) });
  if (!reponse.ok) {
    if (reponse.status === 428) throw new CaptchaRequisApiError();
    if (reponse.status === 400) throw new RecherchePubliqueApiError('Vérifiez les critères de recherche saisis.', 400);
    throw new RecherchePubliqueApiError(`Erreur ${reponse.status}`, reponse.status);
  }
  return reponse.json();
}
```

`captcha` est optionnel (paramètre en fin de signature) : tout appel existant `rechercher(payload)` reste valide sans modification.

### Messages utilisateur (jamais de code HTTP brut, tous affichés via le bloc `erreurServeur` existant)

| Situation | Message affiché |
|---|---|
| 1er 428 reçu sur la recherche (défi jamais tenté) | `Vérification supplémentaire requise avant de poursuivre la recherche.` |
| 428 reçu après soumission d'une réponse au défi (réponse fausse ou expirée) | `Réponse incorrecte ou expirée. Une nouvelle question a été générée.` |
| Échec réseau/autre statut sur `GET /captcha` | `Impossible de charger la vérification de sécurité. Réessayez plus tard.` |
| Autres erreurs (400, 5xx, réseau) | inchangé (comportement existant) |

### État local de `RecherchePubliquePage`

- `defiCaptcha: CaptchaDefi | null` — défi courant à afficher ; `null` = pas de vérification en cours.
- `reponseCaptcha: string` — valeur du champ de réponse, réinitialisée à chaque nouveau défi ou succès.
- `payloadEnAttente: RecherchePubliqueRequest | null` — dernier payload de recherche soumis, rejoué avec les en-têtes captcha lors de `soumettreReponseCaptcha`.
- Formulaire principal désactivé (`disabled`) tant que `defiCaptcha !== null`.

## Plan de tests

| Critère d'acceptation | Test |
|---|---|
| Sur 428, le frontend récupère le défi (`GET /captcha`) et le présente (question affichée) | `RecherchePubliquePage.test.tsx` — test « parcours complet » : assertion sur l'affichage de la question du défi A après le 1er 428. |
| La recherche suivante inclut la réponse dans `X-Captcha-Token`/`X-Captcha-Reponse` | `RecherchePubliquePage.test.tsx` — test « parcours complet » : assertion sur les `headers` du 2e appel `fetch` (`vi.mocked(fetch).mock.calls[2][1]?.headers`). |
| Message clair à chaque étape, jamais de code HTTP brut | `RecherchePubliquePage.test.tsx` — assertions textuelles sur les deux messages du tableau ci-dessus dans les tests « parcours complet » et « réponse incorrecte » ; assertion transverse `queryByText(/Erreur 428/)` absent. |
| Test couvrant le parcours complet : 5 échecs -> défi -> réponse correcte -> recherche acceptée | `RecherchePubliquePage.test.tsx` — test « parcours complet » (voir Écarts identifiés pour la portée exacte du « 5 échecs ») + test manuel listé dans les Tâches pour la boucle réelle des 5 échecs en environnement réel. |
| (Robustesse, non dans le ticket mais couverte par le design) réponse fausse/expirée déclenche un nouveau défi, jamais réutilisation de l'ancien token | `RecherchePubliquePage.test.tsx` — test « réponse incorrecte » : le 3e appel `fetch` (`GET /captcha` après le 2e 428) doit renvoyer un défi différent, et l'UI doit afficher la nouvelle question et non plus l'ancienne. |

## Écarts identifiés

- **Portée du « 5 échecs » de l'AC4** : le critère d'acceptation demande littéralement un test couvrant « 5 échecs -> défi affiché -> réponse correcte -> recherche acceptée ». Le comptage des 5 échecs consécutifs par IP est une logique 100% backend (`EchecRechercheCounterService`, déjà livrée et testée par #19) ; côté frontend il n'existe aucun moyen ni aucune nécessité de reproduire 5 appels réels pour obtenir ce comportement — le frontend ne fait que réagir à un unique statut 428, quel que soit le nombre de tentatives qui l'ont précédé. Le test frontend proposé simule directement le symptôme observable (428 dès le 1er appel mocké) plutôt que la séquence de 5 échecs. Cette interprétation est cohérente avec le découpage backend/frontend du design, mais elle laisse un trou de couverture end-to-end réel (vraies 5 recherches infructueuses via l'UI en conditions réelles) : couvert uniquement par le test manuel ajouté dans les Tâches. À confirmer que ce test manuel est suffisant avant fermeture du ticket, faute de test d'intégration Testcontainers combinant frontend et backend dans ce dépôt.
- **Aucun autre écart** entre `design.md` et le code réel : les noms de champs JSON (`captchaToken`, `question`, `code`, `message`, `captchaChallengeUrl`), les noms d'en-têtes (`X-Captcha-Token`, `X-Captcha-Reponse`) et le comportement fail-open Redis décrits dans le design correspondent exactement à `CaptchaController`, `RecherchePubliqueExceptionHandler` et `RecherchePubliqueCaptchaFilter`.
