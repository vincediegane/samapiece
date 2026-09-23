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
