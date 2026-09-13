# Review - Ticket 6 : Modèle Agent + migration Flyway

APPROVE

## Critères d'acceptation

| Critère | Statut | Preuve |
|---|---|---|
| Migration Flyway créant la table `agent` (matricule, nom, rôle, hash de mot de passe, actif, dernière connexion, poste_id) | Couvert | `V2__create_agent.sql` crée exactement ces colonnes + `id`, `cree_le`, `maj_le`, FK vers `poste(id)`, index sur `poste_id`. Testé par `persisterEtRelireAgent_shouldMapperTousLesChamps` (le démarrage du contexte Spring implique que Flyway rejoue V1 puis V2 sans erreur). |
| Entité JPA `Agent` + enum `Role` (AGENT, CHEF_POSTE, ADMIN_REGIONAL, ADMIN_NATIONAL, AUDITEUR) | Couvert | `Agent.java` mappe toutes les colonnes du contrat technique, `Role.java` contient exactement les 5 constantes attendues, implémente `GrantedAuthority`. Round-trip testé (`assertThat(relu.getRole()).isEqualTo(Role.CHEF_POSTE)`). |
| Contrainte d'unicité sur le matricule | Couvert | `UNIQUE` sur `matricule` en SQL + `unique = true` en JPA. Testé par `insertAgentAvecMatriculeDuplique_shouldViolerContrainteUnique` qui échouerait (pas d'exception levée) si la contrainte était retirée. |
| Test d'intégration vérifiant la contrainte d'unicité et le mapping du rôle | Couvert | `AgentIntegrationTest` contient les 3 tests attendus par le plan de tests du spec (mapping complet, unicité, CHECK sur rôle). Chaque test échouerait si le comportement correspondant était retiré (assertions positives sur les valeurs relues, `assertThatThrownBy` sur les violations de contrainte). |

Aucun écart avec `spec.md` : les 6 tâches (migration SQL, `Role`, `RoleConverter`, `Agent`, `AgentRepository`, test d'intégration) sont réalisées, avec un contenu qui correspond quasi littéralement au contrat technique du spec (mêmes noms de colonnes, mêmes signatures, même plan de tests). `git diff main --name-status` ne montre aucune modification de fichier existant, uniquement des ajouts — périmètre strictement respecté.

## Relecture de code ciblée

- **Littéraux SQL du CHECK** (`V2__create_agent.sql:6-7`) : `role IN ('agent', 'chef_poste', 'admin_regional', 'admin_national', 'auditeur')` — bien des littéraux chaîne entre guillemets simples, pas des identifiants nus. Le bug signalé par le design (guillemets omis) a été correctement corrigé par le spec-writer et repris tel quel par le codeur.
- **Synchronisation enum `Role` / CHECK SQL** : vérification terme à terme —
  `AGENT`→`agent`, `CHEF_POSTE`→`chef_poste`, `ADMIN_REGIONAL`→`admin_regional`, `ADMIN_NATIONAL`→`admin_national`, `AUDITEUR`→`auditeur`. Correspondance exacte et complète dans les deux sens (5 valeurs de chaque côté, aucune valeur orpheline).
- **`RoleConverter`** : `convertToDatabaseColumn` fait `name().toLowerCase()`, `convertToEntityAttribute` fait `Role.valueOf(dbData.toUpperCase())` — round-trip cohérent, aucun piège de casse (les valeurs stockées sont toujours en minuscules, jamais mélangées). Identique au patron `TypePosteConverter` déjà en place pour `poste.type`.
- **Test `insertAgentAvecRoleInvalide_shouldViolerContrainteCheck`** (`AgentIntegrationTest.java:105-110`) : l'INSERT brut fournit des valeurs valides pour toutes les colonnes NOT NULL sans défaut SQL (`id`, `poste_id`, `matricule`, `nom`, `hash_mot_de_passe`) ; `actif`, `cree_le`, `maj_le` ont un `DEFAULT` SQL et `derniere_connexion` est nullable. Seul `role` est invalide (`'role_inexistant'`). Le test échouerait bien pour la bonne raison (violation du CHECK, pas une autre contrainte NOT NULL).
- **Exposition de `hashMotDePasse`** : aucun DTO ni contrôleur n'a été ajouté dans ce ticket (`grep Agent` sur `backend/src/main` ne remonte que `Agent.java` et `AgentRepository.java`). Pas de risque de sérialisation JSON du hash pour l'instant, conforme au garde-fou explicite du spec.
- **Cohérence de style avec `Region`/`Poste`/`TypePoste`/`TypePosteConverter`** : `Agent` suit le même patron exact que `Poste` (constructeur protégé sans argument, `@GeneratedValue(strategy = GenerationType.UUID)`, `cree_le`/`maj_le` en lecture seule via `insertable`/`updatable`, `equals`/`hashCode` sur `id` uniquement avec le même idiome `instanceof ... other` et `id != null && id.equals(other.id)`). Aucune divergence de style repérée.
- **Sécurité des données sensibles (§10 `PROJET-SAMAPIECE.md`)** : §10 vise le numéro de document et le contact citoyen (hors périmètre de ce ticket, qui ne touche pas aux entités citoyen/document). Le mot de passe agent est stocké nommé `hash_mot_de_passe`/`hashMotDePasse`, cohérent avec un stockage de hash (pas de mot de passe en clair) — bonne pratique standard indépendamment de §10. Aucune donnée personnelle citoyenne n'est introduite par ce ticket.
- **Migrations Flyway** : seule `V2__create_agent.sql` a été ajoutée (`V1__create_region_poste.sql` reste la seule migration précédente), pas de collision de version. Migration versionnée classique, pas de problème d'idempotence à ce niveau (Flyway gère le "une seule fois" via son historique de checksums).
- **Endpoints/RBAC** : aucun contrôleur REST n'est introduit par ce ticket, donc pas d'annotation `@PreAuthorize` à vérifier ici — cohérent avec le scope du ticket (le repository seul ne nécessite pas de sécurisation HTTP).

Aucun bug fonctionnel, aucune incohérence de type/nullabilité, aucun problème de concurrence identifié.

## Build/tests

- `mvn -q -pl backend -am compile` → succès (aucune erreur).
- `mvn -q -pl backend -am test-compile` → succès (le test d'intégration compile, y compris les imports Testcontainers/JdbcTemplate).
- `mvn -q -pl backend -am test -Dtest=AgentIntegrationTest` → **échec d'environnement, pas de code** : `ContainerFetchException: Could not find a valid Docker environment`. Docker Desktop 4.54.0 est bien installé et fonctionnel pour les commandes CLI standard (`docker ps`, `docker pull hello-world` réussissent), mais le client `docker-java` utilisé par Testcontainers reçoit une réponse `BadRequestException (Status 400)` avec un JSON `/info` vide en interrogeant le pipe nommé Windows (`npipe:////./pipe/docker_engine` et `npipe:////./pipe/dockerDesktopLinuxEngine` testés explicitement, même résultat). C'est exactement la même incompatibilité Docker Desktop/Windows que celle déjà rencontrée et documentée sur le ticket #5 — confirmée reproduite indépendamment dans cet environnement de review, avec CLI Docker fonctionnelle mais négociation de version API échouant côté bibliothèque `docker-java`.
- En conséquence, une relecture de code renforcée a été menée (section ci-dessus) portant spécifiquement sur les points à risque signalés (quoting SQL, synchronisation enum/CHECK, converter, NOT NULL du test CHECK, non-exposition du hash, cohérence de style). Aucun défaut trouvé.
- Recommandation pour la suite du pipeline : faire tourner `AgentIntegrationTest` en CI (Linux, où Testcontainers fonctionne nativement) avant merge définitif, comme filet de sécurité — mais rien dans la relecture manuelle ne justifie un blocage `CHANGES_REQUESTED` sur ce ticket.
