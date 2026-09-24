# Review — #60 Refus d'accès par rôle renvoie 401 au lieu de 403

APPROVE

## Résumé de l'évaluation

Le pipeline a suivi le processus attendu à la lettre : Tâche 0 (reproduction manuelle réelle,
hors MockMvc) exécutée après le premier correctif (61cb49d), a effectivement invalidé
l'hypothèse du design/spec ("le bug ne se reproduit plus, il ne s'agit que d'un durcissement"),
et le codeur a creusé jusqu'à une vraie cause racine différente (6e20cef), documentée et
testée avec l'outillage qui convient (serveur réel, pas MockMvc). C'est exactement le
comportement attendu d'un bon codeur face à une spec invalidée par les faits, et le raisonnement
technique tient à la vérification du code.

## Critères d'acceptation

| # | Critère | Statut | Justification |
|---|---|---|---|
| AC1 | Jeton valide, rôle insuffisant -> 403 | Couvert | AuthIntegrationTest.routeProtegee_avecJwtValideMaisRoleInsuffisant_shouldRetourner403 (MockMvc) + SecurityConfigIntegrationTest.routeProtegee_avecJwtValideMaisRoleInsuffisant_shouldRetourner403SurServeurReel (serveur reel, TestRestTemplate, RANDOM_PORT) - ce dernier est le seul qui aurait detecte le vrai bug et sa regression, cf. analyse ci-dessous. |
| AC2 | Jeton absent/invalide/expire -> 401 (non-regression) | Couvert | Absent : routeProtegee_sansJwt_shouldRetourner401 (existant) + SecurityConfigIntegrationTest equivalent (nouveau). Malforme : routeProtegee_avecJwtMalforme_shouldRetourner401 (existant) + equivalent serveur reel (nouveau). Expire : routeProtegee_avecAccessTokenExpire_shouldRetourner401 (nouveau, MockMvc, comble un vrai trou - seul le refresh token expire etait teste avant). |
| AC3 | Cause racine identifiee et documentee | Couvert (reserve mineure) | Cause racine techniquement solide (verifiee dans le code), documentee dans le Javadoc de SecurityConfig (lignes 42-51) et dans le message du commit 6e20cef. Reserve : aucune PR n'est encore ouverte sur ce depot (gh pr list vide) - la section "Cause racine" structuree exigee par spec.md n'existe pas encore sous forme de description de PR. A reporter a l'ouverture de la PR (le contenu du commit suffit). Non bloquant pour ce review de branche. |
| AC4 | Au moins un test d'integration par controleur protege par role, cas role insuffisant, 403 | Couvert | Les 5 controleurs (PieceController, PhotoController, AuditController, StatistiquesPosteController, AgentAdminController) ont chacun deja un test MockMvc isForbidden() sur main (verifie par lecture, ex. AgentAdminIntegrationTest.lister_commeAgent_shouldRetourner403 ligne 441). AC4 porte sur la couverture RBAC metier, orthogonale au bug d'infrastructure de ce ticket - voir discussion ci-dessous. |

## Verifications techniques (le coeur du sujet)

### 1. Le raisonnement de la cause racine tient

- JwtAuthenticationFilter, ForcerChangementMotDePasseFilter, RecherchePubliqueCaptchaFilter,
  RecherchePubliqueRateLimitFilter etendent tous OncePerRequestFilter sans override de
  shouldNotFilterErrorDispatch(). Le defaut de cette methode dans Spring (return true, donc
  le filtre est ignore pour le dispatcher ERROR) est bien celui qui s'applique ici, confirme
  par lecture directe du code : aucun de ces filtres ne redefinit ce hook. Cela confirme
  exactement le mecanisme decrit dans la Javadoc de SecurityConfig : sur le forward /error,
  aucun filtre JWT ne s'execute, donc pas de SecurityContext reconstitue, donc anonymat, donc
  anyRequest().authenticated() declenche authenticationEntryPoint -> 401, qui ecrase le 403
  deja emis par sendError().
- Ordre des regles authorizeHttpRequests (SecurityConfig.java lignes 86-97) :
  .requestMatchers("/error").permitAll() est place en ligne 90, avant .anyRequest().authenticated()
  en ligne 97. Spring Security evalue ces regles dans l'ordre et s'arrete au premier match : l'ordre
  est donc correct, et le placement relatif aux autres permitAll() n'a pas d'importance puisqu'aucun
  d'eux ne se recoupe avec /error.
- Corollaire verifie en creusant plus loin (pas demande explicitement mais eclairant) : ce bug
  n'etait pas specifique aux 403 RBAC. Toute exception non geree (ex. 500) sur un endpoint
  necessitant une authentification aurait aussi vu son forward /error reecrit en 401 par
  l'authenticationEntryPoint, avant ce correctif. Cela renforce la legitimite du fix choisi
  (large, au niveau infra) plutot qu'un correctif localise au seul cas RBAC.
- Explique aussi, en creusant, pourquoi les tests MockMvc existants sur les 5 controleurs
  (AC4) affirmaient un 403 correct alors que la prod produisait un 401 : MockMvc ne simule pas
  le forward conteneur ERROR declenche par sendError(), donc status().isForbidden() en
  MockMvc valide seulement l'appel initial a AccessDeniedHandlerImpl, jamais le second passage
  par le forward. Ces tests etaient donc des faux-positifs de longue date vis-a-vis du
  comportement reel en serveur deploye. Ce n'est pas un defaut introduit par ce ticket, mais une
  decouverte de sa Tache 0, bien exploitee par le nouveau SecurityConfigIntegrationTest.

### 2. "/error" en permitAll() : pas de risque de securite constate

- Aucun ErrorController custom dans le code (recherche exhaustive, aucun resultat) : c'est le
  BasicErrorController par defaut de Spring Boot qui repond sur /error.
- Aucune configuration server.error.include-* dans application.yml/application-*.yml : les
  valeurs par defaut de Spring Boot 3.x s'appliquent (include-message=never,
  include-binding-errors=never, include-stacktrace=never, include-exception=false). Le corps
  JSON expose a un utilisateur anonyme se limite donc a timestamp, status, error (libelle
  generique du code HTTP), path - aucune trace de pile, aucun message d'exception, aucun detail
  interne. Le risque de fuite d'information est donc nul avec la configuration actuelle du depot.
- Ce n'est en outre pas une nouvelle surface : /error recevait deja toutes les requetes en
  erreur avant ce ticket (forward conteneur systematique des qu'une exception ou un
  sendError() survient) ; simplement il retombait sous anyRequest().authenticated() et
  produisait un 401 generique au lieu du contenu BasicErrorController. Le rendre permitAll() ne
  fait qu'autoriser ce forward interne a atteindre sa destination legitime plutot que d'etre
  lui-meme rejete.

### 3. SecurityConfigIntegrationTest teste bien ce qu'il pretend

- @SpringBootTest(webEnvironment = RANDOM_PORT) + TestRestTemplate (pas de MockMvc), confirme
  par lecture du fichier. C'est la condition necessaire et suffisante pour exercer le vrai
  dispatcher servlet et le forward ERROR.
- 3 tests : role insuffisant -> 403, sans jeton -> 401, jeton malforme -> 401. En revenant
  mentalement a l'etat d'avant 6e20cef (retrait de .requestMatchers("/error").permitAll()), le
  test sur le role insuffisant echouerait effectivement (l'endpoint renverrait 401 au lieu du
  403 attendu) : c'est exactement le scenario de non-regression recherche. Les deux tests 401
  resteraient verts avant/apres (pas de changement de comportement pour eux), ce qui est
  coherent : ce ne sont pas des tests d'ancrage du fix, mais des tests de non-regression sur AC2,
  a raison.
- Style coherent avec le reste du depot : chaque classe de test cree son propre conteneur
  Postgres (@Container/@ServiceConnection), pas de classe de base commune - c'est la convention
  deja en place ailleurs dans backend/src/test/java, pas une invention isolee.

### 4. Coherence AC4

Le point le plus subtil de cette review : AC4, pris au pied de la lettre ("assertion 403"),
etait deja satisfait avant ce ticket pour les 5 controleurs, mais ces tests MockMvc ne peuvent
pas detecter ce bug precis (voir point 1). Ce n'est pas un probleme pour AC4 tel que formule
dans le ticket original : AC4 porte sur la couverture RBAC metier ("un test par controleur, cas
role insuffisant"), qui est un axe orthogonal au bug d'infra d'AC1/AC3. Le nouveau
SecurityConfigIntegrationTest comble l'angle mort reel (un test qui exerce la vraie pile pour au
moins un endpoint representatif), ce qui est suffisant pour ancrer le contrat 401/403 au niveau
infrastructure, conformement a l'argument du design ("un seul endpoint representatif suffit, ce
n'est pas un test de logique metier par controleur"). Pas de demande de dupliquer
SecurityConfigIntegrationTest pour les 4 autres controleurs : le bug corrige est un mecanisme
global (le forward /error), pas un comportement par controleur, donc un seul test de bout en
bout suffit a detecter une regression sur ce mecanisme.

### 5. Qualite du code

- Diff minimal et cible : import ajoute a la bonne place (ordre alphabetique respecte :
  access.AccessDeniedHandlerImpl avant authentication.HttpStatusEntryPoint), Javadoc claire et
  correcte techniquement, commentaire inline au bon endroit (lignes 88-89).
  backend/src/main/java/sn/samapiece/config/SecurityConfig.java lignes 21, 42-51, 85, 88-90.
- Pas de sur-ingenierie : new AccessDeniedHandlerImpl() sans configuration additionnelle,
  conforme au contrat technique de la spec, aucun corps JSON structure ajoute (hors perimetre
  assume et documente).
- AuthIntegrationTest.java : les deux nouveaux tests utilisent exactement les imports deja
  presents (verifie), pas d'ajout d'import inutile.
- docker-compose.yml : ajout mineur et coherent (forward des 4 variables BOOTSTRAP_ADMIN_*
  vers le conteneur backend), necessaire pour que la procedure de repro manuelle (Tache 0)
  fonctionne reellement via docker-compose. Defauts preserves (enabled: false), aucun changement
  de comportement par defaut.
- Aucune donnee sensible (numero de document, contact citoyen) touchee par ce diff, hors
  perimetre du ticket, section 10 de PROJET-SAMAPIECE.md non concernee ici.

## Build/tests

- cd backend && mvn -q -B -o test-compile -> succes.
- cd backend && mvn -q -B -o test -Dtest='!*IntegrationTest,!*IT' (tests unitaires purs, sans
  @SpringBootTest/Testcontainers) -> 202 tests executes, 0 echec, 0 erreur (aucune regression
  detectee sur le reste de la suite).
- cd backend && mvn -q -B -o test -Dtest=AuthIntegrationTest,SecurityConfigIntegrationTest,AgentAdminIntegrationTest
  -> echec environnemental, pas un echec de test : testcontainers ne parvient pas a dialoguer
  avec le demon Docker Desktop dans ce bac a sable (NpipeSocketClientProviderStrategy recoit une
  reponse JSON vide/invalide alors que "docker ps"/"docker version" fonctionnent normalement en
  dehors du process Maven). Tentative de contournement via
  DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine (le contexte Docker actif d'apres
  "docker context ls"), sans succes, meme erreur. Ce meme blocage touche aussi des tests
  preexistants sur main n'ayant aucun rapport avec ce ticket (SamaPieceApplicationTests,
  PieceNumeroFicheGeneratorTest, PieceCreationLogsTest), ce qui confirme qu'il s'agit d'une
  limitation de l'environnement (isolation du socket Docker dans ce bac a sable), identique a
  celle documentee par le codeur, et non d'un defaut du code de cette branche.
- Compte tenu de (a) la compilation reussie, (b) la suite unitaire complete verte, (c) la
  verification manuelle par lecture de code du mecanisme exact de OncePerRequestFilter/dispatcher
  ERROR qui confirme le raisonnement du codeur, et (d) la validation end-to-end manuelle deja
  effectuee par le codeur (4/4 cas de repro via docker-compose reel), la validation est jugee
  suffisante pour statuer, avec la reserve que la CI (qui dispose probablement d'un acces Docker
  non sandboxe) devra faire tourner SecurityConfigIntegrationTest au moins une fois avant merge
  definitif pour lever tout doute residuel.

## Findings (non bloquants)

1. Documentation PR manquante formellement (AC3) : aucune PR n'existe encore (gh pr list vide)
   pour porter la section "Cause racine" structuree exigee par spec.md. Le contenu existe deja
   (commit 6e20cef + Javadoc SecurityConfig.java lignes 42-51) : il suffit de le reporter dans la
   description de la PR au moment de son ouverture. A faire avant de considerer le ticket clos
   cote processus, mais ne remet pas en cause la qualite du code ni ne bloque ce review de
   branche.
2. Confirmation CI recommandee : SecurityConfigIntegrationTest n'a pu etre execute ni par le
   codeur (Docker/Testcontainers indisponible cote poste de dev) ni par moi (meme classe de
   probleme dans ce bac a sable). Recommandation : verifier que ce test tourne bien et passe en
   CI avant merge, c'est le seul test qui ancre reellement le fix de 6e20cef.

## Fichiers pertinents

- backend/src/main/java/sn/samapiece/config/SecurityConfig.java (fix + Javadoc, lignes 21, 42-51, 85, 88-90)
- backend/src/test/java/sn/samapiece/config/SecurityConfigIntegrationTest.java (nouveau, test serveur reel)
- backend/src/test/java/sn/samapiece/iam/AuthIntegrationTest.java (lignes 285-315, deux nouveaux tests MockMvc)
- docker-compose.yml (forward des variables BOOTSTRAP_ADMIN_*)
- docs/bolts/60-refus-acces-role-401-vs-403/spec.md, design.md (contexte)
