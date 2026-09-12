# Design — Ticket #2 : CI GitHub Actions (build + tests backend et frontend)

État constaté du repo (vérifié via `git log`, Read/Glob sur `pom.xml`, `backend/pom.xml`, `frontend/package.json`) : le ticket #1 est posé sur cette branche. Racine : `pom.xml` aggregator (`packaging=pom`, module unique `backend`). Backend : `backend/pom.xml`, parent `spring-boot-starter-parent:3.3.13`, Java 21, packaging `jar`. Frontend : `frontend/package.json` avec scripts npm réels `dev`, `build` (`tsc -b && vite build`), `lint` (`eslint .`), `preview`, `format`, `format:check` — **aucun script `test`**, aucun framework de test frontend installé (pas de Vitest/Jest dans `devDependencies`), aucun fichier `*.test.*`/`*.spec.*` sous `frontend/src`. Aucun `.github/workflows/` n'existe encore.

## Approche

Deux workflows GitHub Actions indépendants, déclenchés sur `push` et `pull_request`, chacun filtré par `paths:` sur son propre sous-arbre pour éviter de faire tourner Maven quand seul le frontend change (et inversement) :

- `.github/workflows/backend.yml` : déclenché sur `backend/**` et sur lui-même (`.github/workflows/backend.yml`). Un seul job `build` : checkout → setup Java 21 (Temurin) avec cache Maven intégré → `mvn -pl backend -am verify`. Le flag `-am` (also-make) est nécessaire car `backend` dépend du `pom.xml` racine agrégateur ; sans lui, si le module aggregator seul est modifié (rare) rien ne casserait, mais `-am` garantit que les modules requis sont construits même si l'invocation part de la racine.
- `.github/workflows/frontend.yml` : déclenché sur `frontend/**` et sur lui-même. Un seul job `build` avec `working-directory: frontend` : checkout → setup Node LTS avec cache npm intégré → `npm ci` → `npm run lint` → `npm run build`. Pas d'étape "tests" au sens strict pour ce ticket (voir décision ci-dessous).

Pour tenir sous 5 minutes sur un projet vide : cache Maven via `actions/setup-java` (`cache: maven`, clé basée sur les `pom.xml`) et cache npm via `actions/setup-node` (`cache: npm`, clé basée sur `frontend/package-lock.json`) — les deux actions gèrent le cache nativement, pas besoin d'`actions/cache` manuel. `npm ci` plutôt que `npm install` (déterministe, plus rapide, adapté à la CI). Un seul job par workflow (pas de matrice de versions) : le périmètre du ticket ne demande pas de tester plusieurs versions de Java/Node, et une matrice multiplierait le temps total sans bénéfice ici.

## Fichiers / modules impactés

- `.github/workflows/backend.yml` (nouveau)
- `.github/workflows/frontend.yml` (nouveau)

Rien d'autre n'est modifié : pas de changement dans `backend/pom.xml`, `frontend/package.json`, ni dans le code applicatif.

## Décisions clés

- **Actions et versions** : `actions/checkout@v4`, `actions/setup-java@v4` (`distribution: temurin`, `java-version: '21'`, `cache: maven`), `actions/setup-node@v4` (`node-version: 20`, `cache: npm`, `cache-dependency-path: frontend/package-lock.json`). Node 20 LTS choisi pour cohérence avec la décision déjà actée au ticket #1 (design.md #1, section "Décisions clés" : "Node 20 LTS"). Un `frontend/package-lock.json` doit exister (généré par le scaffold Vite/npm du ticket #1) pour que `npm ci` et le cache `setup-node` fonctionnent — le codeur doit vérifier sa présence dans le commit du ticket #1 avant d'écrire le workflow.
- **Stratégie de cache** : cache natif intégré aux actions officielles (`cache: maven` / `cache: npm`) plutôt qu'`actions/cache` manuel — moins de configuration, moins de risque d'erreur de clé de cache, suffisant pour ce périmètre.
- **Sens de "tests" côté frontend — décision tranchée : lint + build uniquement, pas d'ajout de framework de test dans ce ticket.** Justification : le ticket #1 n'a posé aucun framework de test frontend (ni Vitest, ni Jest, ni Testing Library) ni aucun script `test` dans `package.json`, et aucun composant non trivial n'existe encore à tester (`HomePage.tsx` statique). Le ticket #2 est un ticket d'infra CI ("Automatiser le build/tests à chaque push/PR") ; ajouter un framework de test complet (choix de Vitest vs Jest, config, premier test bidon) dépasse son périmètre déclaré et créerait un couplage arbitraire entre deux préoccupations distinctes. L'option d'ajouter dès maintenant une étape `npm test` conditionnelle/commentée dans le YAML n'est pas retenue (source de confusion, faux sentiment de couverture) ; le workflow n'appelle que `lint` + `build`, avec un commentaire explicite dans le YAML indiquant qu'une étape `test` sera ajoutée par le premier ticket qui introduit Vitest et de vrais tests de composants. Le critère d'acceptation "lint + build + tests sur chaque push/PR" est donc satisfait pour la partie testable existante ; le manque de tests unitaires réels est un point à signaler explicitement en review, pas à masquer.
- **Check requis avant merge (branch protection)** : non scriptable via un fichier YAML de workflow — c'est un réglage de dépôt (Settings → Branches → Branch protection rules → "Require status checks to pass before merging", en cochant les checks `backend` et `frontend` une fois qu'ils ont tourné au moins une fois). À documenter comme **étape manuelle post-ticket**, à réaliser par un mainteneur ayant les droits admin sur le repo GitHub, après le merge de ce ticket (les checks doivent exister — donc avoir tourné au moins une fois sur une PR — avant de pouvoir être cochés comme requis dans l'UI). Le spec-writer doit reprendre ce point dans les critères d'acceptation comme une tâche explicite non automatisable par le codeur.
- **Nommage des jobs** : le job de chaque workflow doit être nommé simplement (`build`) mais le fichier lui-même donne le contexte (`backend.yml`/`frontend.yml`) — le nom affiché sur la PR sera du type "backend / build", "frontend / build", ce qui est le libellé à cocher dans la branch protection.

## Risques / points d'attention

- **PR touchant à la fois `backend/**` et `frontend/**`** : avec des filtres `paths:` indépendants sur deux workflows séparés, GitHub évalue chaque workflow indépendamment sur le diff de la PR — si les deux sous-arbres sont touchés, **les deux workflows se déclenchent**, ce qui est le comportement voulu (aucun besoin de logique conditionnelle supplémentaire). Point d'attention réel : si une PR ne touche qu'un seul des deux sous-arbres, l'autre check ne se déclenche jamais — et un check GitHub "requis" qui ne se déclenche pas bloque indéfiniment le merge (statut "attendu" mais jamais reçu). Si les deux checks sont configurés comme requis en branch protection, il faut soit accepter ce comportement (une PR backend-only n'a pas besoin du check frontend, GitHub gère normalement ce cas via `paths:` + required checks sans blocage), soit prévoir un job "skip" trivial si des problèmes de check manquant apparaissent en pratique — à vérifier empiriquement après la première PR réelle plutôt qu'à sur-designer maintenant.
- **`paths:` sur push direct vs pull_request** : bien préciser `on: { push: { branches: [...], paths: [...] }, pull_request: { paths: [...] } }` pour couvrir les deux déclencheurs demandés par le ticket ("chaque push/PR").
- **Premier run sans cache** : le premier run de chaque workflow (cache froid) sera plus lent que les suivants ; le budget de 5 minutes doit être vérifié sur un run à cache chaud en conditions normales, pas nécessairement sur le tout premier run à blanc.
- **`mvn -pl backend -am verify` doit rester cohérent avec la commande citée dans le ticket** (`mvn -pl backend verify`) : le `-am` est une précision technique du design, pas un changement de comportement fonctionnel — à garder en tête si le spec-writer reprend la commande littéralement.

## Hors périmètre

- Déploiement, CD, environnements staging/prod (§11.9 du document produit couvre un pipeline complet avec déploiement — ce ticket ne couvre que l'intégration continue : build + tests).
- Tests d'intégration Testcontainers/PostgreSQL (mentionnés en §11.9) : pas de base de données réelle dans le projet à ce stade (cf. ticket #1, exclusion explicite de l'autoconfiguration datasource) — prématuré.
- Analyse de dépendances (OWASP Dependency-Check, mentionné en §11.9) : non demandé par les critères d'acceptation de ce ticket.
- Build/publication d'image Docker (ticket #3, cf. board).
- Ajout d'un framework de test frontend (Vitest/Jest) et des tests associés — décision explicitement tranchée ci-dessus, à traiter dans un ticket dédié.
- Configuration effective de la branch protection GitHub (droits admin requis, action manuelle post-merge documentée ci-dessus, non automatisable par un fichier versionné dans ce repo).
