# Review - #57 Frontend sans proxy vers l'API backend

APPROVE

## Critères d'acceptation

| Critère (ticket #57) | Statut | Vérification |
|---|---|---|
| `frontend/nginx.conf` (image buildée par docker-compose) proxifie `/api/` vers le service `backend` | Couvert | `frontend/nginx.conf` supprimé, `frontend/nginx.conf.template` ajouté avec `location /api/` (`proxy_pass http://backend:${SERVER_PORT}/api/;` + 4 en-têtes de proxy), placé avant `location /`. Conforme au contrat de `spec.md`. `frontend/Dockerfile` copie le template vers `/etc/nginx/templates/default.conf.template` avec `ENV NGINX_ENVSUBST_FILTER='^SERVER_PORT$'`. Confirmé par build + inspection réelle du conteneur (voir Build/tests) : seul `$SERVER_PORT` est substitué, `$host`/`$remote_addr`/`$proxy_add_x_forwarded_for`/`$scheme`/`$uri` restent intacts. |
| `frontend/vite.config.ts` expose `server.proxy` pour `/api` en dev vers le backend local | Couvert | Bloc `server: { proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } } }` ajouté au même niveau que `plugins`/`test`, sans toucher à ces derniers. Confirmé par `npm run dev` + `curl http://localhost:5173/api/v1/postes` → `200`, `content-type: application/json`. |
| Test/vérification documentée confirmant que `GET /api/v1/postes` renvoie du JSON (pas `index.html`) sur les deux origines (8081 docker, port dev local) | Couvert | Documentation dans `README.md` racine et `frontend/README.md` (commandes `curl -i` + résultat attendu explicite). Aucun test automatisé, conforme au choix assumé dans `spec.md` (§ Plan de tests, pas de framework e2e dans le repo) — j'ai rejoué les deux vérifications moi-même (voir Build/tests) et elles se comportent exactement comme documenté. |
| `README.md` mis à jour si la procédure de lancement change | Couvert | `README.md` racine : ligne ajoutée au tableau des URLs + paragraphe + commande `curl` de vérification. `frontend/README.md` : section « Lancer en local » complétée (dépendance au backend sur `localhost:8080` + commande de vérification). La procédure de lancement elle-même (`docker-compose up -d --build`, `npm run dev`) n'a pas changé, ce qui est cohérent avec le contenu ajouté (documentation de vérification, pas de nouvelle étape). |

Diff conforme au contrat technique de `spec.md` quasi au caractère près (nginx.conf.template, Dockerfile, docker-compose.yml, vite.config.ts) — aucun écart non justifié constaté.

## Findings

Aucun finding bloquant.

Point non bloquant, hors périmètre du ticket #57 (signalé par le codeur, je confirme son analyse) :
- `docker-compose.yml`, service `backend` (ligne ~4-42, non modifiée par ce diff) : `depends_on` ne liste pas `postgres`. `git show main:docker-compose.yml` confirme que c'est préexistant sur `main`, inchangé par cette branche. Lancer `docker-compose up backend` isolément (sans `postgres` déjà démarré) peut faire échouer Flyway (`UnknownHostException: postgres`), mais `docker-compose up -d --build` sur la stack complète (le scénario documenté et testé par ce ticket) n'est pas affecté — confirmé lors de mon `docker compose up -d --build` : `backend` est passé `healthy` avant que `frontend` ne démarre, dans l'ordre attendu. Je recommande d'ouvrir un ticket séparé pour corriger `depends_on: backend: postgres: condition: service_healthy`, mais ce n'est pas un motif de blocage ici.

## Build/tests

- `npm run build` (frontend) → succès (`tsc -b && vite build`, PWA build inclus).
- `npm test -- --run` (frontend, Vitest) → 6 fichiers, **37 tests passés**.
- `npm run lint` (frontend, ESLint) → aucune erreur.
- `docker compose build frontend` → succès.
- `docker run --rm -e SERVER_PORT=8080 --entrypoint sh samapiece-frontend:dev -c "... cat /etc/nginx/conf.d/default.conf"` → confirmé : seul `${SERVER_PORT}` est résolu par `envsubst` (`proxy_pass http://backend:8080/api/;`), les variables nginx internes (`$host`, `$remote_addr`, `$proxy_add_x_forwarded_for`, `$scheme`, `$uri`) restent littérales dans le fichier généré. (`nginx -t` échoue dans ce run isolé faute de réseau Docker Compose pour résoudre l'hôte `backend` — attendu, non représentatif du run réel en stack.)
- `docker compose up -d --build` (stack complète) → tous les services `healthy`/`Up`, `backend` `healthy` avant `frontend` (confirme `depends_on: backend: condition: service_healthy`).
  - `curl -i http://localhost:8081/api/v1/postes` → `HTTP/1.1 200`, `Content-Type: application/json`, corps `[]`.
  - `curl -i http://localhost:8081/` → `HTTP/1.1 200 OK`, `Content-Type: text/html` (index.html, attendu).
  - `curl -i http://localhost:8081/some/unknown/spa/route` → fallback SPA correct vers `index.html` (pas de conflit avec `location /api/`).
- `docker compose stop frontend` puis `npm run dev` (frontend, backend/postgres/etc. toujours up) :
  - `curl -i http://localhost:5173/api/v1/postes` et `curl -i http://localhost:5174/api/v1/postes` (port de repli Vite) → `HTTP/1.1 200 OK`, `content-type: application/json`, corps `[]` dans les deux cas.
- Nettoyage effectué : `docker compose down -v`, processus `vite`/`node` de test arrêtés.

Toutes les vérifications reproduisent exactement ce que le codeur rapporte ; aucune divergence constatée.
