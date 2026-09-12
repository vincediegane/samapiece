# Design — Ticket #3 : Dockeriser backend/frontend + docker-compose de dev

État constaté du repo (vérifié via Read/Glob/Grep sur les fichiers réels, branche `bolt/issue-3-docker-compose-dev` empilée sur #2 empilée sur #1) :

- `backend/pom.xml` : parent `spring-boot-starter-parent:3.3.13` (récupéré depuis Maven Central via `relativePath` vide, pas depuis le `pom.xml` racine), Java 21, packaging `jar`, plugin `spring-boot-maven-plugin`. **Le module `backend` est buildable de façon autonome** (`mvn -f backend/pom.xml package` ou `cd backend && mvn package`) sans dépendre du `pom.xml` racine agrégateur. **Aucun wrapper Maven (`mvnw`) n'est présent** dans le repo — le build Docker doit fournir Maven lui-même (image `maven:...`), pas s'appuyer sur `./mvnw`.
- `backend/src/main/resources/application.yml` : confirme noir sur blanc l'exclusion actée au ticket #1 — `spring.autoconfigure.exclude: DataSourceAutoConfiguration,HibernateJpaAutoConfiguration`, avec un commentaire explicite dans le fichier indiquant que sans cette exclusion `mvn spring-boot:run` échoue faute de datasource, et qu'`/actuator/health` répond `UP` même si une base réelle configurée par ailleurs serait inaccessible. `server.port` par défaut `8080` (surchargeable via `SERVER_PORT`). `management.endpoints.web.exposure.include: health` — l'endpoint `/actuator/health` existe et est utilisable pour un healthcheck Docker, avec la réserve ci-dessus.
- `backend/src/main/resources/application-dev.yml` : bloc `spring.datasource` **présent mais explicitement commenté comme inactif** ("Bloc inactif tant que l'exclusion datasource ci-dessus est en place"), avec `url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:samapiece_dev}`, `username: ${DB_USER}`, `password: ${DB_PASSWORD}`. `application-staging.yml`/`application-prod.yml` attendent les mêmes variables sans valeur par défaut. → Les noms de variables à respecter dans le compose/`.env.example` sont donc exactement `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`.
- `.gitignore` racine contient déjà `target/`, `node_modules/`, `dist/`, `build/`, `.vite/`, `.env`, `.env.local`, `.env.*.local` — pas besoin de les rajouter, mais `.env.example` n'est pas ignoré (c'est voulu, il doit être commité).
- `frontend/package.json` : scripts `dev`, `build` (`tsc -b && vite build`), `lint`, `preview`. Vite build par défaut sort dans `frontend/dist/`. Aucune dépendance `react-router` à ce stade (scaffold simple, une seule page `src/features/home`).
- `.github/workflows/{backend,frontend}.yml` (posés au ticket #2) : `mvn -pl backend -am verify` et `npm ci && npm run lint && npm run build`. **Aucune étape de build/publication d'image Docker** — confirmé, cohérent avec le "hors périmètre" ci-dessous.
- `PROJET-SAMAPIECE.md` §11.4 : confirme les choix technos "retenus" — PostgreSQL, Meilisearch, Redis (cache & rate limiting), Spring AMQP + **RabbitMQ**, MinIO (S3-compatible), conteneurisation Docker + Docker Compose (V1/pilote). Le corps du ticket demandant `rabbitmq` est donc cohérent avec le document de référence, pas un écart.
- `docs/bolts/{1,2}-*/design.md` existent déjà (style de référence suivi ici) ; `docs/bolts/3-docker-compose-dev/` est actuellement vide.

## Approche

Deux `Dockerfile` multi-stage indépendants, un `docker-compose.yml` à la racine qui les assemble avec les 5 services d'infra, et un `.env.example` unique documentant toutes les variables.

**Backend (`backend/Dockerfile`, contexte de build = `backend/`)** :
1. *Stage `build`* : image `maven:3.9-eclipse-temurin-21` (Maven fourni par l'image car pas de `mvnw` dans le repo). `COPY pom.xml .` puis `mvn dependency:go-offline` (cache Docker des dépendances), puis `COPY src ./src` et `mvn -DskipTests package` — produit `target/backend-0.1.0-SNAPSHOT.jar`. `-DskipTests` car les tests tournent déjà en CI (ticket #2) ; ce n'est pas le rôle de l'image Docker de re-exécuter la suite.
2. *Extraction des layers* : toujours dans le stage `build` (ou un stage intermédiaire dédié), `java -Djarmode=layertools -jar target/*.jar extract --destination extracted` — le plugin `spring-boot-maven-plugin` (actif dans `backend/pom.xml`) produit par défaut un jar "layered" depuis Spring Boot 2.4+, aucune configuration supplémentaire requise côté `pom.xml`.
3. *Stage `runtime`* : image `eclipse-temurin:21-jre` (JRE seul, pas le JDK complet, pour réduire la taille). `COPY --from=build` des layers dans l'ordre recommandé par Spring Boot (`dependencies`, `spring-boot-loader`, `snapshot-dependencies`, `application`) pour maximiser le cache Docker (les dépendances changent rarement, la couche `application` change à chaque build). `ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]` (nom de la classe de lancement conforme à Spring Boot 3.2+/3.3). Utilisateur non-root créé explicitement (`RUN useradd` ou équivalent) plutôt que de tourner en `root` dans le conteneur. `EXPOSE 8080`.

**Comment le backend "démarre sans erreur" alors que postgres n'est pas encore réellement utilisé** : rien de spécial à faire côté Dockerfile/compose — c'est déjà garanti par le code applicatif existant (`spring.autoconfigure.exclude` posé au ticket #1). Le conteneur backend démarre, écoute sur `8080`, répond `UP` sur `/actuator/health`, **sans jamais ouvrir de connexion JDBC**, que le service `postgres` du compose soit démarré, en échec, ou absent. Le compose fournit quand même `DB_HOST=postgres`, `DB_PORT=5432`, etc. au conteneur backend (cohérence avec `application-dev.yml` et préparation pour le ticket futur qui réintroduira JPA/Flyway), mais ces variables sont **actuellement inertes** côté backend. Ce point doit être documenté en clair dans le `README` racine ou un commentaire du compose (pas seulement ici) pour qu'un futur développeur ne pense pas, à tort, que la persistance fonctionne déjà.

**Frontend (`frontend/Dockerfile`, contexte de build = `frontend/`)** :
1. *Stage `build`* : image `node:20-alpine` (aligné avec Node 20 LTS déjà choisi en CI au ticket #2). `COPY package.json package-lock.json ./` puis `npm ci` (déterministe, comme en CI), puis `COPY . .` et `npm run build` — produit `frontend/dist/` (build Vite statique, `tsc -b && vite build`).
2. *Stage `runtime`* : image `nginx:alpine`. `COPY --from=build /app/dist /usr/share/nginx/html`. `COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf` — config nginx minimale avec `try_files $uri /index.html;` pour un éventuel routage SPA côté client dans le futur (aucun router n'est installé aujourd'hui, mais ce garde-fou est gratuit et évite une régression silencieuse le jour où React Router est ajouté). `EXPOSE 80`.

**`docker-compose.yml` (racine)** : sept services — `backend`, `frontend`, `postgres`, `redis`, `meilisearch`, `minio`, `rabbitmq`. Chaque service d'infra a un `healthcheck` natif à son image. Toutes les valeurs sensibles (identifiants, mots de passe, clés) viennent de variables d'environnement lues depuis `.env` (jamais codées en dur dans `docker-compose.yml`) — Docker Compose charge `.env` automatiquement s'il est à la racine, donc `docker-compose up` fonctionne sans étape manuelle dès que le développeur a copié `.env.example` vers `.env`.

## Fichiers / modules impactés

Tous nouveaux (aucun fichier source applicatif existant n'est modifié) :

- `backend/Dockerfile`
- `backend/.dockerignore` (exclut `target/`, `.git`, `*.md`, IDE files — évite d'invalider le cache Docker et d'alourdir le contexte de build)
- `frontend/Dockerfile`
- `frontend/.dockerignore` (exclut `node_modules/`, `dist/`, `.git`)
- `frontend/nginx.conf`
- `docker-compose.yml` (racine)
- `.env.example` (racine)

Le `.gitignore` racine n'a pas besoin d'être modifié : `.env` y est déjà ignoré, `target/`/`node_modules/`/`dist/` aussi (pas de doublon à ajouter). `.env.example` doit rester commité (il n'est pas concerné par les règles `.env*` du `.gitignore`, qui ciblent `.env`, `.env.local`, `.env.*.local` — `.env.example` n'a pas ce pattern donc il n'est pas ignoré ; à vérifier explicitement par le codeur en committant, mais aucune modification du `.gitignore` n'est nécessaire).

## Décisions clés

- **Images de base** : `maven:3.9-eclipse-temurin-21` (stage build backend, car pas de `mvnw`), `eclipse-temurin:21-jre` (runtime backend), `node:20-alpine` (stage build frontend, cohérent avec Node 20 LTS de la CI), `nginx:alpine` (runtime frontend). Toujours des tags versionnés précis (pas de simple `:latest`) pour la reproductibilité — le spec-writer/codeur doit figer une version patch exacte disponible sur Docker Hub au moment de l'implémentation (ex. `eclipse-temurin:21-jre` en variante Alpine si la taille est jugée prioritaire, à trancher au moment du codage selon la taille réelle obtenue).
- **Images d'infra et versions** :
  - `postgres:16-alpine` — service démarré pour préparer le ticket Flyway/JPA futur, **non consommé fonctionnellement par le backend dans ce ticket** (cf. section Approche).
  - `redis:7-alpine`.
  - `getmeili/meilisearch:v1.10` (image officielle Meilisearch), avec `MEILI_MASTER_KEY` obligatoire en dev pour éviter le mode "no master key" (avertissement bruyant dans les logs sinon).
  - `minio/minio:latest` remplacé par un tag versionné explicite type `minio/minio:RELEASE.2024-XX-XXT00-00-00Z` (à figer précisément par le codeur au moment de l'implémentation avec la dernière release stable connue), commande `server /data --console-address ":9001"`.
  - `rabbitmq:3-management-alpine` (variante `-management` pour avoir l'UI de gestion accessible en dev, utile pour inspecter les files sans outillage supplémentaire).
- **Ports exposés côté host** (tous configurables via `.env`, valeurs par défaut ci-dessous) :
  - backend : `8080:8080`
  - frontend : `8081:80` (évite la collision avec le backend sur `8080`)
  - postgres : `5432:5432`
  - redis : `6379:6379`
  - meilisearch : `7700:7700`
  - minio : `9000:9000` (API S3) et `9001:9001` (console web)
  - rabbitmq : `5672:5672` (AMQP) et `15672:15672` (management UI)
- **Healthchecks des services d'infra** (pas de valeur pour postgres/redis/meilisearch/minio/rabbitmq sans healthcheck, car `depends_on` avec `condition: service_healthy` en dépend) :
  - postgres : `pg_isready -U ${DB_USER}`
  - redis : `redis-cli ping`
  - meilisearch : `curl -f http://localhost:7700/health`
  - minio : `curl -f http://localhost:9000/minio/health/live`
  - rabbitmq : `rabbitmq-diagnostics -q ping`
  - Un healthcheck optionnel côté `backend` (`curl -f http://localhost:8080/actuator/health`) est possible et cohérent avec `management.endpoints.web.exposure.include: health` déjà actif — **mais avec la réserve déjà documentée dans `application.yml`** : ce endpoint répond `UP` indépendamment de l'état réel de postgres tant que l'exclusion d'autoconfiguration est active. Un healthcheck compose dessus est donc légitime pour vérifier que le process JVM a démarré, pas pour vérifier une chaîne de dépendances applicatives.
- **`depends_on` du backend** : **aucun `depends_on` fonctionnel du backend vers `postgres`/`redis`/etc. dans ce ticket.** Ajouter un `depends_on: postgres: condition: service_healthy` donnerait l'illusion trompeuse d'un couplage réel alors que le code ne s'y connecte pas — cf. point important #1 du ticket. Le backend démarre indépendamment ; les services d'infra démarrent en parallèle. Ce choix sera revu au ticket qui réintroduira JPA/Flyway (où un `depends_on: service_healthy` sur `postgres` deviendra pertinent).
- **Credentials via `.env` uniquement** : aucun mot de passe, clé ou identifiant en dur dans `docker-compose.yml` — toutes les valeurs (`DB_USER`, `DB_PASSWORD`, `MEILI_MASTER_KEY`, `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`, `RABBITMQ_DEFAULT_USER`, `RABBITMQ_DEFAULT_PASS`) référencées via `${VAR}` et documentées avec des valeurs de dev non sensibles dans `.env.example` (le développeur copie `.env.example` → `.env`, jamais l'inverse).
- **Alignement des variables Postgres** : une seule source de vérité dans `.env` (`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, exactement les noms attendus par `application-dev.yml`/`-staging`/`-prod`) ; le service `postgres` du compose consomme les variables d'initialisation natives de l'image officielle (`POSTGRES_DB: ${DB_NAME}`, `POSTGRES_USER: ${DB_USER}`, `POSTGRES_PASSWORD: ${DB_PASSWORD}`) plutôt que de dupliquer des noms différents — évite toute divergence entre ce que Postgres crée et ce que le backend croira lire plus tard.
- **`SPRING_PROFILES_ACTIVE=dev`** transmis au conteneur backend (aligné avec la valeur par défaut déjà présente dans `application.yml` — redondant mais explicite dans `.env.example`, utile si un développeur veut basculer un conteneur local sur `staging` sans reconstruire l'image).

## Risques / points d'attention

- **Le backend "réussit" sans dépendre réellement de postgres peut masquer un futur problème de configuration** (point explicitement soulevé par le ticket) : si le compose donne l'illusion d'une stack fonctionnelle de bout en bout, un développeur pourrait découvrir tardivement (au ticket JPA/Flyway) que les variables `DB_*` ou le réseau Docker ne sont en fait jamais exercés aujourd'hui. Mitigation : documentation explicite dans `README.md`/commentaires du `docker-compose.yml` (pas seulement dans ce design.md), et absence volontaire de `depends_on` trompeur (cf. décision ci-dessus).
- **Taille des images** : le stage `build` backend (Maven + JDK) peut peser plusieurs centaines de Mo de couches intermédiaires ; correctement multi-stage, seule l'image `runtime` finale (JRE + jar) est conservée et poussée/utilisée en local — mais le premier build à froid reste lourd en téléchargement (image Maven + toutes les dépendances Spring Boot). Le stage `build` frontend (`node:20-alpine` + `node_modules`) est également ignoré dans l'image finale (`nginx:alpine` + `dist/` uniquement, quelques Mo).
- **Temps de build** : premier `docker-compose up --build` lent (téléchargement des 7 images + résolution Maven/npm à froid, sans cache) ; les runs suivants sont rapides grâce au cache de couches Docker (à condition que `pom.xml`/`package.json` ne changent pas entre deux builds, sinon invalidation du cache de dépendances). Ce n'est pas bloquant pour le critère d'acceptation ("fonctionne sans configuration manuelle supplémentaire"), mais à ne pas confondre avec de la lenteur ("Docker cassé").
- **Volumes de persistance** : `postgres` et `minio` doivent utiliser des volumes Docker nommés (ex. `postgres_data:/var/lib/postgresql/data`, `minio_data:/data`) pour survivre à un `docker-compose down` (sans `-v`). Sans ça, toute donnée de test créée dans MinIO serait perdue à chaque redémarrage — gênant même si postgres n'est pas encore utilisé par le backend (mais MinIO pourrait déjà servir à des tests manuels d'upload). Redis/Meilisearch/RabbitMQ n'ont pas de volume obligatoire pour ce ticket (leur perte de données au redémarrage est acceptable en dev à ce stade, rien ne les utilise encore côté backend), mais un volume optionnel pour Meilisearch peut être ajouté sans coût si le codeur le juge utile pour de futurs tests de recherche.
- **Pas de reverse-proxy / CORS entre frontend et backend** : le frontend est servi statiquement par nginx sur son propre port, le backend sur le sien — aucun appel réseau réel entre les deux n'existe encore dans le code applicatif (scaffold uniquement), donc aucune configuration CORS/proxy n'est nécessaire pour ce ticket. À revoir dès qu'un premier appel API réel sera ajouté côté frontend.
- **Utilisateur non-root dans l'image backend runtime** : bonne pratique de sécurité de base à appliquer (`USER` non-root dans le Dockerfile), sans complexifier le scope du ticket.

## Hors périmètre

- CI de build/publication des images Docker vers un registre — le ticket #2 ne construit aucune image (confirmé par lecture de `.github/workflows/*.yml`) ; un futur ticket CI/CD dédié pourra ajouter un job `docker build`/`push`.
- Kubernetes (mentionné en cible "national" dans `PROJET-SAMAPIECE.md` §11.4, hors échelle de ce ticket qui couvre uniquement Docker Compose "V1/pilote").
- Configuration TLS/HTTPS (le compose de dev sert en HTTP simple sur des ports locaux).
- Déploiement staging/production — ce ticket ne couvre que l'environnement de développement local (`docker-compose up` sur poste développeur), pas les environnements décrits en §11.5/§11.9 du document de référence.
- Réintroduction de JPA/Flyway/connexion réelle à PostgreSQL côté backend (couvert par un ticket futur, référencé par le TODO déjà présent dans `application.yml`).
- Ajout d'un reverse-proxy (Nginx/Traefik) unifiant backend+frontend derrière une seule origine — non demandé par les critères d'acceptation, et prématuré tant qu'aucun appel API réel n'existe côté frontend.
