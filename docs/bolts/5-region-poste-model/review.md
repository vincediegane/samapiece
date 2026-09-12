# Review — Ticket #5 : Modele Region/Poste + migration Flyway initiale

APPROVE

## Criteres d'acceptation

| Critere | Statut |
|---|---|
| Migration Flyway V1__create_region_poste.sql | Couvert |
| Entites JPA Region/Poste + repositories Spring Data | Couvert |
| Endpoint public GET /api/v1/postes | Couvert |
| Test d'integration Testcontainers | Couvert (logiquement, cf. limitation build/tests) |

## Preuves detaillees par critere

- Migration : le schema de V1__create_region_poste.sql correspond caractere pour caractere a celui impose par spec.md section Contrat technique (colonnes region/poste, contrainte CHECK type IN police/gendarmerie, JSONB NOT NULL DEFAULT vide, index idx_poste_region_id). Le test insertPosteAvecTypeInvalide_shouldFail insere directement via JdbcTemplate un type hors enum et attend une DataIntegrityViolationException : ce test echouerait si la contrainte CHECK etait absente ou mal ecrite.
- Entites/repositories : Region.java et Poste.java respectent le contrat technique (types, cle primaire generee cote Hibernate en GenerationType.UUID, pas de DEFAULT gen_random_uuid cote SQL). Le mapping enum est fait via TypePosteConverter (AttributeConverter) qui serialise en minuscule vers la colonne et deserialise en majuscule vers l'enum Java, coherent avec la contrainte CHECK en minuscule. PosteRepository redefinit findAll() avec EntityGraph(attributePaths = "region") pour eviter le N+1. Le test persisterEtRelirePoste_shouldChargerRegionAssociee persiste puis relit un Poste et verifie que la Region associee est chargee sans LazyInitializationException, et que tous les champs (nom, type, adresse, telephone, horaires, latitude, longitude) correspondent : ce test echouerait si le mapping JPA etait casse.
- Endpoint : PosteController expose GET /api/v1/postes retournant List de PosteResponse. PosteResponse.from mappe directement les champs de l'entite, y compris le sous-objet region avec id et nom, et expose type comme le nom de l'enum Java (POLICE/GENDARMERIE), conforme a l'exemple de payload de la spec. Le test getPostes_shouldReturnListeSansAuthentification verifie le statut 200, le Content-Type JSON, et le contenu du tableau via jsonPath, sans authentification : il echouerait si l'endpoint etait retire ou si la securite bloquait l'acces public.

## Securite (SecurityConfig)

- La regle exacte demandee par la spec est respectee : requestMatchers(HttpMethod.GET, "/api/v1/postes").permitAll() suivi de anyRequest().authenticated(), sans wildcard de sous-chemin type /api/v1/** ou /api/v1/postes/**.
- Point important verifie : definir un bean SecurityFilterChain personnalise desactive ManagementWebSecurityAutoConfiguration (celle-ci est conditionnee par ConditionalOnDefaultWebSecurity, qui recule des qu'un bean SecurityFilterChain existe). Le codeur a identifie ce risque de regression sur /actuator/health (commit "Corrige la securite actuator/health...") et ajoute explicitement EndpointRequest.to(HealthEndpoint.class).permitAll() dans la meme chaine de regles. C'est la bonne correction : elle est ciblee (uniquement HealthEndpoint, pas tous les endpoints actuator) et coherente avec management.endpoints.web.exposure.include=health deja en place. Le test healthEndpoint_shouldReturn200 (SamaPieceApplicationTests) confirme cette non-regression.
- Test temoin getRouteNonPubliqueSansAuthentification_shouldReturn4xx : verifie qu'une route arbitraire non listee dans permitAll() renvoie un code 4xx sans authentification, ce qui confirme que anyRequest().authenticated() s'applique bien et que le perimetre public reste strictement limite a GET /api/v1/postes et /actuator/health.
- CSRF : laisse au comportement par defaut (non desactive globalement), conforme a la consigne de la spec de ne pas le desactiver sans necessite ; sans impact ici car seule une route GET est publique.
- Commentaire Javadoc present sur la classe indiquant que cette configuration est un minimum temporaire, remplace par le futur ticket IAM (RBAC complet), conforme a la demande de la spec.

## Autres points de relecture

- Colonnes cree_le/maj_le : insertable = false sur les deux, updatable = false en plus sur cree_le. Cela correspond exactement a la note de la spec ("pas besoin de les renseigner a l'insert cote Java, laisser Hibernate les lire tels quels depuis la base") et laisse jouer le DEFAULT now() de la migration SQL. maj_le reste updatable par defaut, ce qui n'a pas d'effet observable dans ce ticket en lecture seule mais reste coherent si un futur ticket ajoute une mise a jour.
- ddl-auto : aucune valeur explicite dans application.yml ou les profils. Avec une vraie datasource PostgreSQL (dev, prod, et Testcontainers qui utilise l'image postgres reelle), le comportement par defaut de Spring Boot est ddl-auto=none : pas de risque de conflit entre Hibernate et les migrations Flyway.
- application.yml / application-dev.yml : suppression exacte du bloc spring.autoconfigure.exclude et de son commentaire TODO, rien d'autre ajoute dans application.yml, conforme a la tache de spec.md.
- Donnees sensibles (section 10 de PROJET-SAMAPIECE.md) : cette section concerne les numeros de document et les contacts citoyens, qui doivent etre haches/chiffres. Ce ticket ne traite ni l'un ni l'autre : Region/Poste est un referentiel geographique public (nom, adresse, telephone institutionnel d'un poste de police/gendarmerie, horaires, coordonnees), consultable sans authentification par construction. Aucun ecart avec la section 10 a signaler pour ce perimetre.
- Coherence avec spec.md : la liste des fichiers modifies/ajoutes par le diff (git diff main..HEAD --name-status) correspond exactement, fichier par fichier, a la liste des taches de la spec. Aucune tache manquante, aucun ecart injustifie constate.

## Findings

Aucun finding bloquant. Aucun point mineur non plus apres relecture ligne a ligne des entites, repositories, controller, DTO, configuration de securite et migration SQL.

## Build/tests

- Commande : mvn -pl backend -am verify -DskipTests
  Resultat : BUILD SUCCESS (compilation et packaging Spring Boot OK).

- Commande : mvn -pl backend -am test
  Resultat : ECHEC D'ENVIRONNEMENT, pas un echec de code. Les deux classes de test (SamaPieceApplicationTests et PosteIntegrationTest) echouent des le demarrage du contexte avec org.testcontainers.containers.ContainerFetchException: Could not find a valid Docker environment, cause par une erreur HTTP 400 du named pipe Docker Desktop (NpipeSocketClientProviderStrategy).
  Verifications faites : docker version et docker run --rm hello-world fonctionnent parfaitement en CLI sur la meme machine (Docker Desktop 4.54.0, Windows, hors WSL2). J'ai egalement teste le contournement DOCKER_HOST pointant explicitement sur l'endpoint du contexte desktop-linux actif (pipe dockerDesktopLinuxEngine au lieu du pipe docker_engine par defaut) : meme echec.
  Conclusion sur ce point : il s'agit bien de l'incompatibilite connue entre le client docker-java utilise par Testcontainers et les versions recentes de Docker Desktop hors WSL2, deja signalee par le codeur. Je l'ai reproduite independamment dans mon propre environnement de review, ce qui confirme que ce n'est pas specifique a la machine du codeur.

Consequence : je n'ai aucune preuve d'execution reelle et verte de PosteIntegrationTest ni de SamaPieceApplicationTests contre un vrai PostgreSQL. Conformement a la consigne recue, je ne bloque pas le verdict sur cette seule base puisqu'il s'agit d'une limitation d'environnement et non d'un defaut de code identifie. Je le signale neanmoins explicitement : cette review s'appuie sur une relecture de code exhaustive (schema SQL, mapping JPA/enum, EntityGraph, configuration de securite, contrat JSON), pas sur une execution reelle de la suite d'integration. Recommandation forte : faire tourner cette suite dans un environnement ou Testcontainers fonctionne reellement (CI Linux ou poste avec Docker via WSL2) avant de considerer ce ticket comme definitivement valide en pratique ; si un echec de fond (autre que Docker) apparaissait alors, il faudrait rouvrir le ticket.

## Conclusion

Le code correspond fidelement a spec.md : schema SQL identique, mapping JPA/enum coherent avec la contrainte CHECK, EntityGraph anti-N+1 sur PosteRepository.findAll(), SecurityFilterChain strictement scope sans wildcard (avec correction justifiee et ciblee pour /actuator/health), DTO et endpoint conformes au contrat JSON attendu. Le code compile et s'empaquette sans erreur. Les tests ecrits couvrent chacun des criteres d'acceptation avec des assertions qui echoueraient si le code sous-jacent etait retire ou casse (contrainte CHECK, mapping entite/relation, endpoint public, isolation de la regle de securite). La seule reserve de cette review est l'absence de preuve d'execution reelle de la suite Testcontainers, due a une limitation d'environnement Docker Desktop reproduite independamment et non a un defaut identifie dans le code.
