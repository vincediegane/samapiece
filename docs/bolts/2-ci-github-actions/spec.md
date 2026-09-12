# Spec — Ticket #2 : CI GitHub Actions (build + tests backend et frontend)

## Résumé

Ajouter deux workflows GitHub Actions (`.github/workflows/backend.yml` et `.github/workflows/frontend.yml`) qui exécutent respectivement `mvn -pl backend -am verify` et `npm ci && npm run lint && npm run build`, déclenchés sur `push`/`pull_request` filtrés par `paths:`, avec cache natif Maven/npm.

## Tâches

- [ ] Créer le dossier `.github/workflows/` s'il n'existe pas.
- [ ] Créer `.github/workflows/backend.yml` avec le contenu exact donné en Contrat technique (job unique `build`, checkout, `actions/setup-java@v4` avec `cache: maven`, puis `mvn -pl backend -am verify`).
- [ ] Créer `.github/workflows/frontend.yml` avec le contenu exact donné en Contrat technique (job unique `build`, checkout, `actions/setup-node@v4` avec `cache: npm` et `cache-dependency-path: frontend/package-lock.json`, `working-directory: frontend` pour les steps npm, puis `npm ci` → `npm run lint` → `npm run build`).
- [ ] Vérifier que `frontend/package-lock.json` est bien commité sur la branche (prérequis pour `npm ci` et le cache `setup-node`) — confirmé présent sur `bolt/issue-1-setup-monorepo-spring-react`.
- [ ] Ne modifier aucun autre fichier (`backend/pom.xml`, `frontend/package.json`, code applicatif) : ce ticket est strictement l'ajout des deux fichiers YAML.
- [ ] Committer les deux fichiers, pousser la branche, ouvrir/mettre à jour la PR, et laisser les workflows tourner au moins une fois (voir Plan de tests).
- [ ] Documenter dans la PR (description ou commentaire) que l'activation de "Require status checks to pass before merging" (branch protection) reste une action manuelle post-merge à réaliser par un mainteneur admin — ne pas tenter de la scripter (voir Écarts identifiés).

## Contrat technique

### `.github/workflows/backend.yml`

```yaml
name: backend

on:
  push:
    branches:
      - '**'
    paths:
      - 'backend/**'
      - 'pom.xml'
      - '.github/workflows/backend.yml'
  pull_request:
    paths:
      - 'backend/**'
      - 'pom.xml'
      - '.github/workflows/backend.yml'

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 21 (Temurin)
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven

      - name: Build and test backend
        run: mvn -pl backend -am verify
```

### `.github/workflows/frontend.yml`

```yaml
name: frontend

on:
  push:
    branches:
      - '**'
    paths:
      - 'frontend/**'
      - '.github/workflows/frontend.yml'
  pull_request:
    paths:
      - 'frontend/**'
      - '.github/workflows/frontend.yml'

jobs:
  build:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: frontend
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up Node 20 LTS
        uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: npm
          cache-dependency-path: frontend/package-lock.json

      - name: Install dependencies
        run: npm ci

      - name: Lint
        run: npm run lint

      - name: Build
        run: npm run build

      # NOTE: pas d'étape "test" au sens strict ici — aucun framework de test
      # frontend (Vitest/Jest) n'est installé à ce stade (décision actée dans
      # design.md du ticket #2). Une étape `npm test` sera ajoutée par le
      # premier ticket qui introduit Vitest et de vrais tests de composants.
```

Notes de cohérence avec le design :
- `backend.yml` ajoute `pom.xml` (racine) aux `paths:` en plus de `backend/**`, car le job utilise `-am` (also-make) qui dépend de l'aggregator — un changement du `pom.xml` racine seul doit aussi déclencher le workflow.
- Les deux workflows incluent leur propre fichier dans `paths:` pour se re-déclencher sur leurs propres modifications.
- `push.branches: ['**']` couvre tous les push, conformément à "chaque push" du ticket ; `pull_request` sans filtre `branches` couvre toutes les PR par défaut.

## Plan de tests

Vérification critère par critère, après push de la branche et ouverture/mise à jour de la PR :

1. **`backend.yml` se déclenche et exécute `mvn -pl backend verify` sur `backend/**`** :
   - Pousser un commit touchant un fichier sous `backend/**` (ou ce commit de scaffolding CI lui-même touche les workflows).
   - `gh run list --workflow=backend.yml --branch bolt/issue-2-ci-github-actions` → un run doit apparaître.
   - `gh run view <run-id> --log` → confirmer que la commande exécutée est bien `mvn -pl backend -am verify` et qu'elle se termine en succès (build + tests backend passent).

2. **`frontend.yml` se déclenche et exécute lint + build + (pas de tests réels) sur `frontend/**`** :
   - `gh run list --workflow=frontend.yml --branch bolt/issue-2-ci-github-actions` → un run doit apparaître.
   - `gh run view <run-id> --log` → confirmer l'exécution successive de `npm ci`, `npm run lint`, `npm run build`, toutes en succès.

3. **Statut de build visible sur la PR** :
   - Ouvrir/mettre à jour la PR de la branche `bolt/issue-2-ci-github-actions`.
   - `gh pr checks <numéro-pr>` → les checks `backend / build` et `frontend / build` doivent apparaître avec leur statut (success/failure/pending).
   - Vérifier visuellement dans l'UI GitHub de la PR que les checks sont listés.

4. **Check requis avant merge** :
   - Non vérifiable par une commande automatisée dans ce ticket (dépend d'un réglage manuel de branch protection, cf. Écarts identifiés).
   - Une fois le réglage fait manuellement par un mainteneur admin (post-merge de ce ticket), vérifier via `gh api repos/<org>/<repo>/branches/main/protection` que `required_status_checks.contexts` contient `backend / build` et `frontend / build`.

5. **Temps d'exécution total < 5 min sur un projet vide** :
   - Après un premier run à cache froid (à ignorer pour la mesure, cf. design), déclencher un second run (nouveau commit ou re-run) pour bénéficier du cache Maven/npm chaud.
   - `gh run view <run-id> --json startedAt,updatedAt,jobs` (ou lecture directe de la durée affichée dans l'UI/`gh run list`) → calculer la durée de chaque run (`backend.yml` et `frontend.yml` séparément, car ce sont deux workflows indépendants).
   - Confirmer que chacun des deux workflows termine en moins de 5 minutes à cache chaud.

## Écarts identifiés

- **Branch protection ("check requis avant merge") non automatisable par ce commit.** Configurer "Require status checks to pass before merging" avec les checks `backend / build` et `frontend / build` est un réglage du dépôt GitHub (Settings → Branches → Branch protection rules), accessible uniquement à un mainteneur avec droits admin, et réalisable seulement après qu'au moins un run de chaque workflow ait eu lieu sur une PR. Cette étape reste **manuelle et hors périmètre de ce qu'un commit de code peut livrer** ; elle doit être effectuée après le merge de ce ticket. Le critère d'acceptation correspondant du ticket #2 ne sera donc pas cochable par le codeur/reviewer de ce pipeline, uniquement par un humain admin du repo par la suite.
- **Absence de tests frontend réels.** Le workflow `frontend.yml` exécute uniquement `lint` + `build`, pas de suite de tests unitaires/composants, car aucun framework de test frontend (Vitest/Jest) n'a été installé au ticket #1 et aucun script `test` n'existe dans `frontend/package.json`. Le critère d'acceptation "lint + build + tests" est donc satisfait pour la partie testable existante du projet, mais ne couvre pas de tests unitaires réels côté frontend — ce manque devra être comblé par un ticket dédié qui introduit Vitest (ou équivalent) et de vrais tests de composants, avec ajout d'une étape `npm test` dans `frontend.yml` à cette occasion.
