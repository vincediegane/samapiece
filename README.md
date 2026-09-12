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
