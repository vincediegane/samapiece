# Design — Ticket #5 : Modele Region/Poste + migration Flyway initiale

Etat constate du repo (verifie via Read/Grep/Bash sur les fichiers reels, branche `bolt/issue-5-region-poste-model`) :

- `backend/pom.xml` : parent `spring-boot-starter-parent:3.3.13`. Dependances deja presentes : `spring-boot-starter-web`, `-security`, `-data-jpa`, `-validation`, `-actuator`, `-test`, `spring-security-test`. Aucune dependance Flyway, aucun driver JDBC PostgreSQL, aucun Testcontainers : tout est a ajouter dans ce ticket.
- `backend/src/main/resources/application.yml` : contient bien `spring.autoconfigure.exclude: DataSourceAutoConfiguration,HibernateJpaAutoConfiguration` avec le TODO explicite "a retirer des qu'une entite JPA et une migration Flyway reelles sont introduites" (pose au ticket #1). C'est exactement ce ticket. `application-dev.yml` / `application-staging.yml` / `application-prod.yml` ont deja les blocs `spring.datasource` (commentes "inactifs" en dev) pointant vers `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` -- memes noms que `docker-compose.yml` / `.env.example` (`DB_NAME=samapiece_dev`, `DB_USER=samapiece`, image `postgres:16-alpine`).
- Aucun dossier `db/migration` n'existe encore (premiere migration Flyway du projet).
- Packages de domaine existants : `sn.samapiece.enregistrement`, `.recherche`, `.notifications`, `.retraitaudit`, `.iam`, `.reporting`, chacun reduit a un `package-info.java` d'une ligne (aucune classe metier nulle part dans le repo a ce jour). Aucun n'est un candidat naturel pour `Region`/`Poste` : ces entites sont referencees a la fois par `iam` (agent rattache a un poste) et `enregistrement` (piece deposee dans un poste), donc propriete d'aucun des deux.
- Aucune classe `SecurityFilterChain` / `@EnableWebSecurity` nulle part dans `backend/src` : la securite tourne entierement sur les valeurs par defaut de `spring-boot-starter-security` (utilisateur genere, mot de passe aleatoire au demarrage, `anyRequest().authenticated()` implicite). Verifie en executant le test existant : `/actuator/health` repond 200 sans authentification (comportement par defaut de `ManagementWebSecurityAutoConfiguration`, qui autorise specifiquement `/actuator/health/**` et securise le reste des endpoints actuator) -- mais cette autorisation ne couvre que les endpoints actuator, pas un futur `/api/v1/postes`.
- `.github/workflows/backend.yml` : un seul job `mvn -pl backend -am verify` sur `ubuntu-latest` (Docker disponible nativement sur ce runner GitHub-hosted, donc Testcontainers fonctionnera sans configuration CI supplementaire).
- `backend/src/test/java/sn/samapiece/SamaPieceApplicationTests.java` : `@SpringBootTest` + `@AutoConfigureMockMvc` chargeant le contexte Spring complet avec le profil `dev` actif. Une fois l'exclusion levee, ce test tentera d'ouvrir une vraie datasource JPA -- a traiter (voir Risques).

## Approche

On introduit un nouveau package transverse `sn.samapiece.referentiel` (premiere brique de donnees metier du repo) portant les entites JPA `Region`/`Poste`, leurs repositories Spring Data et un controleur public en lecture seule. C'est le moment de lever l'exclusion `DataSourceAutoConfiguration`/`HibernateJpaAutoConfiguration` dans `application.yml` : le TODO du ticket #1 pointait explicitement vers "la premiere migration Flyway reelle", qui est celle-ci. On ajoute Flyway (+ module PostgreSQL dedie, requis par Flyway 10.x fourni par Spring Boot 3.3), le driver `org.postgresql:postgresql`, et Testcontainers pour les tests. Les coordonnees geographiques sont stockees en colonnes `latitude`/`longitude DOUBLE PRECISION` simples plutot qu'en type geometrique PostGIS : suffisant pour un affichage sur carte (besoin exprime par le critere d'acceptation), et ca evite d'introduire une extension Postgres + `hibernate-spatial` + une image Testcontainers dediee pour un besoin qui n'inclut aucune requete geospatiale (rayon, tri par proximite) dans ce ticket. Prix de ce choix : si une recherche "poste le plus proche" est demandee plus tard, une migration additionnelle sera necessaire pour passer a PostGIS.

## Fichiers / modules impactes

Nouveaux :
- `backend/src/main/resources/db/migration/V1__create_region_poste.sql` -- migration Flyway (tables `region`, `poste`).
- `backend/src/main/java/sn/samapiece/referentiel/package-info.java` -- nouveau package transverse (referentiel geographique partage, pas de bounded context dedie dans le document produit).
- `backend/src/main/java/sn/samapiece/referentiel/Region.java` -- entite JPA.
- `backend/src/main/java/sn/samapiece/referentiel/Poste.java` -- entite JPA (+ enum `TypePoste` avec POLICE et GENDARMERIE, fichier separe ou nested).
- `backend/src/main/java/sn/samapiece/referentiel/RegionRepository.java` -- `JpaRepository<Region, UUID>`.
- `backend/src/main/java/sn/samapiece/referentiel/PosteRepository.java` -- `JpaRepository<Poste, UUID>`, avec `@EntityGraph(attributePaths = "region")` sur la methode de listing pour eviter le N+1 sur `GET /api/v1/postes`.
- `backend/src/main/java/sn/samapiece/referentiel/web/PosteController.java` -- `GET /api/v1/postes`.
- `backend/src/main/java/sn/samapiece/referentiel/web/PosteResponse.java` -- DTO record de sortie (ne jamais serialiser l'entite JPA directement : evite de fuiter des champs internes/proxies Hibernate lazy).
- `backend/src/main/java/sn/samapiece/config/package-info.java` et `backend/src/main/java/sn/samapiece/config/SecurityConfig.java` -- `SecurityFilterChain` minimal : autorise sans authentification `GET /api/v1/postes` (et conserve `authenticated()` par defaut pour le reste), avec un commentaire explicite indiquant que ce bean sera remplace/etendu par le futur ticket IAM (RBAC complet). Necessaire car aucun `SecurityFilterChain` n'existe aujourd'hui et le comportement par defaut de Spring Security exigerait une authentification sur toute route non-actuator.
- `backend/src/test/java/sn/samapiece/referentiel/PosteIntegrationTest.java` -- test Testcontainers (migration Flyway rejouee + `GET /api/v1/postes`).

Modifies :
- `backend/pom.xml` -- ajout de `org.flywaydb:flyway-core`, `org.flywaydb:flyway-database-postgresql` (module separe depuis Flyway 10, requis pour PostgreSQL), `org.postgresql:postgresql` (scope runtime), `org.testcontainers:postgresql` et `org.springframework.boot:spring-boot-testcontainers` (scope test).
- `backend/src/main/resources/application.yml` -- suppression du bloc `spring.autoconfigure.exclude` (et de son commentaire TODO, devenu obsolete).
- `backend/src/test/java/sn/samapiece/SamaPieceApplicationTests.java` -- doit etre adapte pour fonctionner avec une vraie datasource (voir Risques) : ajout de Testcontainers (`@Testcontainers` + `@ServiceConnection` sur un `PostgreSQLContainer` configure avec l'image `postgres:16-alpine`), faute de quoi ce test cassera des que l'exclusion est levee.
- `backend/README.md` -- mention que le backend necessite desormais une base PostgreSQL reelle (via `docker-compose up postgres` ou equivalent) pour demarrer en dehors des tests ; mettre a jour le paragraphe qui decrivait l'exclusion.

## Schema des tables (V1__create_region_poste.sql)

```sql
CREATE TABLE region (
    id   UUID PRIMARY KEY,
    nom  VARCHAR(255) NOT NULL
);

CREATE TABLE poste (
    id          UUID PRIMARY KEY,
    region_id   UUID NOT NULL REFERENCES region(id),
    nom         VARCHAR(255) NOT NULL,
    type        VARCHAR(20) NOT NULL CHECK (type IN ('police', 'gendarmerie')),
    adresse     VARCHAR(500) NOT NULL,
    telephone   VARCHAR(30),
    horaires    JSONB NOT NULL DEFAULT '{}'::jsonb,
    latitude    DOUBLE PRECISION,
    longitude   DOUBLE PRECISION,
    cree_le     TIMESTAMPTZ NOT NULL DEFAULT now(),
    maj_le      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_poste_region_id ON poste(region_id);
```

Les `id` sont des `UUID` sans `DEFAULT gen_random_uuid()` : generes cote application (Hibernate `GenerationType.UUID`, natif depuis Hibernate 6) pour eviter de dependre d'une extension Postgres (`pgcrypto`/`uuid-ossp`) juste pour ca.

## Decisions cles

- Reactivation du datasource/JPA : on retire l'exclusion posee au ticket #1. C'est le point de bascule attendu par le TODO existant, pas une option : consequence directe du fait que ce ticket introduit la premiere vraie table.
- Package `sn.samapiece.referentiel` (nouveau, absent de la liste des 6 modules du paragraphe 11.3 de `PROJET-SAMAPIECE.md`) plutot que de loger `Region`/`Poste` dans `enregistrement` ou `iam` : ces donnees sont un referentiel partage consomme par plusieurs bounded contexts (agents rattaches a un poste, pieces deposees dans un poste), pas la propriete exclusive de l'un d'eux. Ecart assume par rapport au document produit, a signaler au spec-writer.
- Coordonnees geo en `latitude`/`longitude DOUBLE PRECISION`, pas en type PostGIS `geometry(Point)` malgre la mention PostGIS au paragraphe 11.4 : le besoin de ce ticket est un affichage carte simple (pas de requete de proximite). A reevaluer si un futur ticket demande un tri/filtre geospatial.
- `horaires` en `JSONB`, mappe cote JPA en `String` brut via `@JdbcTypeCode(SqlTypes.JSON)` (support natif Hibernate 6, aucune dependance supplementaire) plutot qu'en objet Java structure. Structure JSON proposee (a valider par le spec-writer) : un objet par jour de semaine, par exemple horaires du lundi avec ouvert=true, debut=08:00, fin=18:00, et dimanche avec ouvert=false. Rester en `String` evite d'introduire une classe `Horaires` avec (de)serialisation Jackson custom pour un besoin qui n'exige, dans ce ticket, que du stockage/restitution telle quelle.
- DTO de sortie dedie (`PosteResponse`) plutot que serialisation directe de l'entite `Poste` : evite de serialiser le proxy Hibernate `region` (lazy) et prepare le terrain pour masquer plus tard des champs si `Poste` gagne des attributs internes non destines au public.
- `SecurityFilterChain` minimal ajoute dans `sn.samapiece.config` pour rendre `GET /api/v1/postes` reellement public : sans lui, le comportement par defaut de Spring Security (deja en place, non configure) exigerait une authentification sur toute route applicative. Ce n'est pas une implementation RBAC complete, juste une autorisation ciblee, en attendant le futur ticket IAM.
- Testcontainers avec l'image `postgres:16-alpine` : identique a la version utilisee dans `docker-compose.yml` (coherence dev/CI), au lieu d'une image H2 en memoire -- le but explicite du ticket est de tester la migration Flyway contre un vrai moteur PostgreSQL (JSONB, contraintes CHECK).

## Risques / points d'attention

- `SamaPieceApplicationTests` cassera silencieusement une fois l'exclusion levee si on ne le modifie pas : ce test `@SpringBootTest` avec profil `dev` tentera d'ouvrir une vraie datasource JPA. En CI, `DB_USER`/`DB_PASSWORD` ne sont pas definis comme variables d'environnement (ils viennent normalement de `.env`/docker-compose), donc le contexte echouera a demarrer. Mitigation retenue : brancher ce test lui aussi sur Testcontainers (`@ServiceConnection`), qui reinjecte `spring.datasource.*` avec une priorite superieure a celle des placeholders `DB_USER`/`DB_PASSWORD` du profil `dev` -- aucune variable d'environnement a fournir en CI.
- Premier vrai usage de Docker-in-CI : le job GitHub Actions existant ne fait tourner que `mvn verify` sans service Postgres dedie ; Testcontainers demarre son propre conteneur a la volee. Ca fonctionne nativement sur les runners `ubuntu-latest` (Docker preinstalle), mais rallonge le temps de build (pull de l'image `postgres:16-alpine` + demarrage du conteneur) -- pas bloquant, a noter pour ne pas confondre avec une regression.
- Flyway 10 (fourni par Spring Boot 3.3.13) a extrait le support PostgreSQL dans un module separe (`flyway-database-postgresql`) : oublier cette dependance fait echouer la migration au demarrage avec une erreur du type base non geree -- a verifier explicitement par le codeur.
- Demarrage local sans Docker/`.env` : une fois l'exclusion levee, `mvn -pl backend spring-boot:run` sans base PostgreSQL locale (ou sans `docker-compose up postgres`) echouera desormais au demarrage -- comportement attendu et documente, mais casse le confort des tickets #1/#3 (ca demarrait toujours sans base). A documenter clairement dans `backend/README.md`.
- Le `SecurityFilterChain` ajoute est un minimum volontairement etroit : verifier en revue qu'il n'autorise sans authentification que `GET /api/v1/postes` (pas tout `/api/v1/**`), pour ne pas ouvrir accidentellement de futures routes non publiques ajoutees par des tickets suivants dans le meme package.
- Coherence des identifiants Postgres avec `docker-compose.yml` : `DB_NAME=samapiece_dev`, `DB_USER=samapiece` sont deja coherents entre `.env.example` et `application-dev.yml` -- rien a changer, mais le codeur doit verifier qu'aucune valeur n'est dupliquee/divergente en l'implementant.
- Minimisation des donnees personnelles : `Region`/`Poste` ne contiennent aucune donnee personnelle (adresse/telephone d'un poste de police, pas d'un individu) -- aucun hash/chiffrement requis ici, contrairement aux futures tables `PIECE`/`AGENT`/`CITOYEN_ALERTE` du paragraphe 9.

## Hors perimetre

- Entites `AGENT`, `PIECE`, et toute autre table du paragraphe 9.1 : ce ticket ne couvre que `REGION`/`POSTE`.
- RBAC complet, authentification agent, roles agent/chef_poste/etc. : le `SecurityFilterChain` ajoute ici est strictement limite a rendre `GET /api/v1/postes` public, pas une implementation du module `iam`.
- CRUD complet sur `Region`/`Poste` (creation/mise a jour/suppression d'un poste par un admin) : le ticket ne demande qu'un `GET` public de listing. Pas d'endpoint d'ecriture.
- PostGIS / requetes geospatiales (recherche par proximite, rayon) : reporte a un ticket futur si le besoin se confirme.
- Indexation Meilisearch des postes : le document produit mentionne un moteur de recherche separe pour le module `recherche`, mais ce ticket ne branche rien dessus (le listing public passe directement par PostgreSQL/JPA).
- Pagination/filtrage de `GET /api/v1/postes` : non demande par les criteres d'acceptation ; a reevaluer si le nombre de postes devient significatif (national = plus de 1000 postes selon le paragraphe 11.6, mais hors echelle du pilote).
