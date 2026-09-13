# Spec - Ticket 6 : Modèle Agent + migration Flyway

## Résumé

Ajouter la table `agent` (migration `V2`), l'entité JPA `Agent`, l'enum `Role` (avec son `AttributeConverter`) et un `AgentRepository` minimal dans le package `sn.samapiece.iam`, avec un test d'intégration Testcontainers couvrant le mapping du rôle et la contrainte d'unicité sur `matricule`.

## Tâches

- [ ] **Migration SQL** — `backend/src/main/resources/db/migration/V2__create_agent.sql` : créer la table `agent` selon le schéma exact du contrat technique ci-dessous (FK vers `poste(id)`, contrainte `UNIQUE` sur `matricule`, contrainte `CHECK` sur `role` avec les 5 valeurs en minuscules et correctement quotées en littéraux SQL, index sur `poste_id`). Confirmer au préalable qu'aucune autre migration `V2__*.sql` n'a été ajoutée entretemps dans `backend/src/main/resources/db/migration/` (seule `V1__create_region_poste.sql` doit exister avant cette tâche).
- [ ] **Enum `Role`** — `backend/src/main/java/sn/samapiece/iam/Role.java` : enum `AGENT, CHEF_POSTE, ADMIN_REGIONAL, ADMIN_NATIONAL, AUDITEUR`, implémente `GrantedAuthority`, `getAuthority()` retourne `"ROLE_" + name()`.
- [ ] **Converter `RoleConverter`** — `backend/src/main/java/sn/samapiece/iam/RoleConverter.java` : `AttributeConverter<Role, String>` annoté `@Converter`, même patron que `TypePosteConverter` (sérialise en minuscules via `name().toLowerCase()`, désérialise via `Role.valueOf(dbData.toUpperCase())`).
- [ ] **Entité `Agent`** — `backend/src/main/java/sn/samapiece/iam/Agent.java` : entité immuable (pas de setters), `@Id @GeneratedValue(strategy = GenerationType.UUID)`, `@ManyToOne(fetch = FetchType.LAZY, optional = false)` vers `Poste` (import `sn.samapiece.referentiel.Poste`), champs `matricule`, `nom`, `role` (via `RoleConverter`), `hashMotDePasse`, `actif`, `derniereConnexion`, `creeLe`/`majLe` en lecture seule (`insertable = false` pour `majLe`, `insertable = false, updatable = false` pour `creeLe`, comme sur `Poste`). Constructeur protégé sans argument pour Hibernate + constructeur métier public prenant `(Poste poste, String matricule, String nom, Role role, String hashMotDePasse)`, qui initialise `actif = true` et laisse `derniereConnexion = null`. `equals`/`hashCode` basés sur `id`, identiques au patron `Region`/`Poste`.
- [ ] **Repository `AgentRepository`** — `backend/src/main/java/sn/samapiece/iam/AgentRepository.java` : `interface AgentRepository extends JpaRepository<Agent, UUID> {}`, aucune méthode de recherche supplémentaire.
- [ ] **Test d'intégration** — `backend/src/test/java/sn/samapiece/iam/AgentIntegrationTest.java` : Testcontainers `postgres:16-alpine` avec `@ServiceConnection`, cf. plan de tests ci-dessous pour le contenu exact.

## Contrat technique

### Schéma SQL (`V2__create_agent.sql`)

```sql
CREATE TABLE agent (
    id                  UUID PRIMARY KEY,
    poste_id            UUID NOT NULL REFERENCES poste(id),
    matricule           VARCHAR(50) NOT NULL UNIQUE,
    nom                 VARCHAR(255) NOT NULL,
    role                VARCHAR(20) NOT NULL CHECK (role IN
                             ('agent', 'chef_poste', 'admin_regional', 'admin_national', 'auditeur')),
    hash_mot_de_passe   VARCHAR(255) NOT NULL,
    actif               BOOLEAN NOT NULL DEFAULT true,
    derniere_connexion  TIMESTAMPTZ,
    cree_le             TIMESTAMPTZ NOT NULL DEFAULT now(),
    maj_le              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_poste_id ON agent(poste_id);
```

Correction volontaire par rapport au rendu du `design.md` : les 5 valeurs de la liste `CHECK` sont ici des littéraux chaîne Postgres, donc **entre guillemets simples** (`'agent'`, `'chef_poste'`, etc.) — le design signalait que ses guillemets avaient été omis par une contrainte de son outil de rédaction ; sans eux, `role IN (agent, chef_poste, ...)` serait interprété comme des identifiants de colonnes inexistantes et ferait échouer la migration à l'exécution.

`id` sans valeur par défaut SQL (généré côté application par Hibernate). `matricule` porte `UNIQUE`, ce qui couvre directement le critère d'acceptation correspondant. `derniere_connexion` est le seul champ nullable métier.

### `Role`

```java
package sn.samapiece.iam;

import org.springframework.security.core.GrantedAuthority;

public enum Role implements GrantedAuthority {
    AGENT,
    CHEF_POSTE,
    ADMIN_REGIONAL,
    ADMIN_NATIONAL,
    AUDITEUR;

    @Override
    public String getAuthority() {
        return "ROLE_" + name();
    }
}
```

Contrainte impérative : les 5 constantes ci-dessus doivent rester synchronisées, terme à terme (une fois passées en minuscules avec `_`), avec la liste du `CHECK` SQL. Toute modification de l'un impose la mise à jour de l'autre dans le même changement.

### `RoleConverter`

```java
package sn.samapiece.iam;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class RoleConverter implements AttributeConverter<Role, String> {

    @Override
    public String convertToDatabaseColumn(Role attribute) {
        return attribute == null ? null : attribute.name().toLowerCase();
    }

    @Override
    public Role convertToEntityAttribute(String dbData) {
        return dbData == null ? null : Role.valueOf(dbData.toUpperCase());
    }
}
```

### `Agent`

Table : `agent`. Colonnes -> propriétés :

| Colonne | Type SQL | Nullable | Propriété Java | Type Java |
|---|---|---|---|---|
| `id` | UUID PK | non | `id` | `UUID` |
| `poste_id` | UUID FK | non | `poste` | `Poste` (`@ManyToOne`, `FetchType.LAZY`) |
| `matricule` | VARCHAR(50) UNIQUE | non | `matricule` | `String` |
| `nom` | VARCHAR(255) | non | `nom` | `String` |
| `role` | VARCHAR(20) + CHECK | non | `role` | `Role` (via `RoleConverter`) |
| `hash_mot_de_passe` | VARCHAR(255) | non | `hashMotDePasse` | `String` |
| `actif` | BOOLEAN | non | `actif` | `boolean` |
| `derniere_connexion` | TIMESTAMPTZ | oui | `derniereConnexion` | `OffsetDateTime` |
| `cree_le` | TIMESTAMPTZ | non (défaut SQL) | `creeLe` | `OffsetDateTime`, `insertable = false, updatable = false` |
| `maj_le` | TIMESTAMPTZ | non (défaut SQL) | `majLe` | `OffsetDateTime`, `insertable = false` |

Constructeur métier public unique : `Agent(Poste poste, String matricule, String nom, Role role, String hashMotDePasse)`. Il fixe `actif = true` et laisse `derniereConnexion` à `null`. Constructeur protégé sans argument pour Hibernate. Pas de setters — aucune mutation possible après création dans ce ticket (rappel garde-fou : `actif` et `derniereConnexion` resteront figés tant que les tickets 7/8 n'auront pas ajouté de méthode dédiée, ce n'est pas à trancher ici).

Getters : `getId()`, `getPoste()`, `getMatricule()`, `getNom()`, `getRole()`, `getHashMotDePasse()`, `isActif()`, `getDerniereConnexion()`, `getCreeLe()`, `getMajLe()`. **Garde-fou explicite** : aucun DTO ni sérialisation JSON n'est créé dans ce ticket ; si un futur ticket expose `Agent` via un contrôleur, `getHashMotDePasse()` ne doit jamais transiter dans une réponse HTTP.

`equals`/`hashCode` sur `id` uniquement, identique à `Region`/`Poste`.

### `AgentRepository`

```java
package sn.samapiece.iam;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRepository extends JpaRepository<Agent, UUID> {
}
```

Aucune méthode de recherche par matricule dans ce ticket (viendra avec le ticket 7).

## Plan de tests

Fichier unique : `backend/src/test/java/sn/samapiece/iam/AgentIntegrationTest.java`, structure `@SpringBootTest` + `@Testcontainers` + `@Container @ServiceConnection PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")`, identique à `PosteIntegrationTest`. Injecter `AgentRepository`, `PosteRepository`, `RegionRepository` et `JdbcTemplate`. `@BeforeEach` : nettoyer dans l'ordre `agentRepository.deleteAll()` puis `posteRepository.deleteAll()` puis `regionRepository.deleteAll()` (ordre imposé par les FK). Ne pas isoler `V2` : laisser Flyway rejouer `V1` puis `V2` dans le contexte de test complet.

| Critère d'acceptation du ticket | Test | Vérification |
|---|---|---|
| Migration Flyway créant la table `agent` avec les bonnes colonnes | `persisterEtRelireAgent_shouldMapperTousLesChamps` | Le contexte Spring démarre (Flyway applique `V1` puis `V2` sans erreur) ; l'insertion réussit avec toutes les colonnes attendues. |
| Entité JPA `Agent` correcte | `persisterEtRelireAgent_shouldMapperTousLesChamps` | Créer une `Region`, un `Poste`, puis un `Agent` via `agentRepository.save(new Agent(poste, "PN-2024-00123", "Diop Awa", Role.CHEF_POSTE, "$2a$10$hashopaque"))`. Relire via `agentRepository.findAll()` et vérifier : `id` non nul, `matricule`, `nom`, `hashMotDePasse` égaux aux valeurs fournies, `isActif()` vaut `true`, `getDerniereConnexion()` est `null`, `getPoste().getId()` égale l'id du poste créé, `creeLe`/`majLe` non nuls. |
| Enum `Role` avec ses 5 valeurs, mapping correct du rôle | `persisterEtRelireAgent_shouldMapperTousLesChamps` (assertion `assertThat(relu.getRole()).isEqualTo(Role.CHEF_POSTE)`) | Le round-trip via `RoleConverter` prouve la sérialisation/désérialisation ; pas de test unitaire séparé pour `RoleConverter`, cohérent avec l'absence de test dédié pour `TypePosteConverter`. |
| Contrainte d'unicité sur le matricule | `insertAgentAvecMatriculeDuplique_shouldViolerContrainteUnique` | Créer un premier `Agent` avec `matricule = "PN-2024-00001"` et `agentRepository.saveAndFlush(...)`. Construire un second `Agent` (poste identique ou différent, peu importe) avec le même `matricule` et `agentRepository.saveAndFlush(...)`. `assertThatThrownBy(...).isInstanceOf(DataIntegrityViolationException.class)`. |
| Synchronisation enum `Role` / contrainte `CHECK` (garde-fou du design, pas un critère du ticket mais un risque explicitement signalé) | `insertAgentAvecRoleInvalide_shouldViolerContrainteCheck` | Via `jdbcTemplate.update(...)`, tenter un `INSERT INTO agent (...) VALUES (..., 'role_inexistant', ...)` en fournissant un `poste_id` valide ; `assertThatThrownBy(...).isInstanceOf(DataIntegrityViolationException.class)`, même patron que `insertPosteAvecTypeInvalide_shouldFail` dans `PosteIntegrationTest`. |

Aucun test manuel nécessaire : ce ticket ne produit aucun endpoint REST ni écran, tout est vérifiable par test automatisé.

## Écarts identifiés

- **Format du matricule non spécifié** : ni le ticket ni `design.md` ne définissent un format strict (police et gendarmerie pourraient avoir des formats différents). `VARCHAR(50)` est retenu comme borne large sans validation de format dans ce ticket ; à trancher explicitement avant le ticket 7 si un format canonique doit être imposé (ex. regex applicative), car aucune validation de ce type n'est prévue ici.
- **Mutation de `actif`/`derniere_connexion`** : confirmé cohérent avec le ticket (aucun critère d'acceptation ne demande de CRUD ou d'activation/désactivation), mais l'absence totale de setter sur `Agent` doit être connue du codeur du ticket 7, qui devra ajouter une méthode dédiée (pas un setter générique) pour mettre à jour `derniereConnexion` au login.
- Aucun autre écart entre `design.md` et les critères d'acceptation du ticket 6 : les 4 critères (migration, entité + enum, contrainte d'unicité, test d'intégration) sont couverts intégralement par les tâches et le plan de tests ci-dessus.
