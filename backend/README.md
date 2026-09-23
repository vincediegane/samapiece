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

## Bootstrap du premier compte administrateur

Sur une base neuve, aucun agent n'existe et `POST /api/v1/agents` exige déjà un rôle
`ADMIN_NATIONAL` authentifié — personne ne peut donc se connecter sans un mécanisme de bootstrap.

### Prérequis

Ce mécanisme ne crée ni Région ni Poste automatiquement. Créer manuellement au moins une paire
Région/Poste en base avant d'activer le bootstrap, par exemple :

```sql
INSERT INTO region (id, nom) VALUES (gen_random_uuid(), 'Dakar');

INSERT INTO poste (id, region_id, nom, type, adresse, horaires)
VALUES (
    gen_random_uuid(),
    (SELECT id FROM region WHERE nom = 'Dakar'),
    'Commissariat Central Dakar',
    'police',
    'Place de l''Indépendance, Dakar',
    '{}'
);
```

Noter l'`id` du poste créé (`SELECT id FROM poste WHERE nom = '...'`) : il sera nécessaire pour
`BOOTSTRAP_ADMIN_POSTE_ID`.

### Variables d'environnement

- `BOOTSTRAP_ADMIN_ENABLED` (défaut `false`) — active le mécanisme au démarrage.
- `BOOTSTRAP_ADMIN_MATRICULE` — matricule du premier compte administrateur.
- `BOOTSTRAP_ADMIN_NOM` — nom du premier compte administrateur.
- `BOOTSTRAP_ADMIN_POSTE_ID` — UUID du poste (voir prérequis ci-dessus).

### Procédure

1. Positionner les 4 variables ci-dessus.
2. Démarrer le backend une fois. Si la base est vide, un agent `ADMIN_NATIONAL` est créé et son
   matricule ainsi que son mot de passe temporaire sont affichés en clair dans les logs de
   démarrage, au niveau `WARN`.
3. Se connecter avec ces identifiants via `POST /api/v1/auth/login`.
4. Changer immédiatement le mot de passe via `PUT /api/v1/agents/moi/mot-de-passe` : toute autre
   route protégée renvoie `403 MOT_DE_PASSE_TEMPORAIRE_NON_CHANGE` tant que ce changement n'est
   pas effectué (seules `GET /api/v1/agents/moi`, `POST /api/v1/auth/refresh` et les endpoints
   `/actuator/*` restent accessibles entre-temps).

### Idempotence et sécurité

- Le mécanisme est un no-op si `BOOTSTRAP_ADMIN_ENABLED` n'est pas `true`, ou si un agent existe
  déjà en base (quel que soit son rôle) — laisser la variable positionnée dans un pipeline de
  déploiement ne crée jamais de doublon.
- Laisser `BOOTSTRAP_ADMIN_ENABLED=false` par défaut en `staging`/`prod` : ne l'activer que le
  temps du tout premier démarrage sur une base vide, puis le désactiver de nouveau.
- Le mot de passe temporaire apparaît en clair dans les logs de démarrage : s'assurer qu'aucun
  agrégateur de logs ne le persiste indéfiniment.

## Notes

- Le backend nécessite une base PostgreSQL réelle pour démarrer hors tests : les migrations
  Flyway (`src/main/resources/db/migration`) sont appliquées automatiquement au démarrage
  contre la base configurée par le profil actif.
- Les tests, eux, n'ont besoin d'aucune base locale : ils utilisent Testcontainers, qui
  démarre son propre conteneur PostgreSQL éphémère (image `postgres:16-alpine`) pour chaque
  exécution.
