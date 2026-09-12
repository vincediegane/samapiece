# Review — Ticket #2 : CI GitHub Actions (build + tests backend et frontend)

## Verdict

**APPROVE**

Le diff livré (`git diff bolt/issue-1-setup-monorepo-spring-react...HEAD`) ajoute strictement `.github/workflows/backend.yml`, `.github/workflows/frontend.yml`, `docs/bolts/2-ci-github-actions/design.md` et `docs/bolts/2-ci-github-actions/spec.md`. Les deux fichiers YAML sont **identiques caractère pour caractère** au contrat technique de `spec.md` (vérifié par `diff` entre le contenu commité `git show HEAD:.github/workflows/*.yml` et le bloc de code du contrat). Aucun autre fichier (`backend/pom.xml`, `frontend/package.json`, code applicatif) n'a été touché, conformément à la tâche "Ne modifier aucun autre fichier". Les commandes réelles invoquées par les workflows s'exécutent sans erreur en local (voir section Build/tests).

## Critères d'acceptation

1. **Workflow `backend.yml` : `mvn -pl backend verify` sur chaque push/PR touchant `backend/**`.** ✅ Conforme. Le workflow déclenche sur `push`/`pull_request` avec `paths: ['backend/**', 'pom.xml', '.github/workflows/backend.yml']` et exécute `mvn -pl backend -am verify` (le `-am` est un ajout justifié et documenté dans `design.md`/`spec.md` : nécessaire car `backend` dépend de l'aggregator racine — n'affaiblit pas le critère, le complète). Commande vérifiée localement avec succès (BUILD SUCCESS, 2 tests exécutés).

2. **Workflow `frontend.yml` : lint + build + tests sur chaque push/PR touchant `frontend/**`.** ⚠️ Conforme pour la partie testable existante, écart documenté et assumé. Le workflow exécute `npm ci` → `npm run lint` → `npm run build`, tous vérifiés localement avec succès. Il n'y a **pas d'étape de tests unitaires réels** car aucun framework de test frontend (Vitest/Jest) n'existe dans `frontend/package.json` à ce stade du projet (confirmé : pas de script `test`, pas de `devDependencies` de test, pas de fichier `*.test.*`/`*.spec.*`). Ce point est explicitement documenté comme écart assumé dans `spec.md` (section "Écarts identifiés") avec un commentaire inline dans le YAML lui-même et un renvoi à un futur ticket dédié à l'introduction de Vitest. Je considère cet écart comme correctement traité (signalé, pas caché) plutôt que comme un blocage — mais il reste un vrai gap fonctionnel par rapport à la lettre du critère #2 du ticket, à garder visible pour le backlog.

3. **Statut de build visible sur la PR (check requis avant merge).** ⏸️ Non vérifiable localement — à confirmer après push par l'orchestrateur. Le YAML est structurellement correct pour produire des checks nommés `backend / build` et `frontend / build` sur une PR (job unique nommé `build` par fichier, noms de fichiers distincts). L'activation effective de "Require status checks to pass before merging" en branch protection GitHub est une action manuelle nécessitant des droits admin, réalisable seulement après qu'au moins un run ait eu lieu — ceci est explicitement documenté dans `spec.md` comme hors périmètre scriptable, ce qui est la bonne posture. Non vérifiable sans push réel ni accès à l'API GitHub du repo ; ce n'est pas un échec du code livré.

4. **Temps d'exécution total < 5 min sur un projet vide.** ⏸️ Non vérifiable localement — à confirmer après push par l'orchestrateur (nécessite un run réel sur runner GitHub-hosted, à cache chaud, cf. Plan de tests de `spec.md`). Éléments de confiance indirects observés en local : le build backend Maven complet (compile + 2 tests + package + repackage Spring Boot) prend ~8 s ; `npm ci` (177 paquets) prend ~9 s, `lint` et `build` frontend sont quasi instantanés (<2 s cumulés). Sur un projet de cette taille, avec cache Maven/npm natif intégré aux actions officielles, un temps total < 5 min par workflow est hautement plausible, mais reste à confirmer empiriquement sur l'infrastructure réelle (latence de provisioning de runner, téléchargement de dépendances à froid, etc.), comme le prévoit déjà le plan de tests de la spec.

## Findings

Aucun finding bloquant. Deux observations mineures, non bloquantes :

- **Avertissement `EBADENGINE` local sur `eslint-visitor-keys@5.0.1`** (requiert Node `^20.19.0 || ^22.13.0 || >=24`, ma version locale est `20.15.0`) lors de `npm ci`. C'est un warning, pas une erreur — `npm ci` réussit et le build passe. Le workflow épingle `node-version: '20'`, ce qui sur `actions/setup-node@v4` résout vers la dernière patch de la branche 20.x disponible sur le runner GitHub-hosted (probablement ≥ 20.19), donc ce warning ne devrait pas apparaître en CI. À surveiller au premier run réel plutôt qu'à corriger maintenant.
- **`on:` interprété comme booléen par un parseur YAML strict (PyYAML)** : `yaml.safe_load` restitue la clé top-level `on` comme `True` (quirk connu de YAML 1.1, où `on`/`off`/`yes`/`no` sont des booléens). C'est cependant la syntaxe standard et universelle de tous les workflows GitHub Actions (le parseur de GitHub gère cette clé nativement) — pas un bug, juste une note pour un futur lint YAML strict qui pourrait le signaler à tort.

Aucun écart entre le YAML livré et le contrat technique de `spec.md` : `paths:`, `working-directory: frontend`, `cache-dependency-path: frontend/package-lock.json` (cohérent avec l'emplacement réel confirmé du fichier), versions d'actions (`checkout@v4`, `setup-java@v4`, `setup-node@v4`) sont tous conformes et cohérents avec le design.

## Build/tests

Commandes réellement invoquées par les workflows, relancées en local dans ce repo :

- `mvn -pl backend -am verify` → **BUILD SUCCESS**. `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`. Total time: 8.054 s.
- `cd frontend && npm ci` → succès (`added 177 packages`), avec un warning `EBADENGINE` non bloquant (voir Findings) et 2 vulnérabilités reportées par `npm audit` (moderate/high, préexistantes aux dépendances du ticket #1, hors périmètre de ce ticket CI).
- `npm run lint` (dans `frontend/`) → succès, sortie vide (aucune erreur ESLint), exit code 0.
- `npm run build` (dans `frontend/`) → succès (`tsc -b && vite build`), build Vite terminé en 1.00 s, artefacts générés dans `dist/`.

Vérification YAML :
- `.github/workflows/backend.yml` et `.github/workflows/frontend.yml` parsés sans erreur par PyYAML (`yaml.safe_load`) — structure top-level (`name`, `on`, `jobs`) valide sur les deux fichiers.
- Relecture manuelle de l'indentation et des clés : aucune anomalie (steps correctement imbriqués sous `jobs.build.steps`, `defaults.run.working-directory` correctement positionné au niveau du job dans `frontend.yml`).
- Contenu commité (`git show HEAD:.github/workflows/*.yml`) identique au contenu du working tree et identique au contrat technique de `spec.md` (diff vide sur les trois comparaisons).

Vérification prérequis :
- `frontend/package-lock.json` présent et suivi par git (confirmé).
- Aucun artefact de build (`backend/target`, `frontend/node_modules`, `frontend/dist`) n'est suivi par git — correctement ignoré via `.gitignore`.
