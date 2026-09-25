# Design — #62 Le bouton « Recevoir une alerte » ne crée jamais d'alerte

## Approche

Ticket purement frontend : brancher le bouton existant sur l'endpoint `POST /api/v1/alertes`
(public, livré par #22, contrat déjà stable) au lieu du `setState` factice actuel. On ajoute un
petit formulaire inline (téléphone) qui apparaît au clic, réutilise les critères déjà saisis dans
le formulaire de recherche (type, nom, prénom, numéro, date de naissance) et poste vers
`/api/v1/alertes`. Contrairement à `rechercher()` qui affiche un message 400 générique fixe,
ici il faut relayer le `message` renvoyé par le corps JSON d'erreur du backend (`ErreurReponse.message`),
car c'est explicitement ce que demande le critère d'acceptation « erreurs affichées clairement, pas
génériquement » et le backend fournit déjà des messages FR distincts par cas
(`CRITERES_INSUFFISANTS`, `CONTACT_INVALIDE`). Le prix de ce choix : le nouveau code d'appel API
diverge légèrement du pattern `RecherchePubliqueApiError` (qui ignore le corps sur 400) — c'est
assumé et documenté ci-dessous plutôt que de forcer artificiellement la cohérence.

Aucun nouveau module frontend `alertes/` n'est créé : il n'existe aujourd'hui aucun code frontend
touchant `/api/v1/alertes` (vérifié, aucun fichier ne matche `*alerte*` dans `frontend/src`), et ce
ticket est le seul consommateur de cet endpoint côté UI. L'ajout se fait donc directement dans
`recherche-publique/` (types + fonction d'appel + UI), en suivant le pattern déjà posé par #61
(CAPTCHA) dans ces mêmes fichiers plutôt que de créer une feature dédiée pour un seul appel.

## Fichiers/modules impactés

- `frontend/src/features/recherche-publique/types.ts` — ajouter les types de la requête/réponse
  de création d'alerte, ex. `CreerAlerteRequest` (typeDocument, nomTitulaire, prenomTitulaire,
  numeroDocument, dateNaissanceTitulaire, contact) et `CreerAlerteResponse` (`message: string`),
  miroir des records backend `sn.samapiece.alertes.web.CreerAlerteRequest` /
  `CreerAlerteResponse`.
- `frontend/src/features/recherche-publique/recherchePubliqueApi.ts` — ajouter une fonction
  `creerAlerte(payload: CreerAlerteRequest): Promise<CreerAlerteResponse>` qui POST vers
  `/api/v1/alertes` (nouvelle constante d'URL, différente de `BASE_URL` actuel qui vaut
  `/api/v1/recherche-publique`), et une erreur dédiée (ex. `AlerteApiError extends Error` portant
  `status` et le `message` FR lu dans le corps JSON `{ code, message }` sur 4xx) plutôt que de
  réutiliser `RecherchePubliqueApiError` qui masque le corps sur 400.
- `frontend/src/features/recherche-publique/RecherchePubliquePage.tsx` — remplacer le bloc
  `{alerteProposee ? <p>Cette fonctionnalité arrive bientôt.</p> : <button ...>}` par un bouton qui
  révèle un mini-formulaire (champ téléphone + bouton de soumission), un état de chargement,
  l'affichage de `CreerAlerteResponse.message` après succès (masquer le formulaire), et
  l'affichage du message d'erreur spécifique après échec sans reset de `resultat`.
- `frontend/src/features/recherche-publique/RecherchePubliquePage.test.tsx` — le test existant
  « propose une alerte quand aucun résultat n'est trouvé » (ligne ~71-89) attend actuellement le
  texte statique « Cette fonctionnalité arrive bientôt. » et `fetch` appelé une seule fois : il
  devra être réécrit (nouveaux tests couvrant saisie du contact, appel POST `/api/v1/alertes`,
  confirmation, et les cas d'erreur 400). C'est au spec-writer/codeur de le faire, mais il faut le
  signaler car ce test cassera sinon.

Aucun fichier backend n'est à modifier : l'endpoint, son contrat et sa gestion d'erreurs existent
déjà et sont conformes au besoin (vérifié dans `AlerteController`, `CreerAlerteRequest`,
`CreerAlerteResponse`, `AlerteExceptionHandler`, et l'autorisation publique dans `SecurityConfig` :
`POST /api/v1/alertes` est déjà `permitAll()`).

## Décisions clés

- **Un seul champ demandé au clic : le téléphone.** Le reste des critères (type, nom, prénom,
  numéro, date de naissance) est repris tel quel depuis l'état `formulaire` du composant — pas de
  ressaisie, conformément au critère d'acceptation. Le champ téléphone est local à la section
  alerte (pas dans `FormState` du formulaire de recherche), car il n'a de sens que dans ce contexte.
- **Validation du contact côté client minimale** : champ non vide avant l'appel API (pas de regex
  stricte côté frontend — le backend valide déjà via `NumeroTelephone.de(...)`, qui accepte soit un
  numéro local à 9 chiffres soit un E.164, et renvoie `CONTACT_INVALIDE` en 400 sinon). Éviter de
  dupliquer une règle métier déjà côté serveur.
- **Lecture du corps d'erreur pour l'appel alerte, contrairement à `rechercher()`.** Le backend
  renvoie systématiquement `{ code, message }` en 400 pour cet endpoint (voir
  `AlerteExceptionHandler`), avec un `message` déjà rédigé pour l'utilisateur final en français —
  on l'affiche directement plutôt que de coder un message générique par `code`, ce qui est plus
  simple et suffisant pour satisfaire le critère « erreurs affichées clairement, pas génériquement ».
- **Pas de réinitialisation du captcha/résultat de recherche en cas d'échec de création d'alerte.**
  L'erreur d'alerte reste locale à la section « Aucun résultat » ; elle ne doit pas écraser
  `erreurServeur` (réservé au flux de recherche/captcha) pour ne pas mélanger deux domaines
  d'erreurs différents à l'écran.
- **Après succès, le formulaire d'alerte est remplacé par le message de confirmation** renvoyé par
  l'API (`CreerAlerteResponse.confirmee()` → « Alerte enregistrée. Un lien de désinscription a été
  envoyé par SMS. »), sans bouton pour renvoyer une seconde alerte sur le même résultat (pas demandé
  par le ticket, évite le spam de SMS depuis l'UI).

## Risques / points d'attention

- Le test existant `RecherchePubliquePage.test.tsx` (« propose une alerte... ») va casser dès que
  le texte statique disparaît — à mettre à jour dans la même PR (le spec-writer doit le prévoir
  dans les critères de test).
- `dateNaissanceTitulaire` provient d'un `<input type="date">` (`YYYY-MM-DD`) — déjà envoyé tel
  quel dans `rechercher()` vers un champ backend `LocalDate` sans souci connu ; même format à
  réutiliser pour `creerAlerte`, aucune conversion supplémentaire nécessaire.
- Minimisation des données : ne pas afficher/loguer le numéro de téléphone saisi côté frontend
  au-delà du strict nécessaire (pas de `console.log`, pas de stockage local/`localStorage`) —
  cohérent avec le chiffrement du contact déjà fait côté backend
  (`AlerteContactChiffrementService`).
- L'endpoint est public et non protégé par un CAPTCHA (contrairement à la recherche) —
  `SecurityConfig` liste `POST /api/v1/alertes` en `permitAll()` sans mention de vérification
  captcha. Ce n'est pas dans le périmètre du ticket de l'ajouter, mais bon à savoir en cas d'abus
  signalé plus tard.
- Utiliser un nom d'erreur (`AlerteApiError` ou équivalent) distinct de
  `RecherchePubliqueApiError`/`CaptchaRequisApiError`, pour ne pas laisser croire dans le code que
  les deux domaines d'erreurs (recherche vs alerte) partagent la même sémantique de statut (428
  n'existe pas pour `/api/v1/alertes`).

## Hors périmètre

- Toute modification backend (l'endpoint, sa validation, le chiffrement du contact, l'envoi SMS)
  — déjà livré et fonctionnel par #22.
- Ajout d'un CAPTCHA ou d'une protection anti-abus sur `POST /api/v1/alertes` — non demandé.
- Gestion de la désinscription (`DELETE /api/v1/alertes/{id}`) côté frontend — hors périmètre de ce
  ticket, qui ne couvre que la création.
- Persistance ou affichage d'un historique des alertes créées par le citoyen — l'app publique est
  anonyme, aucun compte citoyen n'existe.
- Création d'une feature/module frontend `alertes/` dédié — un seul point d'appel ne le justifie
  pas ici.
