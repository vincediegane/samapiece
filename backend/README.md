# SamaPièce — Backend

API Spring Boot du projet SamaPièce.

## Prérequis

- JDK 21
- Maven 3.9+ (ou le wrapper Maven du dépôt s'il est ajouté ultérieurement)

## Lancer en local

Le backend nécessite désormais une base PostgreSQL réelle pour démarrer (les migrations
Flyway sont appliquées automatiquement au démarrage). Démarrer d'abord la base, par exemple
via Docker Compose depuis la racine du monorepo :

```bash
docker-compose up postgres
```

ou toute instance PostgreSQL locale équivalente exposant les mêmes variables
`DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD` attendues par le profil `dev`.

Le backend nécessite également la variable `JWT_SECRET` (obligatoire, aucune valeur par
défaut) : un secret HMAC-SHA256 d'au moins 256 bits, soit au moins 32 caractères, utilisé pour
signer les access/refresh tokens émis par `POST /api/v1/auth/login`. En générer une valeur en
local avec :

```bash
openssl rand -base64 32
```

Puis, depuis la racine du monorepo :

```bash
mvn -pl backend spring-boot:run
```

ou, depuis `backend/` :

```bash
mvn spring-boot:run
```

Le serveur démarre par défaut sur `http://localhost:8080` avec le profil `dev` actif
(`SPRING_PROFILES_ACTIVE=dev` par défaut). Vérifier le démarrage :

```bash
curl -i http://localhost:8080/actuator/health
```

doit répondre `200 OK`.

## Lancer les tests

```bash
mvn -pl backend test
```

## Profils disponibles

- `dev` (défaut) : valeurs par défaut non sensibles (ex. `localhost`) pour la configuration
  datasource, activées via `SPRING_PROFILES_ACTIVE=dev`.
- `staging` : aucune valeur par défaut, toute la configuration sensible passe par variables
  d'environnement (`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`).
- `prod` : idem `staging`.

Changer de profil :

```bash
SPRING_PROFILES_ACTIVE=staging mvn -pl backend spring-boot:run
```

## Notes

- Le backend nécessite une base PostgreSQL réelle pour démarrer hors tests : les migrations
  Flyway (`src/main/resources/db/migration`) sont appliquées automatiquement au démarrage
  contre la base configurée par le profil actif.
- Les tests, eux, n'ont besoin d'aucune base locale : ils utilisent Testcontainers, qui
  démarre son propre conteneur PostgreSQL éphémère (image `postgres:16-alpine`) pour chaque
  exécution.
