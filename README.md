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
décrit un environnement de **démo/suivi client gratuit** (backend, frontend, PostgreSQL, Redis),
distinct de la cible d'hébergement souverain visée pour la production (§11 de `PROJET-SAMAPIECE.md`).

**Décision explicite : uniquement des plans gratuits Render, jamais de service payant.** Conséquence :
Meilisearch, MinIO et RabbitMQ (qui n'ont pas d'équivalent gratuit exploitable sur Render — pas de
disque persistant sur le plan gratuit, données perdues à chaque veille du service) sont **volontairement
absents** de cette démo :
- **Recherche publique** : fonctionne quand même — `RecherchePubliqueService` retombe automatiquement
  sur une requête PostgreSQL directe dès que Meilisearch est indisponible (repli déjà présent dans le
  code, pas une dégradation introduite par ce déploiement).
- **Upload de photo de document** : non fonctionnel sur cette démo (MinIO absent), mais n'est de toute
  façon pas exposé dans l'interface actuelle (ticket #65 non livré) — aucune régression visible.
- **Notifications SMS asynchrones** : jamais délivrées sur cette démo (RabbitMQ absent). Vérifié
  empiriquement que l'absence de RabbitMQ ne bloque pas le démarrage du backend
  (`management.health.rabbit.enabled=false`, déjà configuré ; les listeners retentent la connexion en
  arrière-plan sans jamais faire échouer `/actuator/health`).

Si ces 3 services redeviennent nécessaires (au-delà d'une démo gratuite), un ticket dédié devra les
réintroduire avec un budget explicitement validé — voir l'historique git de `render.yaml` pour la
version "stack complète" précédente (avant cette révision).

**⚠️ Environnement de démonstration : ne jamais saisir de données personnelles citoyennes réelles**
(numéro de document, contact, etc.) sur cet environnement — seulement des données synthétiques de test.

### Déploiement initial (action humaine)

1. Créer un compte Render et connecter ce dépôt GitHub (ou utiliser le serveur MCP Render si configuré).
2. Dans le dashboard Render, choisir "New Blueprint" et pointer vers `render.yaml` à la racine du dépôt.
3. Render crée les 4 services et la base décrits dans `render.yaml`, tous en plan **gratuit**, mais **ne
   déploie rien tant que les secrets ne sont pas renseignés** : dans l'onglet "Environment" du groupe
   `samapiece-secrets`, saisir manuellement chaque valeur (JWT, clés de chiffrement, clé API SMS,
   informations du premier compte admin). Aucune de ces valeurs n'est ni ne doit être présente dans le
   fichier versionné. Les variables Meilisearch/MinIO/RabbitMQ sont en revanche déjà écrites en clair
   dans `render.yaml` : ce ne sont pas des secrets, seulement des valeurs non vides requises pour
   satisfaire la configuration du backend, ces services n'étant pas déployés.
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

Pas de CI/CD automatique : un push sur `main` ne déclenche aucun déploiement Render tant que
l'auto-deploy n'est pas activé manuellement dans le dashboard. Pour redéployer une nouvelle version,
utiliser le bouton "Manual Deploy" sur le service concerné (`samapiece-backend`/`samapiece-frontend`)
dans le dashboard Render.

### Risques et hypothèses non vérifiables sans exécution réelle

- **Base de données gratuite Render supprimée automatiquement après une durée limitée** (environ 30
  jours à la date d'écriture) : accepté comme coût du choix "gratuit". Il faudra recréer `samapiece-db`
  et rejouer la procédure de bootstrap admin (§ ci-dessus) périodiquement pour garder la démo utilisable.
- **Veille des services gratuits après ~15 minutes d'inactivité** : le premier accès après une période
  d'inactivité peut prendre quelques dizaines de secondes le temps que Render redémarre les services
  `samapiece-backend`/`samapiece-frontend`/`samapiece-redis` — comportement normal du plan gratuit, à
  ne pas confondre avec une panne.
- **TLS/authentification sur le Redis managé Render** : le code actuel ne configure ni mot de passe ni
  TLS pour Redis, et `management.health.redis.enabled=false` (fail-open déjà acté au ticket #19) masque
  silencieusement une mauvaise configuration côté `/actuator/health`. Une vérification manuelle du
  rate-limiting/CAPTCHA de la recherche publique (déclencher plusieurs recherches rapprochées et
  constater une limitation effective) est nécessaire après déploiement, pas seulement un health check.
- **Port détecté sans variable `PORT`** : l'hypothèse retenue est que Render détecte le port via `EXPOSE`
  dans les Dockerfiles backend/frontend (`runtime: docker`) sans imposer de variable `PORT` — à confirmer
  au premier déploiement.
- **Schéma exact du Blueprint Render** : le type exact du service Redis managé (`type: redis`) est une
  hypothèse non certifiée, marquée en commentaire directement dans `render.yaml` — à ajuster au moment
  du "Deploy Blueprint" sans que cela remette en cause l'architecture des 4 services.

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
