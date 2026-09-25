# SamaPièce

Plateforme numérique de signalement et de récupération des pièces d'identité perdues au Sénégal.

- Contexte, objectifs, fonctionnalités, modèle de données et architecture technique complets : voir **[`PROJET-SAMAPIECE.md`](./PROJET-SAMAPIECE.md)** (ou sa version imprimable [`SamaPiece-Note-de-Projet.pdf`](./SamaPiece-Note-de-Projet.pdf)).
- Backlog du MVP : [Project board « SamaPiece »](https://github.com/users/vincediegane/projects/3/views/1).

## Stack

- **Backend** : Java 21, Spring Boot 3 (Spring Web, Spring Security, Spring Data JPA), PostgreSQL, Flyway.
- **Frontend** : React + Vite, PWA offline-first pour l'application agent.
- **Infra** : Docker, hébergement souverain visé (ADIE), Meilisearch, Redis, MinIO.

Détail complet des choix et de leur justification : §11 de `PROJET-SAMAPIECE.md`.

## Monorepo

- `backend/` : module Maven Spring Boot (voir [`backend/README.md`](./backend/README.md) pour lancer/tester localement).
- `frontend/` : application React/Vite (voir [`frontend/README.md`](./frontend/README.md) pour lancer/tester localement).

## Lancer la stack complète avec Docker Compose

Prérequis : Docker + Docker Compose installés localement.

```bash
cp .env.example .env
docker-compose up -d --build
```

Une fois `docker-compose ps` affiche tous les services en `healthy` (le `frontend` n'a pas de healthcheck, vérifier juste `Up`), les URLs suivantes sont disponibles :

| Service | URL |
|---|---|
| Backend (health) | http://localhost:8080/actuator/health |
| Frontend | http://localhost:8081 |
| Frontend → API backend | http://localhost:8081/api/v1/... (proxifié vers le backend) |
| Meilisearch | http://localhost:7700 |
| MinIO (console) | http://localhost:9001 |
| RabbitMQ (management) | http://localhost:15672 |

Le conteneur `frontend` (nginx) proxifie tout `/api/*` vers le backend (`location /api/` dans `frontend/nginx.conf.template`, résolue au démarrage via `envsubst` sur `SERVER_PORT`). Pour vérifier que le proxy fonctionne :

```bash
curl -i http://localhost:8081/api/v1/postes
```

Attendu : `HTTP/1.1 200` et un en-tête `Content-Type: application/json` (pas `text/html`, ce qui indiquerait que la requête est retombée sur `index.html`).

**Important** : `postgres`, `redis`, `meilisearch`, `minio` et `rabbitmq` démarrent avec la stack mais ne sont pas encore réellement utilisés par le backend à ce stade (cf. ticket #1) — leur présence ne garantit pas une intégration fonctionnelle. Le backend expose `/actuator/health` en `UP` sans jamais ouvrir de connexion JDBC vers `postgres`.

Pour arrêter proprement la stack et supprimer les volumes nommés (`postgres_data`, `minio_data`, `meili_data`) :

```bash
docker-compose down -v
```

## Profils Spring (dev/staging/prod)

Le backend supporte trois profils Spring : `dev` (défaut), `staging` et `prod`
(`backend/src/main/resources/application-{dev,staging,prod}.yml`). Le profil actif est
piloté par la variable d'environnement `SPRING_PROFILES_ACTIVE` (définie dans `.env`,
propagée au conteneur `backend` par `docker-compose.yml`) — aucune valeur n'est codée en
dur dans les fichiers versionnés. Le mot de passe et l'utilisateur de la base
(`DB_USER`, `DB_PASSWORD`) ne sont jamais définis en clair : ils sont lus depuis
l'environnement dans les trois profils, sans valeur par défaut en `staging`/`prod`, et
sans valeur par défaut sensible en `dev` non plus (seuls `DB_HOST`/`DB_PORT`/`DB_NAME` ont
un défaut non sensible en `dev`). Détail des profils et commande pour en changer :
voir la section [« Profils disponibles »](./backend/README.md#profils-disponibles) de
`backend/README.md`.

## Déploiement sur Render (démo)

Un [Blueprint Render](https://render.com/docs/blueprint-spec) versionné, [`render.yaml`](./render.yaml),
décrit la stack complète (backend, frontend, PostgreSQL, Redis, Meilisearch, MinIO, RabbitMQ) pour un
environnement de **démo/suivi client**, distinct de la cible d'hébergement souverain visée pour la
production (§11 de `PROJET-SAMAPIECE.md`). Ce déploiement n'a jamais été exécuté par le pipeline
d'agents de ce dépôt (aucun accès à un compte Render) : `render.yaml` est du code prêt à déployer, pas
la preuve d'un déploiement réel.

**⚠️ Environnement de démonstration : ne jamais saisir de données personnelles citoyennes réelles**
(numéro de document, contact, etc.) sur cet environnement — seulement des données synthétiques de test.

### Déploiement initial (action humaine)

1. Créer un compte Render et connecter ce dépôt GitHub.
2. Dans le dashboard Render, choisir "New Blueprint" et pointer vers `render.yaml` à la racine du dépôt.
3. Render crée les 7 services et la base décrits dans `render.yaml`, mais **ne déploie rien tant que les
   secrets ne sont pas renseignés** : dans l'onglet "Environment" du groupe `samapiece-secrets` (et des
   quelques variables déclarées `sync: false` directement sur le service `samapiece-backend`, voir
   commentaires dans `render.yaml`), saisir manuellement chaque valeur (JWT, clés de chiffrement,
   identifiants MinIO/RabbitMQ/Meilisearch, informations du premier compte admin). Aucune de ces valeurs
   n'est ni ne doit être présente dans le fichier versionné.
4. Avant le tout premier démarrage du service `samapiece-backend` avec `BOOTSTRAP_ADMIN_ENABLED=true` :
   se connecter à `samapiece-db` via le Shell Render (`psql`) et exécuter l'insertion manuelle d'une
   `Region`/un `Poste` de démonstration décrite dans
   [`backend/README.md` — "Bootstrap du premier compte administrateur"](./backend/README.md#bootstrap-du-premier-compte-administrateur),
   puis noter l'`id` du `Poste` créé pour la variable `BOOTSTRAP_ADMIN_POSTE_ID`.
5. Déployer le Blueprint. Les migrations Flyway s'exécutent **automatiquement au démarrage** du service
   `samapiece-backend` (même mécanisme qu'en local, aucune commande séparée à lancer) : vérifier les
   logs Render du service pour confirmer leur application, puis suivre la procédure de bootstrap admin
   ci-dessus (§ "Procédure" de `backend/README.md`) pour récupérer les identifiants du premier compte.

### Redéploiement

Pas de CI/CD automatique sur ce ticket : un push sur `main` ne déclenche aucun déploiement Render tant
que l'auto-deploy n'est pas activé manuellement dans le dashboard. Pour redéployer une nouvelle version,
utiliser le bouton "Manual Deploy" sur le service concerné (`samapiece-backend`/`samapiece-frontend`)
dans le dashboard Render.

### Risques et hypothèses non vérifiables sans compte Render réel

- **TLS/authentification sur le Redis managé Render** : le code actuel ne configure ni mot de passe ni
  TLS pour Redis, et `management.health.redis.enabled=false` (fail-open déjà acté au ticket #19) masque
  silencieusement une mauvaise configuration côté `/actuator/health`. Une vérification manuelle du
  rate-limiting/CAPTCHA de la recherche publique (déclencher plusieurs recherches rapprochées et
  constater une limitation effective) est nécessaire après déploiement, pas seulement un health check.
- **Port détecté sans variable `PORT`** : l'hypothèse retenue est que Render détecte le port via `EXPOSE`
  dans les Dockerfiles backend/frontend (`runtime: docker`) sans imposer de variable `PORT` — à confirmer
  au premier déploiement.
- **Coût des 3 Private Services** (`samapiece-meilisearch`, `samapiece-minio`, `samapiece-rabbitmq`) :
  facturation continue à l'heure, pas de tier gratuit pérenne — à valider avant de cliquer
  "Deploy Blueprint".
- **Plans payants Postgres/Redis** : les tiers gratuits Render pour ces deux services ont une durée de
  vie limitée, incompatible avec une URL démo stable ; le choix précis du plan est une décision humaine
  hors périmètre de ce dépôt.
- **Schéma exact du Blueprint Render** : plusieurs points de `render.yaml` (type exact du service Redis
  managé, support de `runtime: image`/`image.url` pour les `pserv` basés sur une image Docker Hub
  tierce, commande de démarrage/utilisateur root pour l'image MinIO, absence d'interpolation de variable
  dans une valeur `value:`) sont des hypothèses non certifiées, marquées en commentaire directement dans
  `render.yaml` — à ajuster au moment du "Deploy Blueprint" sans que cela remette en cause l'architecture
  des 7 services.
- **Ordonnancement de démarrage** : contrairement à `docker-compose.yml` (`depends_on`/
  `condition: service_healthy`), Render n'offre pas la même garantie de séquencement entre services — un
  premier déploiement peut nécessiter un redémarrage manuel de `samapiece-backend` si Meilisearch/MinIO/
  RabbitMQ ne sont pas encore prêts à son premier démarrage.

## Conventions

### Commits

[Conventional Commits](https://www.conventionalcommits.org/) : `<type>(<scope>): <description>` avec un type parmi `feat`, `fix`, `chore`, `docs`, `test`, `refactor`, et une référence optionnelle au numéro de ticket, par exemple :

```
feat(backend): ajoute le endpoint X (#12)
```

### Nommage de fichiers et de packages

- Packages Java : `sn.samapiece.<domaine>`, en minuscules, sans séparateur (ex. `sn.samapiece.enregistrement`).
- Dossiers frontend par feature : `src/features/<feature-kebab-case>/` (ex. `src/features/home/`).

## Développement assisté (`/bolt`)

Ce repo utilise un pipeline d'agents Claude Code (`.claude/commands/bolt.md`, `.claude/agents/bolt-*.md`) qui fait avancer un ticket du board à travers architecte → spec-writer → codeur → reviewer jusqu'à une PR en draft. Voir `.claude/commands/bolt.md` pour le détail du fonctionnement.
