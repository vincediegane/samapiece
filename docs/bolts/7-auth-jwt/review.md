# Review — Ticket #7 : Authentification agent (login + JWT)

APPROVE

## Criteres d'acceptation

| Critere | Statut |
|---|---|
| `POST /api/v1/auth/login` verifie matricule + mot de passe (BCrypt), renvoie JWT signe courte duree + refresh token | Couvert -- `AuthService.login`, `AuthController`, teste par `AuthIntegrationTest.login_avecIdentifiantsValides_shouldRetournerAccessEtRefreshToken` (200, accessToken/refreshToken non vides, `expiresIn == 900`, `role`/`nom`) |
| `POST /api/v1/auth/refresh` renouvelle un access token a partir d'un refresh token valide | Couvert -- `AuthService.refresh`, teste par `refresh_avecRefreshTokenValide_shouldRetournerNouvelAccessToken` |
| Verrouillage du compte apres 5 tentatives infructueuses consecutives | Couvert -- `Agent.enregistrerEchecConnexion`/`estVerrouille`, teste unitairement (`AgentTest`) et en integration (`login_apresCinqEchecsConsecutifs_shouldRetourner423DesLaCinquiemeTentative`, `login_surCompteDejaVerrouille_shouldRetourner423MemeAvecMotDePasseCorrect`) |
| Toutes les routes non publiques rejettent une requete sans JWT valide (401) | Couvert -- `HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)` explicite dans `SecurityConfig`, teste par `routeProtegee_sansJwt_shouldRetourner401`, `routeProtegee_avecJwtMalforme_shouldRetourner401`, et regression `PosteIntegrationTest.getRouteNonPubliqueSansAuthentification_shouldReturn401` (resserre depuis `is4xxClientError()`) |
| Tests d'integration : login valide, mot de passe invalide, compte verrouille, token expire | Couvert -- les 12 scenarios du plan de tests de `spec.md` sont tous presents dans `AuthIntegrationTest` (285 lignes), avec les bonnes assertions de statut ET de corps de reponse |

## Verification diff vs spec.md

Diff isole via `git diff bolt/issue-6-agent-model..HEAD` (28 fichiers, +1713/-16). Comparaison fichier par fichier avec les blocs de code complets fournis dans `spec.md` (section Contrat technique) :

- `Agent.java` : champs, constructeur, `enregistrerConnexionReussie`/`enregistrerEchecConnexion`/`estVerrouille` -- identiques au contrat, aucun setter generique ajoute.
- `AgentRepository.findByMatricule` -- identique.
- `V3__ajoute_verrouillage_agent.sql` -- identique (pas de CHECK, defauts 0/NULL).
- `JwtProperties`, `JwtService`, `JwtAuthenticationFilter` -- identiques caractere pour caractere aux extraits de la spec, y compris l'horloge injectable (Clock) sur JwtService et le second constructeur public dedie aux tests.
- Claims JWT : access token porte `role`, refresh token ne le porte pas -- verifie dans le code (`genererRefreshToken` n'ajoute pas `CLAIM_ROLE`) et teste explicitement (`JwtServiceTest.genererRefreshToken_shouldPasContenirClaimRole`).
- `AuthenticationException`/`CompteVerrouilleException`/`AuthService`/DTOs/`AuthController`/`AuthExceptionHandler` -- identiques au contrat ; le corps d'erreur est toujours un message generique fixe (jamais `ex.getMessage()`), conforme a l'exigence de ne pas distinguer les causes d'echec.
- `SecurityConfig` -- `HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)` bien configure via `exceptionHandling(...)`, filtre JWT insere via `addFilterBefore` avant `UsernamePasswordAuthenticationFilter`, `permitAll()` correct sur login/refresh et `GET /api/v1/postes`.
- `backend/src/test/resources/application.yml` bien present avec un secret de test dedie (>= 32 caracteres) -- evite de casser les `@SpringBootTest` existants.
- `.env.example`, `docker-compose.yml`, `backend/README.md` : `JWT_SECRET` documente et propage, coherent avec le contrat (pas de valeur par defaut en `application.yml` prod, `${JWT_SECRET}` uniquement).
- `pom.xml` : `jjwt-api`/`jjwt-impl` (runtime)/`jjwt-jackson` (runtime) en 0.12.6.
- `PosteIntegrationTest` : test renomme et assertion resserree a `isUnauthorized()` conformement a l'ecart n1 de la spec.

Aucune tache de la spec n'a ete omise ou realisee differemment sans justification.

## Securite / risques cibles

- Aucun log de secret : `grep` sur tout `sn.samapiece.iam` (main) ne trouve aucun appel `log.*`/`System.out`/`printStackTrace` -- le seul texte contenant "logger" est un commentaire rappelant explicitement de ne pas logger le token. Conforme a `spec.md` et a l'exigence de non-divulgation.
- 403 vs 401 : verifie par lecture du code (`HttpStatusEntryPoint` correctement cable dans l'unique bean `securityFilterChain`) et par les tests dedies (`routeProtegee_sansJwt_shouldRetourner401`, `PosteIntegrationTest...shouldReturn401`), non executables en local faute de Docker (voir Build/tests) mais logiquement corrects.
- Claims JWT : conforme (access a `role`, refresh non), verifie par test unitaire reellement execute et vert.
- Verrouillage : logique exacte verifiee a la fois par lecture du code (`AuthService.login`, `Agent.enregistrerEchecConnexion`) et par test unitaire reellement execute (`AgentTest`, 3/3 verts) : le 5e echec verrouille et remet le compteur a 0, `enregistrerConnexionReussie` reinitialise tout, le 5e essai renvoie bien 423 dans `AuthService.login` (verification `estVerrouille()` apres l'increment, avant de lever `AuthenticationException`).
- Horloge injectable : `JwtService` a bien un second constructeur public `(JwtProperties, Clock)`, utilise par `JwtServiceTest` et `AuthIntegrationTest` pour generer un token expire sans dependre du temps systeme -- teste et vert.
- Pas de setter generique sur `Agent` : confirme par lecture complete du fichier.

### Observation mineure non bloquante (hors perimetre du ticket)

`AuthService.login` fait un cycle lire-modifier-ecrire sur `tentativesEchouees` sans verrouillage optimiste (pas de `@Version` sur `Agent`). Deux tentatives d'echec strictement concurrentes sur le meme agent peuvent produire un "lost update" (le compteur avance de 1 au lieu de 2), ce qui retarderait legerement le verrouillage dans un scenario de brute-force parallele. Ni le ticket ni spec.md/design.md n'exigent de protection contre ce cas, et l'impact reel est faible. Je ne bloque pas la review sur ce point, mais il merite d'etre note pour un futur durcissement (ex. `@Version` ou verrou pessimiste sur la ligne agent au moment de l'echec).

## Build/tests

- `mvn -q -pl backend -am test -Dtest=JwtServiceTest,AgentTest` -> BUILD SUCCESS, confirme par les rapports Surefire : `AgentTest` 3/3 (0 echec/0 erreur), `JwtServiceTest` 3/3 (0 echec/0 erreur). Ces tests unitaires purs (pas de Spring/Testcontainers) sont bien executables et verts en local, comme annonce par le codeur.
- `mvn -q -pl backend -am test -Dtest=AuthIntegrationTest` -> echec avec `ContainerFetchException: Could not find a valid Docker environment`. Confirme qu'il s'agit de la meme limitation d'environnement Docker Desktop/Windows deja documentee sur les tickets #5/#6 (pas un probleme introduit par ce ticket) : Testcontainers ne trouve aucun daemon Docker valide (`NpipeSocketClientProviderStrategy` echoue).
- `mvn -q -pl backend -am test` (suite complete) -> 10 tests executes, 6 verts (`AgentTest` + `JwtServiceTest`), 4 en erreur, tous par `ContainerFetchException` Docker (`SamaPieceApplicationTests`, `AgentIntegrationTest`, `AuthIntegrationTest`, `PosteIntegrationTest`) -- coherent avec le fait que ce sont les 4 seules classes `@SpringBootTest`/`@Testcontainers` du module. Aucune erreur de compilation, aucun echec logique (`Failures: 0` partout).
- Relecture stricte de `AuthIntegrationTest.java` en compensation de l'impossibilite d'execution Testcontainers en local : les 12 scenarios du plan de tests de `spec.md` sont tous presents avec les bons noms de methode, les bonnes assertions de statut HTTP et de corps JSON (`code`, `accessToken`/`refreshToken` non vides, `expiresIn`, relecture en base des compteurs/verrouillage). Rien a redire sur la couverture ni sur la construction des tokens expires/malformes via l'horloge injectee.

Aucun echec de build ni de test qui soit imputable au code de ce ticket. Les seuls echecs constates sont des echecs d'infrastructure Testcontainers deja connus et hors du controle du codeur.
