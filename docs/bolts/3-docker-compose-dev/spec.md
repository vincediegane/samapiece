# Spec — Ticket #3 : Dockeriser backend/frontend + docker-compose de dev

## Résumé

Fournir deux `Dockerfile` multi-stage (backend Spring Boot, frontend Vite/nginx), un `docker-compose.yml` racine assemblant ces deux services avec postgres/redis/meilisearch/minio/rabbitmq, et un `.env.example` complet, de sorte qu'un développeur sur une machine propre puisse lancer toute la stack avec `cp .env.example .env && docker-compose up -d --build`.

## Tâches

- [ ] `backend/Dockerfile` — multi-stage `build` (Maven) → `runtime` (JRE seul), utilisateur non-root, `ENTRYPOINT` via `JarLauncher`.
- [ ] `backend/.dockerignore` — exclut `target/`, `.git`, `*.md`, fichiers IDE.
- [ ] `frontend/Dockerfile` — multi-stage `build` (Node/Vite) → `runtime` (nginx statique).
- [ ] `frontend/.dockerignore` — exclut `node_modules/`, `dist/`, `.git`.
- [ ] `frontend/nginx.conf` — config nginx minimale avec fallback SPA (`try_files ... /index.html`).
- [ ] `docker-compose.yml` (racine) — sept services : `backend`, `frontend`, `postgres`, `redis`, `meilisearch`, `minio`, `rabbitmq`.
- [ ] `.env.example` (racine) — toutes les variables consommées par `docker-compose.yml`, avec valeurs de dev par défaut non sensibles.
- [ ] Mise à jour de `README.md` (racine) — section "Lancer la stack complète avec Docker Compose" : prérequis (`cp .env.example .env`), commande de démarrage, liste des URLs exposées, commande d'arrêt propre, et le rappel explicite que `postgres`/`redis`/etc. démarrent mais ne sont pas encore réellement consommés par le backend (cf. Écarts identifiés).

Aucun autre fichier ne doit être créé ou modifié (pas de changement au `.gitignore`, déjà correct — cf. design).

## Contrat technique

### `backend/Dockerfile` (contexte de build = `backend/`)

Stages nommés, dans cet ordre exact, avec ce COPY-ordering pour maximiser le cache Docker :

```
# ---- Stage "build" ----
FROM maven:3.9.16-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package
RUN java -Djarmode=layertools -jar target/backend-*.jar extract --destination extracted

# ---- Stage "runtime" ----
FROM eclipse-temurin:21.0.12_8-jre-alpine-3.24 AS runtime
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=build /app/extracted/dependencies/ ./
COPY --from=build /app/extracted/spring-boot-loader/ ./
COPY --from=build /app/extracted/snapshot-dependencies/ ./
COPY --from=build /app/extracted/application/ ./
USER spring:spring
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

Points obligatoires :
- `COPY pom.xml .` **avant** `COPY src ./src` : le `RUN mvn dependency:go-offline` doit rester en cache tant que `pom.xml` ne change pas, même si `src/` change à chaque commit.
- `-DskipTests` sur `mvn package` (les tests tournent déjà en CI, ticket #2 — pas le rôle de l'image).
- Extraction layertools faite **dans le stage `build`** (pas de stage intermédiaire supplémentaire nécessaire) : `target/backend-*.jar` matche l'artefact unique produit (`backend-0.1.0-SNAPSHOT.jar` selon `backend/pom.xml`, artifactId `backend`, version `0.1.0-SNAPSHOT` — utiliser le glob `target/backend-*.jar` plutôt que de coder la version en dur).
- Ordre des 4 `COPY --from=build` dans le stage `runtime` **exactement** `dependencies` → `spring-boot-loader` → `snapshot-dependencies` → `application` (ordre officiel Spring Boot layertools, du moins volatil au plus volatil, pour maximiser la réutilisation de couches Docker entre deux builds successifs).
- Utilisateur non-root créé explicitement et activé via `USER spring:spring` avant l'`ENTRYPOINT`.
- `ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]` (nom de classe conforme Spring Boot 3.2+/3.3, confirmé par le design — Spring Boot 3.3.13 est le parent du `pom.xml`).
- `EXPOSE 8080` (correspond à `server.port` par défaut dans `application.yml`).

### `frontend/Dockerfile` (contexte de build = `frontend/`)

```
# ---- Stage "build" ----
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# ---- Stage "runtime" ----
FROM nginx:alpine AS runtime
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```

Points obligatoires :
- `COPY package.json package-lock.json ./` **avant** `npm ci`, elle-même **avant** `COPY . .` : cache npm préservé tant que les manifests ne changent pas.
- `npm ci` (déterministe, identique à la CI du ticket #2), pas `npm install`.
- `npm run build` exécute `tsc -b && vite build` (script existant, sortie dans `frontend/dist/`).
- Le stage `runtime` ne contient ni Node ni `node_modules` : uniquement nginx + `dist/`.
- Pas de `CMD` à redéfinir (celui de l'image `nginx:alpine` officielle convient).

### `frontend/nginx.conf`

```nginx
server {
    listen 80;
    server_name _;
    root /usr/share/nginx/html;
    index index.html;

    location / {
        try_files $uri /index.html;
    }
}
```

### `backend/.dockerignore`

```
target/
.git
.gitignore
*.md
.idea/
*.iml
.vscode/
```

### `frontend/.dockerignore`

```
node_modules/
dist/
.git
.gitignore
.vscode/
```

### `docker-compose.yml` (racine)

```yaml
version: "3.9"

services:
  backend:
    build:
      context: ./backend
    image: samapiece-backend:dev
    ports:
      - "${BACKEND_HOST_PORT}:${SERVER_PORT}"
    environment:
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE}
      SERVER_PORT: ${SERVER_PORT}
      # NOTE: DB_* est transmis pour préparer un ticket futur (JPA/Flyway réel).
      # Le backend actuel ne les utilise jamais : spring.autoconfigure.exclude
      # (ticket #1) désactive tout accès JDBC. /actuator/health répond UP même
      # si postgres est down. Voir docs/bolts/3-docker-compose-dev/{design,spec}.md.
      DB_HOST: postgres
      DB_PORT: ${DB_PORT}
      DB_NAME: ${DB_NAME}
      DB_USER: ${DB_USER}
      DB_PASSWORD: ${DB_PASSWORD}
    healthcheck:
      test: ["CMD", "wget", "--no-verbose", "--tries=1", "--spider", "http://localhost:8080/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s
    # Pas de depends_on vers postgres/redis/etc. : voir note DB_* ci-dessus et
    # section "Écarts identifiés" du spec — couplage volontairement absent.

  frontend:
    build:
      context: ./frontend
    image: samapiece-frontend:dev
    ports:
      - "${FRONTEND_HOST_PORT}:80"

  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: ${DB_NAME}
      POSTGRES_USER: ${DB_USER}
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    ports:
      - "${POSTGRES_HOST_PORT}:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USER}"]
      interval: 10s
      timeout: 5s
      retries: 5

  redis:
    image: redis:7-alpine
    ports:
      - "${REDIS_HOST_PORT}:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

  meilisearch:
    image: getmeili/meilisearch:v1.10
    environment:
      MEILI_MASTER_KEY: ${MEILI_MASTER_KEY}
      MEILI_NO_ANALYTICS: "true"
    ports:
      - "${MEILISEARCH_HOST_PORT}:7700"
    volumes:
      - meili_data:/meili_data
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:7700/health"]
      interval: 10s
      timeout: 5s
      retries: 5

  minio:
    image: quay.io/minio/minio:RELEASE.2025-10-15T17-29-55Z
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}
    ports:
      - "${MINIO_API_HOST_PORT}:9000"
      - "${MINIO_CONSOLE_HOST_PORT}:9001"
    volumes:
      - minio_data:/data
    healthcheck:
      test: ["CMD", "mc", "ready", "local"]
      interval: 10s
      timeout: 5s
      retries: 5

  rabbitmq:
    image: rabbitmq:3-management-alpine
    environment:
      RABBITMQ_DEFAULT_USER: ${RABBITMQ_DEFAULT_USER}
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_DEFAULT_PASS}
    ports:
      - "${RABBITMQ_AMQP_HOST_PORT}:5672"
      - "${RABBITMQ_MANAGEMENT_HOST_PORT}:15672"
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  postgres_data:
  minio_data:
  meili_data:
```

Remarques normatives sur ce contrat (à respecter à la lettre par le codeur) :
- Le mot-clé `version: "3.9"` est conservé pour compatibilité maximale avec les installations qui exposent encore le binaire `docker-compose` (V1/Compose-Python) en plus de `docker compose` (V2/plugin) — les deux acceptent ce fichier sans erreur.
- Tous les identifiants/mots de passe passent par `${VAR}` — **zéro valeur en dur** dans `docker-compose.yml`, y compris les valeurs par défaut (elles vivent uniquement dans `.env.example`, jamais en fallback `${VAR:-...}` dans le compose, pour garder une seule source de vérité).
- Le port **côté conteneur** de chaque service d'infra est toujours écrit en dur (`5432`, `6379`, `7700`, `9000`/`9001`, `5672`/`15672`, `80`) car c'est le port réel où le processus écoute dans l'image officielle et il n'est jamais reconfiguré. Seul le port **côté host** (partie gauche du mapping) est piloté par une variable `_HOST_PORT` dédiée — voir "Écarts identifiés" pour la justification de cette séparation.
- `DB_PORT` transmis au backend est un port **logique interne au réseau Docker** (valeur par défaut `5432`, découplé de `POSTGRES_HOST_PORT`) : changer `POSTGRES_HOST_PORT` pour éviter une collision locale ne doit jamais casser une future connexion backend→postgres.

### `.env.example` (racine)

```dotenv
# ---------------------------------------------------------------------------
# SamaPiece — variables d'environnement pour docker-compose (développement).
# Copier ce fichier en .env (cp .env.example .env) avant `docker-compose up`.
# Ne jamais committer .env (déjà ignoré par .gitignore) ; .env.example, lui,
# doit toujours être commité et tenu à jour.
# ---------------------------------------------------------------------------

# --- Backend (Spring Boot) ---
SPRING_PROFILES_ACTIVE=dev
SERVER_PORT=8080
BACKEND_HOST_PORT=8080

# --- Frontend (nginx) ---
FRONTEND_HOST_PORT=8081

# --- PostgreSQL ---
# NOTE: non consommé par le backend dans ce ticket (voir README / spec) ;
# posé ici pour préparer le ticket futur qui réintroduira JPA/Flyway.
DB_NAME=samapiece_dev
DB_USER=samapiece
DB_PASSWORD=samapiece_dev_password
DB_PORT=5432
POSTGRES_HOST_PORT=5432

# --- Redis ---
REDIS_HOST_PORT=6379

# --- Meilisearch ---
MEILI_MASTER_KEY=samapiece_dev_master_key_change_me
MEILISEARCH_HOST_PORT=7700

# --- MinIO ---
MINIO_ROOT_USER=samapiece_minio
MINIO_ROOT_PASSWORD=samapiece_minio_password
MINIO_API_HOST_PORT=9000
MINIO_CONSOLE_HOST_PORT=9001

# --- RabbitMQ ---
RABBITMQ_DEFAULT_USER=samapiece
RABBITMQ_DEFAULT_PASS=samapiece_rabbitmq_password
RABBITMQ_AMQP_HOST_PORT=5672
RABBITMQ_MANAGEMENT_HOST_PORT=15672
```

Contrainte : chaque variable référencée par `${...}` dans `docker-compose.yml` **doit** avoir une ligne correspondante ici, avec une valeur de dev non vide (MinIO exige `MINIO_ROOT_PASSWORD` ≥ 8 caractères — les valeurs ci-dessus le respectent).

### Section README à ajouter (racine, après la section "Monorepo")

Contenu attendu (le codeur peut ajuster la formulation mais doit couvrir ces points) :
1. Titre `## Lancer la stack complète avec Docker Compose`.
2. Prérequis : Docker + Docker Compose installés, `cp .env.example .env`.
3. Commande : `docker-compose up -d --build`.
4. Tableau ou liste des URLs exposées une fois `docker-compose ps` montre tout "healthy" : backend (`http://localhost:8080/actuator/health`), frontend (`http://localhost:8081`), Meilisearch (`http://localhost:7700`), MinIO console (`http://localhost:9001`), RabbitMQ management (`http://localhost:15672`).
5. Un avertissement explicite, formulé sans ambiguïté : *"postgres, redis, meilisearch, minio et rabbitmq démarrent avec la stack mais ne sont pas encore réellement utilisés par le backend à ce stade (cf. ticket #1) — leur présence ne garantit pas une intégration fonctionnelle."*
6. Commande d'arrêt propre : `docker-compose down -v` (supprime aussi les volumes nommés).

## Plan de tests

| Critère d'acceptation | Vérification concrète |
|---|---|
| `Dockerfile` multi-stage backend (build Maven → jar layered) | `docker build -t samapiece-backend ./backend` réussit ; `docker run --rm -p 8080:8080 --env-file .env samapiece-backend` démarre puis `curl -f http://localhost:8080/actuator/health` répond `200 {"status":"UP"}` en < 30s ; `docker history samapiece-backend` confirme une image finale sans Maven/JDK complet (seulement JRE + jar). |
| `Dockerfile` frontend (build Vite → nginx statique) | `docker build -t samapiece-frontend ./frontend` réussit ; `docker run --rm -p 8081:80 samapiece-frontend` puis `curl -I http://localhost:8081/` répond `200` ; `docker run --rm samapiece-frontend ls /usr/share/nginx/html` liste `index.html`/assets, pas de `node_modules`. |
| `docker-compose.yml` démarre backend, frontend, postgres, redis, meilisearch, minio, rabbitmq | `docker-compose up -d --build` puis `docker-compose ps` liste les 7 services ; attendre (`watch docker-compose ps` ou boucle avec `sleep`) que `postgres`, `redis`, `meilisearch`, `minio`, `rabbitmq`, `backend` affichent `healthy` (frontend n'a pas de healthcheck, vérifier juste `Up`). |
| `docker-compose up` fonctionne sans configuration manuelle supplémentaire sur une machine propre | Sur un clone frais (ou `docker-compose down -v` + suppression des images locales pour simuler une machine propre) : seule étape manuelle attendue = `cp .env.example .env` ; puis `docker-compose up -d --build` sans autre édition. Vérifier `docker-compose logs backend` ne contient aucune erreur de connexion JDBC/refus de démarrage. Puis exécuter les `curl` suivants sur chaque port exposé et vérifier une réponse HTTP valide (ou `PONG`/`ping` réussi) : `curl -f http://localhost:8080/actuator/health`, `curl -I http://localhost:8081/`, `curl -f http://localhost:7700/health`, `curl -f http://localhost:9000/minio/health/live`, `curl -I http://localhost:9001/`, `curl -I http://localhost:15672/`, `docker-compose exec redis redis-cli ping`, `docker-compose exec postgres pg_isready -U samapiece`. Puis `docker-compose down -v` : code de sortie 0, `docker volume ls` ne montre plus `postgres_data`/`minio_data`/`meili_data` du projet. |
| `.env.example` documente toutes les variables nécessaires | Revue croisée : `grep -oE '\$\{[A-Z_]+' docker-compose.yml` (sans valeurs par défaut inline) doit être un sous-ensemble strict des clés définies dans `.env.example` ; aucune variable manquante, aucune valeur vide. |
| README mis à jour | Revue manuelle de la section ajoutée : présence des commandes `cp .env.example .env`, `docker-compose up -d --build`, `docker-compose down -v`, et de l'avertissement sur l'absence de dépendance réelle backend→infra. |

## Écarts identifiés

1. **Absence volontaire de `depends_on` backend→postgres (repris du design, non retranché)** : le backend ne s'est jamais connecté à Postgres dans ce ticket (`spring.autoconfigure.exclude` posé au ticket #1). `/actuator/health` répond `UP` que `postgres` soit démarré, en échec, ou absent. Le compose fournit quand même `DB_HOST`/`DB_PORT`/etc. au conteneur backend par cohérence avec `application-dev.yml`, mais ces variables sont **actuellement inertes**. Documenté explicitement dans `docker-compose.yml` (commentaire inline) et dans le README (point 5 de la section à ajouter) pour qu'un futur développeur ne croie pas la persistance déjà fonctionnelle.

2. **MinIO retiré de Docker Hub — fait découvert lors de la vérification réseau demandée par ce ticket.** Le dépôt `minio/minio` sur Docker Hub a été archivé (dernière image publiée : `RELEASE.2025-10-15T17-29-55Z`, en octobre 2025 ; MinIO a cessé de publier gratuitement sur Docker Hub après cette date). Le registre de repli officiel, utilisé par MinIO lui-même dans son propre `docker-compose.yaml` de référence, est `quay.io/minio/minio`. **J'ai donc fixé l'image à `quay.io/minio/minio:RELEASE.2025-10-15T17-29-55Z`** (dernière release connue, incluant un correctif de sécurité) au lieu de `minio/minio:...` évoqué comme placeholder par le design — sans ce changement, `docker-compose up --build` sur une machine sans l'image déjà en cache local échouerait au pull, ce qui casserait directement le critère d'acceptation "fonctionne sans configuration manuelle supplémentaire sur une machine propre".

3. **Healthcheck MinIO via `curl` : impossible avec l'image retenue.** Les images MinIO récentes (dont celle ci-dessus) ne contiennent plus ni `curl` ni `wget` (retirés pour réduire la surface d'attaque). La commande `curl -f http://localhost:9000/minio/health/live` évoquée par le design comme healthcheck resterait indéfiniment en échec (conteneur "unhealthy" en permanence, sans jamais bloquer `docker-compose up` mais polluant `docker-compose ps`). Remplacée dans le contrat ci-dessus par `mc ready local` — le client `mc` est embarqué nativement dans l'image officielle, c'est la méthode recommandée par le projet MinIO pour ce cas précis. (Note : cela ne concerne que le `HEALTHCHECK` exécuté *dans* le conteneur — un `curl` depuis le host vers le port exposé, utilisé dans le plan de tests ci-dessus, fonctionne normalement.)

4. **Healthcheck backend (optionnel, évoqué par le design) via `curl` : également impossible.** L'image runtime retenue (`eclipse-temurin:21.0.12_8-jre-alpine-3.24`) ne fournit pas `curl` (seul `wget` de BusyBox est présent, comme sur toute image Alpine minimale). Le contrat ci-dessus utilise donc `wget --no-verbose --tries=1 --spider ...` pour ce healthcheck, jamais `curl`.

5. **Noms de variables pour les ports hôte configurables — point sous-spécifié par le design.** Le design indique que "tous les ports exposés côté host sont configurables via `.env`" sans donner les noms de variables ni trancher leur relation avec les ports internes (ex. `DB_PORT`). J'ai introduit des variables `_HOST_PORT` dédiées et strictement séparées du port interne réseau Docker (ex. `POSTGRES_HOST_PORT` ≠ `DB_PORT`) pour qu'un développeur changeant un port hôte (collision locale) ne casse jamais silencieusement la connectivité interne entre conteneurs — pertinent dès le ticket futur qui réactivera JPA.

6. **Volume Meilisearch — tranché ici, laissé ouvert par le design.** Le design qualifiait un volume `meili_data` d'"optionnel, à la discrétion du codeur". Pour ne rien laisser au hasard, il est inclus dans le contrat technique (coût nul, évite une perte de données d'index lors de tests manuels de recherche en dev).

7. **Version Meilisearch `v1.10` très en retard sur la dernière stable réelle** (`v1.53.x` au moment de cette spec) : décision du design (image de base), non rediscutée ici. Vérifié que `getmeili/meilisearch:v1.10` est toujours disponible sur Docker Hub au moment de la rédaction — aucun blocage immédiat, mais dette technique à surveiller si ce tag venait à être dépublié.
