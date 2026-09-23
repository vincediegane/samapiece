# Review — Ticket #58 : Bootstrap du premier compte administrateur

APPROVE

## Résumé

Diff (`git diff main...HEAD`) relu intégralement, section par section, contre le contrat technique
de `spec.md` (§1 à §8). Chaque fichier livré (migration, `Agent`, `MotDePasseTemporaireGenerator`,
`AdminBootstrapProperties`/`AdminBootstrapRunner`, `JwtService`/`JwtAuthenticationFilter`/
`AuthService`, `ForcerChangementMotDePasseFilter`/exception handler, endpoint
`PUT /moi/mot-de-passe` et son service/handler, câblage `SecurityConfig`, `backend/README.md`,
tests) correspond **exactement** au contrat technique : mêmes noms de classes/méthodes, mêmes
signatures, même SQL de migration, même YAML, même liste blanche de filtre, même logique de runner,
mêmes codes HTTP. Aucun écart silencieux constaté par rapport à la spec ; les deux écarts
volontaires documentés dans `spec.md` (§Écarts identifiés — type `String` de `posteId`, méthode
unique `changerMotDePasse(String)`, statut `400` pour mot de passe actuel invalide) sont bien
implémentés tels que tranchés.

## Critères d'acceptation

| # | Critère | Statut |
|---|---|---|
| AC1 | Mécanisme documenté/reproductible crée le premier admin au démarrage d'une base neuve | Couvert (code : `AdminBootstrapRunner` ; test : `AdminBootstrapRunnerIntegrationTest.run_avecBaseVideEtBootstrapActive_shouldCreerAdminNational` + garde `run_avecPosteIdInconnu_shouldLeverIllegalStateException`) |
| AC2 | Mot de passe initial temporaire, renouvellement imposé à la 1re connexion | Couvert (code : flag `doitChangerMotDePasse`, claim JWT, `ForcerChangementMotDePasseFilter`, endpoint `PUT /moi/mot-de-passe` ; tests : `AgentSelfIntegrationTest.premiereConnexionAdminBootstrap_avantChangementMotDePasse_shouldRetourner403SurRoutesProtegees`, `moi_avantChangementMotDePasse_shouldRetourner200`, `changerMotDePasse_avecMotDePasseActuelValide_shouldRetourner200EtLeverEnforcement`, `changerMotDePasse_avecMotDePasseActuelInvalide_shouldRetourner400`) |
| AC3 | Ne s'active pas silencieusement en production si un admin existe déjà | Couvert (code : garde `agentRepository.count() != 0` + garde `!properties.isEnabled()`, no-op dans les deux cas ; tests : `run_avecAgentExistant_shouldEtreNoOp`, `run_avecBootstrapDesactive_shouldEtreNoOp`) |
| AC4 | Documenté dans `backend/README.md` | Couvert (nouvelle section "Bootstrap du premier compte administrateur" : prérequis Région/Poste avec SQL d'exemple, 4 variables d'environnement, procédure complète, rappel d'idempotence, avertissement sécurité sur le log en clair — contenu conforme au Contrat technique §8) |

Chaque test listé échouerait bien si le code correspondant était retiré (assertions sur
`agentRepository.count()`, sur `role`/`doitChangerMotDePasse` persistés, sur les codes HTTP
403/200/400 et le `code` d'erreur JSON) — ce ne sont pas des tests de façade.

## Points de vigilance spécifiques vérifiés

- **Non-régression Agent/AgentAdminService.creer()** : le constructeur 5-arg existant est
  inchangé dans son corps, initialise toujours doitChangerMotDePasse = false en derniere ligne ;
  le nouveau constructeur 6-arg delegue dessus. Tous les appels existants au constructeur 5-arg dans
  le reste du code (grep exhaustif sur "new Agent(" -- une trentaine d'occurrences dans les tests
  existants) restent syntaxiquement et semantiquement inchanges. AgentAdminService.creer() ne
  passe toujours que par le constructeur 5-arg, donc un agent cree par un admin garde
  doitChangerMotDePasse=false par defaut -- coherent avec AgentAdminIntegrationTest non modifie.
- **Signature genererAccessToken(...)** : grep exhaustif sur genererAccessToken -- tous les
  appelants (AuthService.login(), AuthService.refresh(), AgentSelfService.changerMotDePasse(),
  les deux occurrences de JwtServiceTest) utilisent bien la nouvelle signature a 4 arguments.
  Aucun appelant orphelin trouve dans le reste de l'arbre (pas de code mort ni de profil non
  compile par defaut faisant reference a l'ancienne signature) -- coherent avec la compilation
  reussie.
- **Liste blanche ForcerChangementMotDePasseFilter** : correspond exactement a celle du Contrat
  technique paragraphe 6 (/actuator/* toute methode, PUT /api/v1/agents/moi/mot-de-passe,
  GET /api/v1/agents/moi, POST /api/v1/auth/refresh). Logique de blocage identique au contrat
  (authentication.getDetails() instanceof Boolean actif && actif -> delegation a
  handlerExceptionResolver). Cablage dans SecurityConfig : addFilterAfter(...,
  JwtAuthenticationFilter.class), sans modification des regles authorizeHttpRequests, conforme a
  la Tache 8.
- **AdminBootstrapProperties.posteId en String** : conforme a l'ecart identifie 1 de la spec ;
  le parsing UUID est bien fait dans le runner, uniquement quand enabled=true, avec
  IllegalStateException explicite nommant la variable fautive en cas d'echec (poste
  introuvable, UUID invalide, matricule/nom manquant).
- **Mot de passe temporaire en clair dans les logs** : logge en WARN (pas INFO), comportement
  volontaire documente dans le design/spec et repris dans l'avertissement securite du README. Ce
  n'est pas une violation du paragraphe 10 de PROJET-SAMAPIECE.md, qui porte sur les donnees
  citoyennes (numero de document, contact) et non sur les identifiants de bootstrap d'un compte
  technique a usage unique -- acceptable en l'etat, avec l'avertissement explicite deja en place.
- **Tests d'integration relus sans execution** (AdminBootstrapRunnerIntegrationTest.java,
  AgentSelfIntegrationTest.java complete) : couvrent bien les 8 scenarios attendus par le plan de
  tests (AC1 base vide, AC1 poste inconnu, AC3 agent existant, AC3 desactive, AC2 403 hors liste
  blanche, AC2 GET /moi whiteliste, AC2 changement reussi + levee d'enforcement avec le nouveau
  token, AC2 mauvais mot de passe). Conventions respectees : getContentAsString(StandardCharsets
  .UTF_8) utilise pour la reponse contenant potentiellement de l'accentue,
  @BeforeEach nettoie dans l'ordre agent -> poste -> region (respect des FK, coherent avec les
  tests existants du fichier). AdminBootstrapRunnerIntegrationTest n'autowire pas le bean
  AdminBootstrapRunner de l'application et construit ses propres instances/Properties par
  scenario, comme demande, pour eviter le probleme d'ordonnancement CommandLineRunner.

## Findings

Aucun finding bloquant. Aucune remarque de style a signaler (hors perimetre de cette review).

## Build/tests

Execute dans cet environnement (Windows, Testcontainers indisponible -- limitation
environnementale preexistante, deja confirmee par l'orchestrateur sur AgentAdminIntegrationTest
non modifie par cette branche) :

- `mvn -q -pl backend -am compile` -> succes (aucune sortie d'erreur).
- `mvn -q -pl backend -am test-compile` -> succes (aucune sortie d'erreur).
- `mvn -pl backend -am test -Dtest=AgentTest,JwtServiceTest` -> succes, 13/13 tests passes (9
  AgentTest, dont les 3 nouveaux tests sur le constructeur 6-arg et changerMotDePasse(...) ; 4
  JwtServiceTest, dont le nouveau test sur le claim doitChangerMotDePasse).
- Tests unitaires purs additionnels touchant Agent/le domaine, executes en sanity-check de
  non-regression (aucun n'est modifie par cette branche, tous utilisent le constructeur 5-arg
  inchange) : `mvn -pl backend -am test -Dtest=PerimetreRegionalTest,PerimetrePosteTest,PhotoServiceTest,PieceTest,PieceServiceTest`
  -> succes, 67/67 tests passes.

Non execute dans cet environnement (Docker/Testcontainers inatteignable via named pipe sur cette
machine Windows, confirme par l'orchestrateur comme limitation preexistante et non liee a ce
changement) : AdminBootstrapRunnerIntegrationTest, AgentSelfIntegrationTest (complete),
AgentAdminIntegrationTest (non-regression). Ces trois suites doivent etre confirmees vertes en CI
(Linux, Docker disponible) avant merge -- ce point ne bloque pas l'approbation puisqu'il s'agit
d'une limitation d'environnement local et que la relecture manuelle du code de test (voir
ci-dessus) confirme leur couverture et leur conformite aux conventions du projet.

## Verdict final

APPROVE, sous reserve de confirmation en CI (Linux/Docker) que
AdminBootstrapRunnerIntegrationTest, AgentSelfIntegrationTest et AgentAdminIntegrationTest
passent effectivement -- condition non verifiable localement, non imputable au code.
