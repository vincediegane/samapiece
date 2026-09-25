# Review — #62 Le bouton « Recevoir une alerte » ne crée jamais d'alerte

APPROVE

## Critères d'acceptation

| Critère | Statut |
|---|---|
| Le clic sur "Recevoir une alerte..." demande un moyen de contact (téléphone) et appelle réellement `POST /api/v1/alertes` avec les critères déjà saisis | Couvert — `RecherchePubliquePage.tsx:142-170` (`soumettreAlerte`) appelle `creerAlerte` avec `formulaire` + `contact` ; test « crée une alerte avec succès... » vérifie l'URL exacte et le corps JSON (`typeDocument`, `nomTitulaire`, `numeroDocument`, `contact`). Le test échouerait sans l'appel réel (retour au texte statique). |
| Confirmation affichée après création réussie (message de l'API) | Couvert — `setConfirmationAlerte(reponse.message)` puis rendu ligne 273-274 ; test vérifie le texte exact renvoyé par le mock backend et la disparition du formulaire. |
| Erreurs (critères insuffisants, `AlerteCriteresInsuffisantsException`, contact invalide) affichées clairement, pas génériquement | Couvert — `AlerteApiError`/`creerAlerte` (`recherchePubliqueApi.ts:30-95`) lit `{code, message}` du corps 400 et relaie le `message` FR tel quel ; deux tests dédiés (`CRITERES_INSUFFISANTS`, `CONTACT_INVALIDE`) vérifient l'affichage du message exact et l'absence de message générique, sans toucher `erreurServeur`/`resultat` (vérifié par l'assertion sur la région "Aucun résultat"). |
| Le message statique "Cette fonctionnalité arrive bientôt." est retiré | Couvert — absent de `RecherchePubliquePage.tsx` (grep confirmé, seule occurrence restante est l'assertion négative dans le test). |

Critère de validation locale (non-vide) et de non-régression (2e appel fetch bloqué si téléphone vide) également couverts par un test dédié.

## Conformité à la spec

- Types (`CreerAlerteRequest`/`CreerAlerteResponse`) miroir exact des records backend `sn.samapiece.alertes.web.CreerAlerteRequest`/`CreerAlerteResponse` (mêmes champs, mêmes types nullables) — vérifié champ à champ.
- `recherchePubliqueApi.ts` : `ALERTES_URL`, `AlerteApiError(status, code)`, `creerAlerte` avec repli sur message générique si corps absent/invalide — conforme à la tâche 2.
- UI : mini-formulaire déclenché par le bouton existant, validation locale non vide, gestion succès/échec, `enEnvoiAlerte` en `finally` — conforme à la tâche 3.
- Réinitialisation (`reinitialiserAlerte`) appelée à chaque nouveau résultat de recherche réussi et à chaque réponse captcha réussie (`soumettreFormulaire` et `soumettreReponseCaptcha`, chemins de succès) — conforme littéralement à la tâche 4 ("nouveau résultat de recherche/captcha réussi"). Note mineure : sur le chemin "captcha requis" (échec initial), l'ancien état d'alerte (confirmation/erreur) n'est pas réinitialisé avant l'affichage du captcha — mais l'ancien `resultat` n'est pas non plus effacé dans ce cas, donc le comportement est cohérent avec le pattern existant du composant (pas une régression introduite par ce ticket, hors périmètre de la spec).
- Pas de fichier de test API dédié : conforme à la tâche 5 ("couverts via tests de composant si pas de fichier dédié existant" — aucun fichier `recherchePubliqueApi.test.ts` n'existait avant ce ticket).
- 4 nouveaux tests + réécriture de l'existant : conforme à la tâche 6 (succès avec vérification du corps POST exact, `CRITERES_INSUFFISANTS`, `CONTACT_INVALIDE`, validation locale téléphone vide).
- Aucune modification backend, endpoint déjà `permitAll()` en écriture (`SecurityConfig.java:94`), hashage du numéro de document et chiffrement du contact déjà en place côté `AlerteService`/`AlerteContactChiffrementService` (livré par #22, hors périmètre de #62) — conforme à §10 PROJET-SAMAPIECE.md, pas de régression introduite.
- Pas de `console.log`/persistance locale du téléphone saisi — conforme au point de vigilance du design.md.

## Findings

Aucun finding bloquant. Aucune faille de sécurité, aucune régression, aucun bug de logique identifié dans le diff.

Remarque non bloquante (à surveiller, hors périmètre du ticket) : sur le chemin d'échec "captcha requis" d'une recherche ultérieure, une confirmation/erreur d'alerte affichée pour un résultat précédent reste visible sous le nouveau formulaire captcha tant que celui-ci n'a pas été résolu avec succès. Comportement conforme au libellé littéral de la spec ("nouveau résultat de recherche/captcha réussi") mais pourrait surprendre un testeur manuel ; à évaluer dans un futur ticket si signalé.

## Build/tests

- `npm test -- --run` (frontend) : 11 fichiers, 71 tests, tous passés (dont les 6 tests de `RecherchePubliquePage.test.tsx` liés à l'alerte).
- `npm run build` (tsc -b && vite build) : succès, aucune erreur TypeScript.
- `npm run lint` (eslint) : succès, aucune erreur.
- `npm run format:check` (prettier) : 49 fichiers signalés, dont les 3 fichiers modifiés par ce ticket (`RecherchePubliquePage.tsx`, `recherchePubliqueApi.ts`, `types.ts`). Vérifié par comparaison directe avec `main` (checkout temporaire, sans modification committée) : les mêmes fichiers sont déjà non formatés sur `main` (50 fichiers avant ce ticket, incluant `recherche-publique/*`), confirmant que ce défaut préexiste globalement au repo et n'est pas introduit par ce bolt. Non bloquant.
- Backend : aucun fichier `backend/` modifié dans le diff (`git diff main...HEAD --name-only`) — `mvn test` non pertinent pour ce ticket, endpoint `/api/v1/alertes` déjà livré et testé par #22.
