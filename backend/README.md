# SamaPièce — Backend

API Spring Boot du projet SamaPièce.

## Prérequis

- JDK 21
- Maven 3.9+ (ou le wrapper Maven du dépôt s'il est ajouté ultérieurement)

## Lancer en local

Depuis la racine du monorepo :

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

- `spring-boot-starter-data-jpa` est présent en dépendance mais l'autoconfiguration
  datasource/JPA est temporairement exclue (voir `application.yml`) tant qu'aucune entité
  JPA ni migration Flyway réelle n'existe. Cette exclusion sera retirée par le ticket qui
  introduira la persistance.
