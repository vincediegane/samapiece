# Spec — Ticket #77 : Déploiement de la stack complète sur Render (démo/suivi client)

## Résumé

Livrer le code et la configuration versionnés (`render.yaml`, correctifs `application-staging.yml`,
paramétrisation du proxy nginx, documentation) nécessaires à un déploiement Render de la stack
complète ; le déploiement effectif et la vérification des critères d'acceptation qui supposent une URL
en ligne restent une action manuelle humaine documentée, jamais exécutée par ce pipeline.

## Périmètre : ce qui est livré vs ce qui reste manuel

- **Livré par le codeur (vérifiable sans compte Render)** : `render.yaml` valide syntaxiquement,
  `application-staging.yml` corrigé et démarrable en local, `nginx.conf.template`/`Dockerfile` frontend
  paramétrés, `docker-compose.yml`/`.env.example` toujours fonctionnels en local, `README.md` à jour.
- **Reste une étape humaine post-bolt (non vérifiable par ce pipeline)** : création du compte Render,
  "New Blueprint" à partir de `render.yaml`, saisie manuelle des secrets dans le dashboard (`envVarGroup`
  `sync: false`), choix des plans payants, premier déploiement, insertion manuelle Région/Poste avant
  bootstrap, vérification que `GET /actuator/health` répond `200` en ligne, vérification bout-en-bout du
  frontend déployé, vérification TLS/port réel. Chaque tâche ci-dessous précise si sa "définition de
  fini" est automatisable ou seulement documentaire.

## Tâches

### Backend

- [ ] `backend/src/main/resources/application-staging.yml` : ajouter les blocs manquants pour que le
  profil `staging` démarre (voir Contrat technique). Vérification : `SPRING_PROFILES_ACTIVE=staging`
  + toutes les variables d'env requises positionnées (voir liste ci-dessous) → `mvn -pl backend
  spring-boot:run` (ou service `backend` docker-compose avec `SPRING_PROFILES_ACTIVE=staging` surchargé)
  démarre sans `ConfigurationPropertiesBindException`/erreur de validation et `GET
  /actuator/health` répond `200` en local.
- [ ] Ne pas toucher à `application-prod.yml` ni à d'autres fichiers backend (contrôleurs, entités,
  migrations Flyway) — hors périmètre explicite du design.

### Frontend / proxy nginx

- [ ] `frontend/nginx.conf.template` : remplacer `proxy_pass http://backend:${SERVER_PORT}/api/;` par
  `proxy_pass http://${BACKEND_HOST}:${SERVER_PORT}/api/;`.
- [ ] `frontend/Dockerfile` : étendre `ENV NGINX_ENVSUBST_FILTER='^SERVER_PORT$'` pour couvrir aussi
  `BACKEND_HOST` (ex. `ENV NGINX_ENVSUBST_FILTER='^(SERVER_PORT|BACKEND_HOST)$'` — vérifier la syntaxe
  exacte acceptée par l'image `nginx:alpine`/`docker-entrypoint.sh` : c'est une regex passée à `envsubst`
  côté image officielle nginx, un seul flag `NGINX_ENVSUBST_FILTER` avec alternance `(A|B)` est le
  format attendu). Sans ce correctif, `${BACKEND_HOST}` resterait littéral dans la conf nginx générée
  (même régression que celle documentée dans `docs/bolts/57-frontend-sans-proxy-api-backend/design.md`).
- [ ] `docker-compose.yml` : ajouter `BACKEND_HOST: backend` dans `environment:` du service `frontend`
  (à côté de `SERVER_PORT: ${SERVER_PORT}`) pour préserver le comportement local existant après la
  paramétrisation du template.
- [ ] `.env.example` : ajouter `BACKEND_HOST=backend` dans la section frontend, avec un commentaire
  précisant que cette valeur est spécifique à docker-compose et que Render fournit sa propre valeur via
  `render.yaml`.

### Render Blueprint

- [ ] Créer `render.yaml` (racine du repo) décrivant les 7 services (voir Contrat technique pour le
  détail par service). Vérification faisable maintenant : le fichier est un YAML valide (`docker
  compose` n'est pas utilisable ici — utiliser un linter YAML générique, ex. `python -c "import yaml,
  sys; yaml.safe_load(open('render.yaml'))"` ou équivalent) et respecte, au meilleur de la connaissance
  du schéma Render Blueprint documenté (`services`, `databases`, `envVarGroups`, `type: web` +
  `runtime: docker`, `type: pserv`, `type: redis`, `disk:`, `envVars` avec `fromService`/`fromDatabase`/
  `sync: false`), la structure décrite ci-dessous. Le schéma exact n'étant pas vérifiable sans compte
  Render réel, chaque point incertain est marqué **(hypothèse à confirmer au déploiement manuel)**
  ci-dessous plutôt que présenté comme garanti.
- [ ] Aucune valeur de secret en clair dans `render.yaml` : tous les champs listés en "Secrets"
  (Contrat technique) utilisent `sync: false` dans un `envVarGroup`, sans valeur, y compris de démo.

### Documentation

- [ ] `README.md` (racine) : nouvelle section "Déploiement sur Render (démo)" couvrant : lien vers
  `render.yaml`, procédure de déploiement initial (Blueprint → saisie manuelle des secrets dans le
  dashboard → déployer), procédure de redéploiement (push sur `main` + "Manual Deploy", pas de CI/CD
  automatique), confirmation que Flyway tourne automatiquement au démarrage du service `backend` (pas
  de commande séparée), renvoi vers la procédure de bootstrap admin déjà documentée dans
  `backend/README.md` (section "Bootstrap du premier compte administrateur") en précisant qu'elle doit
  être exécutée via le Shell Render (`psql` contre `samapiece-db`) avant le premier démarrage avec
  `BOOTSTRAP_ADMIN_ENABLED=true`, rappel explicite "environnement démo, ne jamais saisir de données
  personnelles citoyennes réelles", et la liste des risques/hypothèses non vérifiables sans compte
  Render (TLS Redis managé, port détecté sans variable `PORT`, coût des 3 Private Services, plans
  payants Postgres/Redis).
- [ ] Ne pas modifier `.gitignore` : `.env` y est déjà exclu ; aucune nouvelle exclusion nécessaire
  (aucun fichier de secret Render n'est écrit sur disque par ce ticket).

## Contrat technique

### `application-staging.yml` — blocs à ajouter

En plus du contenu existant (`spring.datasource.*`), ajouter :

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT:6379}

samapiece:
  meilisearch:
    host: ${MEILISEARCH_HOST}
    api-key: ${MEILISEARCH_API_KEY}
    index-pieces: ${MEILISEARCH_INDEX_PIECES}
```

- Aucune valeur par défaut pour `REDIS_HOST`/`MEILISEARCH_HOST`/`MEILISEARCH_API_KEY`/
  `MEILISEARCH_INDEX_PIECES` (cohérent avec la convention déjà en place pour `DB_*` en `staging` : pas
  de défaut sensible ni de défaut d'infra hors dev).
- `spring.rabbitmq.*`, `samapiece.jwt.secret`, `samapiece.photo.cle-chiffrement`,
  `samapiece.alerte.cle-chiffrement`, `samapiece.sms.*` sont déjà résolus au niveau `application.yml`
  (base, sans profil) via des variables d'environnement obligatoires sans défaut — ne rien ajouter pour
  ceux-ci dans `application-staging.yml`, seulement s'assurer que `render.yaml` fournit ces variables
  d'environnement au service `backend`.
- Ne pas ajouter `spring.data.redis.password`/`spring.data.redis.ssl.enabled` : hors périmètre explicite
  du design ("trancher la question du TLS sur le Redis managé Render"). Voir **Écarts identifiés**.

### `frontend/nginx.conf.template`

```
proxy_pass http://${BACKEND_HOST}:${SERVER_PORT}/api/;
```

`BACKEND_HOST` et `SERVER_PORT` doivent tous deux être résolus par `envsubst` au démarrage du conteneur
nginx (via `NGINX_ENVSUBST_FILTER` dans `frontend/Dockerfile`), sinon le proxy tente de résoudre le nom
DNS littéral `${BACKEND_HOST}` et échoue (`502 Bad Gateway`).

### `render.yaml` — structure attendue (Blueprint)

```yaml
envVarGroups:
  - name: samapiece-secrets
    envVars:
      - key: DB_PASSWORD          # géré par fromDatabase côté backend, présent ici seulement si besoin ailleurs
        sync: false
      - key: JWT_SECRET
        sync: false
      - key: PHOTO_CLE_CHIFFREMENT
        sync: false
      - key: ALERTE_CLE_CHIFFREMENT
        sync: false
      - key: SMS_API_KEY
        sync: false
      - key: MEILI_MASTER_KEY
        sync: false
      - key: MINIO_ROOT_USER
        sync: false
      - key: MINIO_ROOT_PASSWORD
        sync: false
      - key: RABBITMQ_DEFAULT_USER
        sync: false
      - key: RABBITMQ_DEFAULT_PASS
        sync: false
      - key: BOOTSTRAP_ADMIN_MATRICULE
        sync: false
      - key: BOOTSTRAP_ADMIN_NOM
        sync: false
      - key: BOOTSTRAP_ADMIN_POSTE_ID
        sync: false

databases:
  - name: samapiece-db
    plan: <plan payant à choisir manuellement>   # (hypothèse : clé `plan`, valeurs exactes à confirmer dans le dashboard)
    databaseName: samapiece
    user: samapiece

services:
  # --- Redis managé (Render "Key Value") ---
  - type: redis                      # (hypothèse à confirmer : la clé Blueprint pour le service managé
                                      #  Redis/Key Value pourrait différer de `redis` selon la version du
                                      #  schéma Render au moment du déploiement réel)
    name: samapiece-redis
    plan: <plan payant à choisir manuellement>
    ipAllowList: []                  # pas d'accès externe, seulement inter-services internes

  # --- Backend ---
  - type: web
    name: samapiece-backend
    runtime: docker
    dockerfilePath: ./backend/Dockerfile
    dockerContext: ./backend
    plan: <plan à choisir manuellement>
    healthCheckPath: /actuator/health
    envVars:
      - key: SPRING_PROFILES_ACTIVE
        value: staging
      - key: SERVER_PORT
        value: "8080"
      - key: DB_HOST
        fromDatabase: { name: samapiece-db, property: host }
      - key: DB_PORT
        fromDatabase: { name: samapiece-db, property: port }
      - key: DB_NAME
        fromDatabase: { name: samapiece-db, property: database }
      - key: DB_USER
        fromDatabase: { name: samapiece-db, property: user }
      - key: DB_PASSWORD
        fromDatabase: { name: samapiece-db, property: password }
      - key: REDIS_HOST
        fromService: { type: redis, name: samapiece-redis, property: host }
      - key: REDIS_PORT
        fromService: { type: redis, name: samapiece-redis, property: port }
      - key: MEILISEARCH_HOST
        value: "http://samapiece-meilisearch:7700"   # (hypothèse : nom du pserv utilisable tel quel comme
                                                       #  hostname interne — à confirmer, sinon utiliser
                                                       #  `fromService: {type: pserv, name: ..., property: host}`)
      - fromGroup: samapiece-secrets   # apporte MEILI_MASTER_KEY etc. — voir note ci-dessous
      - key: MEILISEARCH_API_KEY
        value: "${MEILI_MASTER_KEY}"   # (à vérifier : Render ne supporte pas l'interpolation de variable
                                        #  dans une valeur `value:` — si confirmé impossible, dupliquer la
                                        #  clé secrète telle quelle dans le groupe sous le nom
                                        #  MEILISEARCH_API_KEY plutôt que de la déduire de MEILI_MASTER_KEY)
      - key: MEILISEARCH_INDEX_PIECES
        value: pieces
      - key: MINIO_ENDPOINT
        value: "http://samapiece-minio:9000"
      - key: MINIO_BUCKET_PHOTOS
        value: samapiece-photos-demo
      - key: RABBITMQ_HOST
        value: samapiece-rabbitmq
      - key: RABBITMQ_PORT
        value: "5672"
      - key: JWT_SECRET
        sync: false
      - key: PHOTO_CLE_CHIFFREMENT
        sync: false
      - key: ALERTE_CLE_CHIFFREMENT
        sync: false
      - key: SMS_API_ENDPOINT
        value: "http://localhost:1"   # pas de passerelle SMS réelle pour la démo, cf. .env.example dev
      - key: SMS_API_KEY
        sync: false
      - key: MINIO_ACCESS_KEY
        sync: false
      - key: MINIO_SECRET_KEY
        sync: false
      - key: RABBITMQ_USER
        sync: false
      - key: RABBITMQ_PASSWORD
        sync: false
      - key: BOOTSTRAP_ADMIN_ENABLED
        value: "true"
      - key: BOOTSTRAP_ADMIN_MATRICULE
        sync: false
      - key: BOOTSTRAP_ADMIN_NOM
        sync: false
      - key: BOOTSTRAP_ADMIN_POSTE_ID
        sync: false

  # --- Frontend ---
  - type: web
    name: samapiece-frontend
    runtime: docker
    dockerfilePath: ./frontend/Dockerfile
    dockerContext: ./frontend
    plan: <plan à choisir manuellement>
    envVars:
      - key: SERVER_PORT
        value: "8080"     # doit correspondre au port réellement écouté par samapiece-backend
      - key: BACKEND_HOST
        fromService: { type: web, name: samapiece-backend, property: host }

  # --- Meilisearch (Private Service) ---
  - type: pserv
    name: samapiece-meilisearch
    runtime: docker
    dockerfilePath: <Dockerfile inline ou image officielle — hypothèse : `image: getmeili/meilisearch:v1.10`
                     directement supporté en `pserv` sans Dockerfile ; à confirmer, le schéma Blueprint
                     documente aussi une clé `image: { url: ... }` pour les services basés sur une image
                     Docker Hub plutôt qu'un build>
    plan: <plan payant à choisir manuellement>
    envVars:
      - key: MEILI_MASTER_KEY
        sync: false
      - key: MEILI_NO_ANALYTICS
        value: "true"
    disk:
      name: meili-data
      mountPath: /meili_data
      sizeGB: 1

  # --- MinIO (Private Service) ---
  - type: pserv
    name: samapiece-minio
    runtime: image
    image:
      url: docker.io/bitnamilegacy/minio@sha256:451fe6858cb770cc9d0e77ba811ce287420f781c7c1b806a386f6896471a349c
    plan: <plan payant à choisir manuellement>
    envVars:
      - key: MINIO_ROOT_USER
        sync: false
      - key: MINIO_ROOT_PASSWORD
        sync: false
    disk:
      name: minio-data
      mountPath: /data
      sizeGB: 1
    # (hypothèse à confirmer : la commande de démarrage `server /data --console-address ":9001"` et
    #  `user: root` (nécessaires en docker-compose, voir commentaire dans docker-compose.yml) doivent
    #  être reproduits via `dockerCommand:`/équivalent Blueprint — syntaxe exacte non certaine)

  # --- RabbitMQ (Private Service) ---
  - type: pserv
    name: samapiece-rabbitmq
    runtime: image
    image:
      url: docker.io/library/rabbitmq:3-management-alpine
    plan: <plan payant à choisir manuellement>
    envVars:
      - key: RABBITMQ_DEFAULT_USER
        sync: false
      - key: RABBITMQ_DEFAULT_PASS
        sync: false
```

Notes de contrat :
- Toute valeur marquée `sync: false` est saisie manuellement dans le dashboard Render au moment du
  déploiement — jamais commitée, même en placeholder.
- `MINIO_BUCKET_PHOTOS=samapiece-photos-demo` et `MEILISEARCH_INDEX_PIECES=pieces` sont des valeurs non
  sensibles écrites en clair (cohérent avec Décision clé 4 du design) ; ce ne sont pas des identifiants
  d'authentification.
- Le point `MEILISEARCH_API_KEY: "${MEILI_MASTER_KEY}"` ci-dessus est signalé explicitement comme
  incertain : si l'interpolation entre variables n'est pas supportée par le format Blueprint réel, la
  solution de repli documentée est de dupliquer la même valeur secrète sous les deux clés
  `MEILI_MASTER_KEY` et `MEILISEARCH_API_KEY` dans le groupe `samapiece-secrets` (le service Meilisearch
  et le backend doivent partager la même clé), au prix d'une duplication assumée plutôt que d'une
  syntaxe inventée.
- `runtime: docker` avec `dockerfilePath`/`dockerContext` vs `runtime: image` avec `image.url` : les deux
  formes sont utilisées ci-dessus (backend/frontend buildés depuis leur Dockerfile existant ; MinIO/
  RabbitMQ/Meilisearch depuis une image Docker Hub, comme en docker-compose) — la disponibilité de
  `runtime: image` pour les `pserv` est une hypothèse à confirmer au moment du "Deploy Blueprint" ; si
  cette forme n'existe pas, la solution de repli documentée dans le README est un `Dockerfile` minimal
  `FROM <image>` par service tiers (nouveau fichier hors périmètre de ce spec si nécessaire).

## Plan de tests / vérifications

| Critère d'acceptation du ticket | Vérifiable par ce bolt (comment) | Reste manuel post-déploiement |
|---|---|---|
| Backend accessible publiquement, `GET /actuator/health` → `200` | Démarrage local du backend en profil `staging` corrigé (`SPRING_PROFILES_ACTIVE=staging mvn -pl backend spring-boot:run` avec toutes les variables d'env requises positionnées, y compris des valeurs factices pour Redis/Meilisearch/RabbitMQ/MinIO pointant vers les conteneurs docker-compose locaux) + `curl -i http://localhost:8080/actuator/health` → `200` en local. Build Docker de l'image backend (`docker build ./backend`) réussit. | Oui — confirmation de l'URL publique Render et du `200` en ligne. |
| Frontend accessible publiquement, parcours fonctionnel bout-en-bout (recherche publique, connexion agent, enregistrement, dashboard, retrait/signalement/déblocage) | Build Docker de l'image frontend (`docker build ./frontend --build-arg VITE_SENTRY_DSN=`) réussit. `docker-compose up -d --build` en local avec `BACKEND_HOST=backend` ajouté : `curl -i http://localhost:8081/api/v1/postes` retourne `200`/`Content-Type: application/json` (non régression du proxy après paramétrisation, cf. procédure déjà documentée dans le README existant). | Oui — parcours fonctionnel réel contre le backend Render déployé, dans le même réseau régional. |
| Postgres, Redis, Meilisearch, MinIO, RabbitMQ tournent sur Render et sont réellement utilisés (pas de fallback silencieux) | `render.yaml` déclare bien les 5 services et leur injection de variables au backend (relecture manuelle du fichier). Démarrage local en profil `staging` avec les 5 dépendances réellement démarrées (via `docker-compose up` sur les services annexes) valide que le backend s'y connecte effectivement en dehors du profil `dev`. | Oui pour Postgres/Meilisearch/MinIO/RabbitMQ (aucun fallback prévu dans le code, une erreur de connexion fait échouer le démarrage ou l'opération). **Pour Redis, voir Écarts identifiés : le fail-open déjà acté (#19) peut masquer une mauvaise configuration TLS/auth sur le Redis managé Render — non vérifiable sans compte réel.** |
| Un premier compte administrateur est utilisable dès le déploiement initial | Relecture de `AdminBootstrapRunner`/`AdminBootstrapRunnerIntegrationTest` (déjà existants, ticket #58, non modifiés) : le mécanisme fonctionne dès qu'un `Poste` existe et que les 4 variables sont positionnées — confirmé par les tests existants, pas de nouveau test nécessaire pour ce ticket. Documentation de la procédure d'insertion manuelle Région/Poste vérifiée par relecture croisée avec `backend/README.md` (déjà correcte et complète, seulement référencée depuis la nouvelle section Render du README racine). | Oui — exécution réelle de l'insertion SQL via le Shell Render puis vérification du login `POST /api/v1/auth/login` en ligne. |
| Aucun secret en clair dans `render.yaml` ni dans l'historique git | Relecture manuelle exhaustive de `render.yaml` : toute valeur pouvant s'apparenter à un identifiant, mot de passe ou clé utilise `sync: false` sans valeur. `git diff`/`git log -p` sur `render.yaml` avant commit pour confirmer l'absence de valeur committée par erreur. | Non applicable — entièrement vérifiable en amont du déploiement. |
| Procédure de déploiement et redéploiement documentée | Relecture de la nouvelle section README (présence des étapes : Blueprint, secrets dashboard, déploiement initial, redéploiement manuel, Flyway automatique, bootstrap admin, rappel démo/pas de données réelles). | Non applicable — livrable documentaire, pas d'exécution requise. |

Tests automatisés à écrire/adapter par le codeur :
- Aucun nouveau test JUnit/Mockito/Testcontainers requis : aucune classe backend n'est modifiée hors
  fichier de configuration `application-staging.yml` (pas de logique testable unitairement), et les
  tests existants (`AdminBootstrapRunnerIntegrationTest`, tests des filtres Redis) couvrent déjà le
  comportement de production concerné.
- Aucun nouveau test Vitest/composant React requis : aucun composant frontend n'est modifié (seuls
  `nginx.conf.template`/`Dockerfile`, hors périmètre de test unitaire JS).
- Vérification manuelle obligatoire avant merge : `mvn -pl backend test` (non-régression) et un
  démarrage local complet `docker-compose up -d --build` suivi du test proxy déjà documenté dans le
  README (`curl -i http://localhost:8081/api/v1/postes`).

## Écarts identifiés

- **Redis managé Render (TLS/auth) vs AC "pas de fallback silencieux vers un mode dégradé"** : le code
  actuel (`RedisRateLimiterConfig`, `EchecRechercheCounterService`, `DefiMathematiqueCaptchaVerifier`)
  ne configure ni mot de passe ni TLS pour se connecter à Redis (`spring.data.redis.host`/`port` seuls,
  `RedisURI.Builder.redis(host, port)` sans authentification). Si le Redis managé de Render impose une
  authentification et/ou TLS (probable pour un service managé public), le rate-limiting/CAPTCHA de la
  recherche publique basculera silencieusement en mode dégradé (fail-open déjà acté au ticket #19) sans
  qu'aucune erreur ne soit visible dans `/actuator/health` — ce qui contredit littéralement le troisième
  critère d'acceptation du ticket ("pas de fallback silencieux vers un mode dégradé") pour ce service
  précis. Le design classe ce point en risque non tranché et l'exclut explicitement du périmètre
  ("Trancher la question du TLS sur le Redis managé Render" est listé en Hors périmètre) : ce spec ne
  crée donc pas de tâche de code pour l'adresser (respect du périmètre acté), mais signale que la
  vérification manuelle post-déploiement doit impérativement inclure un test actif du rate-limiting
  (déclencher plusieurs recherches publiques rapprochées et confirmer qu'une limitation se déclenche
  réellement) et pas seulement un `GET /actuator/health` à `200`, faute de quoi ce critère d'acceptation
  resterait non vérifié même après un déploiement "réussi".
- **Format exact du Blueprint Render** : plusieurs clés du `render.yaml` proposé ci-dessus
  (`type: redis` pour le service Key Value, `runtime: image`/`image.url` pour les `pserv` basés sur une
  image Docker Hub tierce, capacité à fixer une commande de démarrage/utilisateur pour l'image MinIO,
  interpolation `"${MEILI_MASTER_KEY}"` dans une valeur `value:`) ne sont pas certifiées par lecture du
  code du repo — elles s'appuient sur la connaissance générale du format Blueprint Render et sont
  marquées comme hypothèses à confirmer lors du "Deploy Blueprint" manuel, conformément à la contrainte
  du ticket. Aucune de ces incertitudes ne bloque la livraison du code/config : en cas d'écart avec le
  format réel au moment du déploiement, seule la syntaxe de `render.yaml` doit être ajustée (pas
  l'architecture des 7 services ni les décisions du design).
- **Bucket MinIO démo** : le design ne fixe pas de valeur pour `MINIO_BUCKET_PHOTOS` en environnement
  Render ; ce spec propose `samapiece-photos-demo` par cohérence avec `samapiece-photos-dev` dans
  `.env.example`, à ajuster librement lors du déploiement (valeur non sensible, sans impact sur la
  sécurité).
