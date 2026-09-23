# Spec - #57 Frontend sans proxy vers l'API backend

## Résumé

Router `/api/*` vers le backend depuis le frontend, à la fois en production/Docker (nginx, via un template `envsubst` sur `SERVER_PORT`) et en développement local (`server.proxy` de Vite), sans introduire de `VITE_API_BASE_URL` ni de configuration CORS backend.

## Tâches

- [ ] Renommer `frontend/nginx.conf` en `frontend/nginx.conf.template` et y ajouter un bloc `location /api/` avec `proxy_pass http://backend:${SERVER_PORT}/api/;` placé **avant** le bloc `location / { try_files $uri /index.html; }`.
- [ ] Dans `frontend/nginx.conf.template`, ajouter les en-têtes de proxy usuels (`proxy_set_header Host $host;`, `proxy_set_header X-Real-IP $remote_addr;`, `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;`, `proxy_set_header X-Forwarded-Proto $scheme;`) sur le bloc `location /api/`.
- [ ] Modifier `frontend/Dockerfile` : remplacer `COPY nginx.conf /etc/nginx/conf.d/default.conf` par `COPY nginx.conf.template /etc/nginx/templates/default.conf.template` (mécanisme natif `docker-entrypoint.d/20-envsubst-on-templates.sh` de l'image `nginx:alpine`).
- [ ] Dans `frontend/Dockerfile`, définir `ENV NGINX_ENVSUBST_FILTER='^SERVER_PORT$'` (ou variable équivalente supportée par l'image `nginx:alpine` utilisée) avant l'étape `EXPOSE 80`, pour que `envsubst` ne substitue que `$SERVER_PORT` et laisse intactes les variables nginx internes (`$uri`, `$host`, etc.) dans le template.
- [ ] Dans `docker-compose.yml`, service `frontend` : ajouter `environment: SERVER_PORT: ${SERVER_PORT}`.
- [ ] Dans `docker-compose.yml`, service `frontend` : ajouter `depends_on: backend: condition: service_healthy`.
- [ ] Dans `frontend/vite.config.ts`, ajouter un bloc `server: { proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } } }` au `defineConfig`, sans toucher à la config `test` ni au plugin `VitePWA` existant.
- [ ] Mettre à jour `README.md` (racine) : tableau des URLs section « Lancer la stack complète avec Docker Compose » — ajouter une ligne précisant que `/api/*` sur `http://localhost:8081` est proxifié vers le backend, et ajouter la commande de vérification `curl -i http://localhost:8081/api/v1/postes` avec le résultat attendu (`HTTP/1.1 200`, `Content-Type: application/json`).
- [ ] Mettre à jour `frontend/README.md` : section « Lancer en local » — mentionner que `npm run dev` nécessite le backend démarré sur `localhost:8080` (`docker-compose up backend` ou `./mvnw spring-boot:run` depuis `backend/`) pour que les appels `/api/*` aboutissent via le proxy Vite, et ajouter la commande de vérification `curl -i http://localhost:5173/api/v1/postes` (même résultat attendu : JSON, pas `text/html`).
- [ ] Vérification manuelle documentée (voir Plan de tests) : exécuter `docker-compose up -d --build`, attendre `frontend` et `backend` `healthy`/`Up`, puis lancer la commande curl du README racine et confirmer `Content-Type: application/json`.
- [ ] Vérification manuelle documentée (voir Plan de tests) : lancer `docker-compose up backend` (ou `./mvnw spring-boot:run`) puis `npm run dev` dans `frontend/`, et lancer la commande curl du `frontend/README.md` et confirmer `Content-Type: application/json`.

## Contrat technique

### `frontend/nginx.conf.template` (nouveau contenu, remplace `frontend/nginx.conf`)

```nginx
server {
    listen 80;
    server_name _;
    root /usr/share/nginx/html;
    index index.html;

    location /api/ {
        proxy_pass http://backend:${SERVER_PORT}/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location / {
        try_files $uri /index.html;
    }
}
```

- `${SERVER_PORT}` est résolu par `envsubst` au démarrage du conteneur (valeur `.env` par défaut : `8080`, cf. `.env.example`).
- `$host`, `$remote_addr`, `$proxy_add_x_forwarded_for`, `$scheme` sont des variables nginx internes : elles ne doivent **pas** être substituées par `envsubst` — d'où le filtrage explicite (`NGINX_ENVSUBST_FILTER`) à `$SERVER_PORT` uniquement.

### `frontend/Dockerfile` (diff attendu)

```diff
-COPY nginx.conf /etc/nginx/conf.d/default.conf
+ENV NGINX_ENVSUBST_FILTER='^SERVER_PORT$'
+COPY nginx.conf.template /etc/nginx/templates/default.conf.template
```

### `docker-compose.yml` (service `frontend`, diff attendu)

```diff
 frontend:
   build:
     context: ./frontend
     args:
       VITE_SENTRY_DSN: ${VITE_SENTRY_DSN}
   image: samapiece-frontend:dev
+  environment:
+    SERVER_PORT: ${SERVER_PORT}
+  depends_on:
+    backend:
+      condition: service_healthy
   ports:
     - "${FRONTEND_HOST_PORT}:80"
```

### `frontend/vite.config.ts` (ajout dans `defineConfig`)

```ts
server: {
  proxy: {
    '/api': {
      target: 'http://localhost:8080',
      changeOrigin: true,
    },
  },
},
```

- Placé au même niveau que `plugins` et `test` dans l'objet passé à `defineConfig`.
- Ne modifie ni `plugins` (PWA/workbox) ni `test` (setup Vitest existant).

### README (contenu à ajouter)

- `README.md` racine, tableau des URLs : ligne `| Frontend → API backend | http://localhost:8081/api/v1/... (proxifié vers le backend) |` ou équivalent, plus un paragraphe avec :
  ```bash
  curl -i http://localhost:8081/api/v1/postes
  ```
  Attendu : `HTTP/1.1 200` et en-tête `Content-Type: application/json` (pas `text/html`).
- `frontend/README.md`, section « Lancer en local » : note que le backend doit tourner sur `localhost:8080`, plus :
  ```bash
  curl -i http://localhost:5173/api/v1/postes
  ```
  Attendu : même résultat (`Content-Type: application/json`).

## Plan de tests

| Critère d'acceptation (ticket #57) | Couverture |
|---|---|
| `frontend/nginx.conf` proxifie `/api/` vers `backend` (image Docker) | Vérification manuelle documentée : `docker-compose up -d --build`, attendre `backend` `healthy` et `frontend` `Up`, puis `curl -i http://localhost:8081/api/v1/postes` → attendu `Content-Type: application/json`. Aucun test automatisé (pas de framework e2e/intégration démarrant nginx dans le repo, cf. design § Hors périmètre). |
| `frontend/vite.config.ts` expose `server.proxy` pour `/api` en dev | Vérification manuelle documentée : backend démarré sur `localhost:8080` (`docker-compose up backend` ou `./mvnw spring-boot:run`), puis `npm run dev` dans `frontend/`, puis `curl -i http://localhost:5173/api/v1/postes` → attendu `Content-Type: application/json`. |
| Test/vérification documentée confirmant JSON (pas `index.html`) aux deux ports | Les deux commandes curl ci-dessus sont documentées telles quelles dans `README.md` et `frontend/README.md` (chemin exact, résultat attendu explicite) — reproductibles par tout développeur ou par la review, sans dépendance à un framework de test. |
| `README.md` mis à jour si la procédure de lancement change | Relecture du diff `README.md` : tableau des URLs + commande curl ajoutés. Pas de changement de procédure de lancement en tant que tel (`docker-compose up -d --build` reste identique), donc pas de réécriture de section, seulement l'ajout de la vérification et de la note sur `depends_on: backend` qui ralentit légèrement le démarrage du `frontend`. |

Aucun test unitaire/JUnit ni Vitest n'est ajouté ou modifié : le changement touche uniquement des fichiers de configuration infra (`nginx.conf.template`, `Dockerfile`, `docker-compose.yml`, `vite.config.ts`) et de la documentation, cohérent avec le design (§ Décisions clés et § Hors périmètre).

## Écarts identifiés

- Aucun écart bloquant entre le design et le ticket : les quatre critères d'acceptation sont couverts (proxy nginx prod, proxy Vite dev, vérification documentée, README mis à jour).
- Point de vigilance à trancher avant/pendant l'implémentation (déjà signalé par le design, repris ici pour le codeur) : confirmer que la variable `NGINX_ENVSUBST_FILTER` est bien supportée par la version d'image `nginx:alpine` épinglée dans `frontend/Dockerfile` (`FROM nginx:alpine AS runtime`, tag flottant non versionné explicitement). Si `NGINX_ENVSUBST_FILTER` n'est pas disponible sur l'image résolue au moment du build, `envsubst` substituera aussi `$host`, `$remote_addr`, `$proxy_add_x_forwarded_for`, `$scheme` et `$uri` (variables nginx, absentes de l'environnement du conteneur) par des chaînes vides, cassant `try_files $uri /index.html;` et les en-têtes de proxy. Le codeur doit vérifier le comportement réel au build (`docker-compose build frontend` puis inspecter `/etc/nginx/conf.d/default.conf` généré dans le conteneur) et, si nécessaire, comme repli, échapper les variables nginx dans le template (`$$uri`, `$$host`, etc., syntaxe reconnue par `envsubst`) plutôt que de compter sur le filtre.
