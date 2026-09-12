# Review — Ticket #1 : Initialiser le monorepo (backend Spring Boot + frontend React/Vite)

## Verdict

APPROVE

## Critères d'acceptation

| # | Critère | Statut |
|---|---|---|
| 1 | `mvn -pl backend spring-boot:run` démarre un backend exposant `/actuator/health` qui répond 200. | **Couvert** — vérifié par le test automatisé `SamaPieceApplicationTests.healthEndpoint_shouldReturn200()` (MockMvc, statut 200) et par une exécution réelle : `mvn -pl backend spring-boot:run` démarre Tomcat sur le port 8080 et `curl -i http://localhost:8080/actuator/health` répond `200 {"status":"UP"}` sans authentification. |
| 2 | `npm run dev` dans `frontend/` démarre un serveur de dev Vite affichant une page d'accueil minimale. | **Couvert** — vérifié manuellement : `npm run dev` sert `http://localhost:5173`, le HTML retourné contient `<title>SamaPièce</title>` et monte `HomePage` (titre « SamaPièce » + accroche) via `main.tsx` → `App.tsx` → `HomePage.tsx`. Pas de test automatisé requis pour ce ticket (conforme au plan de tests). |
| 3 | Un `README.md` de chaque module explique comment lancer/tester localement. | **Couvert** — `backend/README.md` et `frontend/README.md` documentent prérequis, commande de lancement, profils, lint/format et build, chacun conforme au contenu attendu par la spec. |
| 4 | Aucun secret en dur dans le code (config via variables d'environnement). | **Couvert** — grep manuel (`password|secret|token`) sur `backend/src/main/resources` et `frontend/src` : seules les références `${DB_PASSWORD}` (interpolation d'env var, aucune valeur par défaut) apparaissent. `application-staging.yml`/`application-prod.yml` n'ont aucun défaut pour host/port/name/credentials, conformément au contrat technique. |

## Build/tests

Toutes les commandes ont été exécutées directement (pas seulement le rapport du codeur) :

- `mvn -pl backend test` → **BUILD SUCCESS**, `Tests run: 2, Failures: 0, Errors: 0` (`contextLoads`, `healthEndpoint_shouldReturn200`).
- `mvn -pl backend spring-boot:run` (exécution réelle, tuée après vérification) → démarrage OK, `curl -i http://localhost:8080/actuator/health` → `200 {"status":"UP"}`.
- `npm install` (frontend) → OK, `up to date, audited 178 packages`. Un warning `EBADENGINE` sur `eslint-visitor-keys@5.0.1` (requiert Node `^20.19.0 || ^22.13.0 || >=24`, environnement de test en `20.15.0`) — voir note ci-dessous, non bloquant.
- `npm run build` (frontend) → OK, `tsc -b && vite build` termine sans erreur, bundle généré.
- `npm run lint` (frontend) → OK, aucune erreur ESLint.
- `npm run format:check` (frontend) → OK, `All matched files use Prettier code style!`.
- `npm run dev` (frontend, exécution réelle, tué après vérification) → sert `http://localhost:5173`, HTML avec `<title>SamaPièce</title>` confirmé via `curl`.
- `git status --porcelain` après tous les runs (backend `target/`, frontend `node_modules/`+`dist/` générés localement) → arbre propre, aucun artefact de build tracké (gitignore racine + `frontend/.gitignore` corrects).
- Grep secrets (`password|secret|token` sur `backend/src/main/resources`, `frontend/src`, `backend/pom.xml`, `frontend/package.json`) → aucune valeur en dur, seules des interpolations `${VAR}`.

## Remarques (non bloquantes)

- **Node engine mismatch mineur** : `npm install` émet un warning `EBADENGINE` pour `eslint-visitor-keys@5.0.1` qui déclare requérir Node `^20.19.0` alors que la spec/README documentent un minimum `20.11.0` et que l'environnement de test (`20.15.0`) est sous ce seuil. `npm install`, `build`, `lint` et `format:check` passent malgré tout sans erreur ; ce n'est qu'un warning de résolution de dépendance transitive (`typescript-eslint`/`eslint`), pas un échec. À surveiller si un futur ticket relève ce sous-seuil en erreur stricte (`engine-strict`), mais rien à corriger dans ce ticket.

## Conformité à la spec

Tous les fichiers listés dans la checklist de `spec.md` sont présents et conformes au contrat technique : `pom.xml` racine (aggregator), `backend/pom.xml` (parent `spring-boot-starter-parent:3.3.13`, Java 21, dépendances exactes), les 6 `package-info.java` avec la Javadoc exacte demandée (y compris la note de traçabilité `retraitaudit`/`retrait_audit` dans `retraitaudit/package-info.java`), les 4 fichiers `application*.yml` avec les clés et commentaires TODO/ATTENTION exacts, le test `SamaPieceApplicationTests`, les deux README, la structure frontend exacte (`src/app/App.tsx`, `src/features/home/HomePage.tsx`, suppression de `App.tsx`/`App.css`/`assets` du template), `eslint.config.js` (flat config, `eslint-config-prettier` en dernier), `.prettierrc` (valeurs exactes), et la section « Conventions » du README racine. Aucun écart non documenté trouvé ; les écarts mentionnés dans la spec (`retraitaudit`, dépendance Actuator ajoutée, sécurité par défaut de l'actuator) sont correctement reflétés dans le code et confirmés par les tests/vérifications manuelles ci-dessus.
