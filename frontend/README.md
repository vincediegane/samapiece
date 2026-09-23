# SamaPièce — Frontend

Application web React (scaffold Vite + TypeScript) du projet SamaPièce.

## Prérequis

- Node.js 20 LTS (minimum `20.11.0`)
- npm (livré avec Node, pas de yarn/pnpm)

## Lancer en local

```bash
npm install
npm run dev
```

Le serveur de développement Vite démarre sur `http://localhost:5173` et affiche la page
d'accueil (`src/features/home/HomePage.tsx`).

Le backend doit tourner sur `localhost:8080` pour que les appels `/api/*` aboutissent (proxy
`server.proxy` configuré dans `vite.config.ts`) : `docker-compose up backend` depuis la racine
du repo, ou `./mvnw spring-boot:run` depuis `backend/`. Pour vérifier que le proxy fonctionne :

```bash
curl -i http://localhost:5173/api/v1/postes
```

Attendu : `HTTP/1.1 200` et un en-tête `Content-Type: application/json` (pas `text/html`).

## Lint et formatage

```bash
npm run lint
npm run format:check
```

`npm run format` applique automatiquement le formatage Prettier.

## Build de production

```bash
npm run build
```

## Tests

Aucun framework de test automatisé n'est ajouté par ce ticket de scaffolding (voir le
design du ticket #1, section Hors périmètre). L'ajout de Vitest/Testing Library est prévu
par un ticket ultérieur.
