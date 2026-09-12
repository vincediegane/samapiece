# Spec — Ticket #5 : Modèle Region/Poste + migration Flyway initiale

## Résumé

Introduction de la première migration Flyway (`region`, `poste`), des entités JPA/repositories correspondants dans un nouveau package `sn.samapiece.referentiel`, d'un endpoint public `GET /api/v1/postes` (lecture seule, avec DTO dédié) protégé par un `SecurityFilterChain` minimal, et d'un test d'intégration Testcontainers validant migration + lecture.

## Tâches

- [ ] **`backend/pom.xml`** — ajouter les dépendances : `org.flywaydb:flyway-core`, `org.flywaydb:flyway-database-postgresql` (module obligatoire depuis Flyway 10 pour PostgreSQL — son absence fait échouer la migration au démarrage), `org.postgresql:postgresql` (scope runtime), `org.testcontainers:postgresql` et `org.springframework.boot:spring-boot-testcontainers` (scope test). Ne pas fixer de version explicite : laisser le parent `spring-boot-starter-parent:3.3.13` gérer le BOM (sauf pour `org.testcontainers:postgresql` si le BOM Testcontainers n'est pas géré, auquel cas vérifier la version compatible Spring Boot 3.3 — cf. `spring-boot-dependencies` gère normalement `testcontainers-bom`).

- [ ] **`backend/src/main/resources/db/migration/V1__create_region_poste.sql`** — créer la migration avec le schéma exact ci-dessous (section Contrat technique).

- [ ] **`backend/src/main/resources/application.yml`** — supprimer entièrement le bloc `spring.autoconfigure.exclude` et son commentaire TODO (lignes 6-13 actuelles). Ne rien ajouter d'autre dans ce fichier (la config datasource reste dans les fichiers `application-{profile}.yml` existants, déjà corrects).

- [ ] **`backend/src/main/java/sn/samapiece/referentiel/package-info.java`** — package-info d'une ligne, sur le modèle des packages existants (`enregistrement`, `iam`, etc.), précisant qu'il s'agit d'un référentiel géographique partagé (pas un bounded context métier).

- [ ] **`backend/src/main/java/sn/samapiece/referentiel/TypePoste.java`** — enum `POLICE`, `GENDARMERIE` (fichier séparé, pas nested, pour cohérence avec le reste du repo qui n'a pas encore de convention nested-enum).

- [ ] **`backend/src/main/java/sn/samapiece/referentiel/Region.java`** — entité JPA `Region` (signature complète en section Contrat technique).

- [ ] **`backend/src/main/java/sn/samapiece/referentiel/Poste.java`** — entité JPA `Poste` avec relation `@ManyToOne` vers `Region`, colonne `horaires` mappée en `String` via `@JdbcTypeCode(SqlTypes.JSON)` (signature complète en section Contrat technique).

- [ ] **`backend/src/main/java/sn/samapiece/referentiel/RegionRepository.java`** — `interface RegionRepository extends JpaRepository<Region, UUID>`. Aucune méthode custom requise pour ce ticket.

- [ ] **`backend/src/main/java/sn/samapiece/referentiel/PosteRepository.java`** — `interface PosteRepository extends JpaRepository<Poste, UUID>` avec une méthode de listing annotée `@EntityGraph(attributePaths = "region")` pour charger `region` en un seul aller-retour SQL (éviter le N+1 lors de la sérialisation en DTO). Nom suggéré : `List<Poste> findAll()` ne peut pas être ré-annoté (méthode héritée) — définir explicitement `@EntityGraph(attributePaths = "region") List<Poste> findAllBy();` ou surcharger `findAll()` via `@Override @EntityGraph(...) List<Poste> findAll();` (les deux fonctionnent avec Spring Data JPA ; retenir l'override de `findAll()` pour rester simple côté controller).

- [ ] **`backend/src/main/java/sn/samapiece/config/package-info.java`** — package-info d'une ligne pour le nouveau package `sn.samapiece.config`.

- [ ] **`backend/src/main/java/sn/samapiece/config/SecurityConfig.java`** — `@Configuration` + `@EnableWebSecurity` définissant un unique bean `SecurityFilterChain` qui autorise sans authentification **uniquement** `GET /api/v1/postes`, et exige l'authentification pour tout le reste (`anyRequest().authenticated()`), CSRF désactivé pour une API stateless (ou laissé par défaut si cohérent avec le reste — à trancher simplement en excluant `/api/v1/**` des vérifications CSRF n'est pas nécessaire ici puisque seule une route GET est publique ; ne pas désactiver CSRF globalement sans nécessité). Ajouter un commentaire explicite indiquant que ce bean est un minimum temporaire, remplacé/étendu par le futur ticket IAM (RBAC complet).

- [ ] **`backend/src/main/java/sn/samapiece/referentiel/web/PosteResponse.java`** — `record` DTO de sortie (champs détaillés en section Contrat technique), avec une méthode statique `from(Poste poste)` (ou équivalent) pour la conversion entité → DTO.

- [ ] **`backend/src/main/java/sn/samapiece/referentiel/web/PosteController.java`** — `@RestController` exposant `GET /api/v1/postes`, retournant `List<PosteResponse>`, statut `200 OK`.

- [ ] **`backend/src/test/java/sn/samapiece/SamaPieceApplicationTests.java`** — adapter pour fonctionner avec une vraie datasource : ajouter `@Testcontainers`, un champ statique `@Container` `PostgreSQLContainer<?>` sur l'image `postgres:16-alpine`, et l'annoter `@ServiceConnection` (Spring Boot 3.1+, injecte automatiquement `spring.datasource.*` avec priorité supérieure aux placeholders `DB_USER`/`DB_PASSWORD` du profil `dev` — aucune variable d'environnement à fournir en CI). Conserver les deux tests existants (`contextLoads`, `healthEndpoint_shouldReturn200`) inchangés dans leur assertion.

- [ ] **`backend/src/test/java/sn/samapiece/referentiel/PosteIntegrationTest.java`** — nouveau test Testcontainers (détail du plan de test en section dédiée).

- [ ] **`backend/README.md`** — remplacer la section "Notes" (lignes 53-58 actuelles) : indiquer que le backend nécessite désormais une base PostgreSQL réelle pour démarrer hors tests (via `docker-compose up postgres` ou une instance Postgres locale équivalente aux variables `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD` attendues par le profil `dev`), que les migrations Flyway sont appliquées automatiquement au démarrage, et que les tests, eux, n'en ont pas besoin (Testcontainers gère son propre conteneur éphémère). Mettre à jour aussi la section "Lancer en local" si elle laisse entendre qu'aucune base n'est requise.

## Contrat technique

### Schéma SQL (`V1__create_region_poste.sql`, à reprendre tel quel)

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

Pas de `DEFAULT gen_random_uuid()` : les `id` sont générés côté application via Hibernate (`GenerationType.UUID`).

### Entités JPA

`Region.java` :

```java
@Entity
@Table(name = "region")
public class Region {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "nom", nullable = false)
    private String nom;

    // constructeur protégé/no-args pour Hibernate, getters, equals/hashCode sur id
}
```

`TypePoste.java` :

```java
public enum TypePoste {
    POLICE,
    GENDARMERIE
}
```

Valeurs enum en anglais/majuscule côté Java ; mapper vers les valeurs SQL en minuscules (`police`, `gendarmerie`) via `@Enumerated(EnumType.STRING)` + une conversion explicite (soit un `@Converter` JPA, soit stocker la constante enum telle quelle si l'on aligne les valeurs SQL du `CHECK` sur `POLICE`/`GENDARMERIE` en majuscules — **à trancher par le codeur en cohérence avec le SQL ci-dessus qui utilise `police`/`gendarmerie` en minuscules** : le plus simple est un `AttributeConverter<TypePoste, String>` qui sérialise en minuscules, pour ne pas modifier la contrainte `CHECK` de la migration).

`Poste.java` :

```java
@Entity
@Table(name = "poste")
public class Poste {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "region_id", nullable = false)
    private Region region;

    @Column(name = "nom", nullable = false)
    private String nom;

    @Convert(converter = TypePosteConverter.class) // ou @Enumerated selon décision ci-dessus
    @Column(name = "type", nullable = false, length = 20)
    private TypePoste type;

    @Column(name = "adresse", nullable = false, length = 500)
    private String adresse;

    @Column(name = "telephone", length = 30)
    private String telephone;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "horaires", nullable = false, columnDefinition = "jsonb")
    private String horaires;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "cree_le", nullable = false, updatable = false)
    private OffsetDateTime creeLe;

    @Column(name = "maj_le", nullable = false)
    private OffsetDateTime majLe;

    // getters, constructeur no-args protégé pour Hibernate
}
```

Note : `cree_le`/`maj_le` ont un `DEFAULT now()` en base ; pas besoin de les renseigner à l'insert côté Java pour ce ticket (lecture seule, aucun endpoint d'écriture). Laisser Hibernate les lire tels quels depuis la base.

Structure JSON attendue pour `horaires` (convention à documenter en commentaire Javadoc sur le champ, stockée telle quelle en `String`, aucune validation de schéma dans ce ticket) :

```json
{
  "lundi":    { "ouvert": true,  "debut": "08:00", "fin": "18:00" },
  "mardi":    { "ouvert": true,  "debut": "08:00", "fin": "18:00" },
  "mercredi": { "ouvert": true,  "debut": "08:00", "fin": "18:00" },
  "jeudi":    { "ouvert": true,  "debut": "08:00", "fin": "18:00" },
  "vendredi": { "ouvert": true,  "debut": "08:00", "fin": "18:00" },
  "samedi":   { "ouvert": true,  "debut": "08:00", "fin": "13:00" },
  "dimanche": { "ouvert": false, "debut": null,    "fin": null }
}
```

### Endpoint `GET /api/v1/postes`

- Accès : public, sans authentification (via `SecurityConfig`).
- Réponse : `200 OK`, `Content-Type: application/json`, corps = tableau JSON (pas d'enveloppe de pagination pour ce ticket).

`PosteResponse` (record) :

```java
public record PosteResponse(
    UUID id,
    String nom,
    String type,        // "POLICE" ou "GENDARMERIE" (nom de l'enum, pas la valeur SQL)
    String adresse,
    String telephone,   // nullable
    String horaires,    // chaîne JSON brute
    Double latitude,    // nullable
    Double longitude,   // nullable
    RegionResume region
) {
    public record RegionResume(UUID id, String nom) {}

    public static PosteResponse from(Poste poste) { /* mapping direct des champs */ }
}
```

Exemple de payload pour un élément du tableau :

```json
{
  "id": "b6e6c4b0-1c1a-4e0e-9a1a-000000000001",
  "nom": "Commissariat Central Dakar",
  "type": "POLICE",
  "adresse": "Place de l'Indépendance, Dakar",
  "telephone": "+221338210000",
  "horaires": "{\"lundi\":{\"ouvert\":true,\"debut\":\"08:00\",\"fin\":\"18:00\"}, ...}",
  "latitude": 14.6928,
  "longitude": -17.4467,
  "region": { "id": "a1b2c3d4-...", "nom": "Dakar" }
}
```

### RBAC / sécurité

- `SecurityConfig` : un seul `SecurityFilterChain`. Règle exacte : `requestMatchers(HttpMethod.GET, "/api/v1/postes").permitAll()` puis `anyRequest().authenticated()`.
- **Ne pas** utiliser un pattern large type `"/api/v1/**"` ou `"/api/v1/postes/**"` pour la règle `permitAll` — se limiter strictement à la route exacte `GET /api/v1/postes` sans wildcard de sous-chemin, pour ne pas ouvrir accidentellement de futures routes ajoutées dans le même package.
- Conserver le comportement par défaut existant pour `/actuator/health` (déjà géré par `ManagementWebSecurityAutoConfiguration`, ne pas le dupliquer dans `SecurityConfig` sauf si son ajout casse ce comportement — à vérifier par le test `healthEndpoint_shouldReturn200` existant qui doit continuer à passer).

## Plan de tests

| Critère d'acceptation (ticket) | Test | Type |
|---|---|---|
| Migration Flyway `V1__create_region_poste.sql` crée `region` et `poste` avec les colonnes attendues | `PosteIntegrationTest` : au démarrage du contexte Spring (Testcontainers + Flyway auto-run), vérifier que le contexte charge sans erreur (Flyway échoue le démarrage si la migration est invalide) ; assertion explicite additionnelle en insérant directement via `JdbcTemplate`/`EntityManager` une `Region` puis un `Poste` avec tous les champs renseignés (y compris `horaires` JSON et `type` contraint) et en relisant les valeurs pour confirmer les types/colonnes (notamment que l'insertion d'un `type` hors `police`/`gendarmerie` est bien rejetée par la contrainte CHECK — test dédié `insertPosteAvecTypeInvalide_shouldFail` attendant une exception, ex. `DataIntegrityViolationException`) | Intégration Testcontainers |
| Entités JPA `Region`/`Poste` + repositories Spring Data | `PosteIntegrationTest` : persister une `Region` via `RegionRepository.save`, une `Poste` liée via `PosteRepository.save`, puis relire via `PosteRepository.findAll()` (ou `findAllBy()`) et vérifier que l'objet `Poste` récupéré a bien sa `Region` associée chargée (pas de `LazyInitializationException`, grâce à `@EntityGraph`) et que tous les champs (nom, type, adresse, telephone, horaires, latitude, longitude) correspondent aux valeurs insérées | Intégration Testcontainers |
| Endpoint public `GET /api/v1/postes` liste les postes | `PosteIntegrationTest` (avec `@AutoConfigureMockMvc`) : après avoir persisté au moins un `Poste` rattaché à une `Region` (via repository, en `@BeforeEach` ou directement dans le test), appeler `mockMvc.perform(get("/api/v1/postes"))` **sans authentification** et vérifier : statut `200`, `Content-Type` JSON, tableau de taille attendue, champs JSON attendus présents pour le premier élément (`$[0].id`, `$[0].nom`, `$[0].type`, `$[0].region.id`, `$[0].region.nom`, etc. via `jsonPath`) | Intégration Testcontainers (MockMvc) |
| Endpoint public au bon sens (sécurité) | Test dédié dans `PosteIntegrationTest` (ou test unitaire de sécurité séparé) : `GET /api/v1/postes` sans en-tête `Authorization` retourne `200` ; un `GET` sur une route arbitraire non listée (ex. `GET /api/v1/inexistant-proteges` ou tout endpoint protégé simulé) sans authentification retourne `401`/`403` pour confirmer que `permitAll()` ne s'applique pas à `anyRequest()`. Si aucune autre route protégée n'existe encore dans le repo pour servir de témoin, utiliser un endpoint de test minimal ou vérifier via `SecurityConfig`/`SecurityFilterChain` que le matcher est bien scoping — a minima documenter ce choix dans le test | Intégration Testcontainers (MockMvc) — sinon test manuel documenté si aucune route protégée n'est disponible comme témoin |
| Régression : `/actuator/health` reste public après ajout de `SecurityConfig` | `SamaPieceApplicationTests.healthEndpoint_shouldReturn200` (test existant, doit continuer à passer une fois adapté à Testcontainers) | Intégration (`@SpringBootTest` + MockMvc) |
| Régression : le contexte Spring démarre toujours (avec vraie datasource) | `SamaPieceApplicationTests.contextLoads` (test existant, adapté à Testcontainers) | Intégration Testcontainers |
| Dépendance `flyway-database-postgresql` correctement déclarée | Couvert indirectement : si absente, tout test Testcontainers ci-dessus échoue au démarrage du contexte avec une erreur Flyway explicite (pas de test dédié séparé nécessaire, le risque est déjà couvert par le fait que Flyway s'exécute réellement contre Postgres) | Intégration Testcontainers |

Aucun test unitaire pur (JUnit/Mockito sans contexte Spring) n'est prévu pour ce ticket : la valeur du test réside dans la vérification contre un vrai moteur PostgreSQL (JSONB, contrainte CHECK, génération UUID), ce qu'un mock ne couvrirait pas.

## Écarts identifiés

- Le design place `Region`/`Poste` dans un nouveau package `sn.samapiece.referentiel`, absent de la liste des 6 modules du §11.3 de `PROJET-SAMAPIECE.md`. C'est un écart assumé et justifié (donnée partagée entre `iam` et `enregistrement`), déjà signalé comme tel par l'architecte — aucune action supplémentaire requise du spec-writer, mais à garder visible pour la revue finale.
- Le §11.4 du document produit mentionne PostGIS pour les coordonnées géographiques ; ce ticket utilise de simples colonnes `latitude`/`longitude DOUBLE PRECISION`. Le critère d'acceptation du ticket #5 ne demande que des "coordonnées géo" pour affichage carte, sans requête de proximité — le choix du design couvre le critère tel que formulé. Écart à revalider explicitement si un futur ticket demande une recherche par proximité (migration additionnelle alors nécessaire).
- Le critère d'acceptation mentionne des "horaires JSON" sans préciser de structure. La structure proposée (objet par jour, `ouvert`/`debut`/`fin`) est une convention introduite par cette spec, non actée ailleurs dans le document produit — à valider en revue de code, mais ne bloque pas l'implémentation (le champ est stocké/restitué tel quel, sans validation de schéma dans ce ticket).
- Le SQL fixe `type` en minuscules (`police`, `gendarmerie`) via `CHECK`, alors que le ticket #5 les nomme "police/gendarmerie" sans casse précisée. La spec tranche : SQL en minuscules, enum Java en majuscules (`POLICE`/`GENDARMERIE`), converties via un `AttributeConverter` explicite. À implémenter par le codeur exactement selon cette convention pour éviter une divergence entre la contrainte CHECK et le mapping JPA.
