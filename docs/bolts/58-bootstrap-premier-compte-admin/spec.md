# Spec -- Ticket #58 : Bootstrap du premier compte administrateur

## Résumé

Livrer un `CommandLineRunner` idempotent (`AdminBootstrapRunner`, gardé par `BOOTSTRAP_ADMIN_ENABLED`
et `agentRepository.count() == 0`) qui crée le premier agent `ADMIN_NATIONAL` avec mot de passe
temporaire loggé une fois, plus un mécanisme générique de renouvellement forcé du mot de passe
(colonne `doit_changer_mot_de_passe`, claim JWT, filtre de blocage 403, endpoint
`PUT /api/v1/agents/moi/mot-de-passe`) activé uniquement pour ce compte bootstrap, documenté dans
`backend/README.md`.

## Tâches

### 1. Migration et modèle de données

- [ ] `backend/src/main/resources/db/migration/V12__ajoute_doit_changer_mot_de_passe_agent.sql`
  (nouveau) -- contenu exact (voir Contrat technique §1).
- [ ] `backend/src/main/java/sn/samapiece/iam/Agent.java` (modifié) -- champ
  `doitChangerMotDePasse`, getter `isDoitChangerMotDePasse()`, nouveau constructeur surchargé
  6 arguments, méthode `changerMotDePasse(String nouveauHashMotDePasse)` (voir Contrat technique §2).
  Le constructeur 5-arg existant et tous ses appelants restent inchangés en comportement.
- [ ] `backend/src/test/java/sn/samapiece/iam/AgentTest.java` (complété) -- tests unitaires pour le
  nouveau constructeur (`doitChangerMotDePasse` par défaut `false` via le constructeur 5-arg, `true`
  via le 6-arg) et pour `changerMotDePasse(...)` (met à jour le hash, remet le flag à `false`).

### 2. Génération de mot de passe temporaire (refactor)

- [ ] `backend/src/main/java/sn/samapiece/iam/MotDePasseTemporaireGenerator.java` (nouveau,
  `@Component`) -- méthode `String generer()`, reprend exactement `LONGUEUR_MOT_DE_PASSE = 12` et
  `ALPHABET_MOT_DE_PASSE` actuellement privés dans `AgentAdminService`.
- [ ] `backend/src/main/java/sn/samapiece/iam/AgentAdminService.java` (modifié) -- retire
  `genererMotDePasseTemporaire()`, les constantes associées et le champ `SecureRandom` ; injecte
  `MotDePasseTemporaireGenerator` au constructeur (4e paramètre) ; remplace l'appel interne par
  `motDePasseTemporaireGenerator.generer()`. Comportement de `creer(...)` inchangé (aucun test
  `AgentAdminIntegrationTest` à modifier pour cette étape).

### 3. Configuration du bootstrap

- [ ] `backend/src/main/java/sn/samapiece/iam/AdminBootstrapProperties.java` (nouveau,
  `@Component @ConfigurationProperties(prefix = "samapiece.bootstrap-admin")`) -- champs
  `enabled` (`boolean`, défaut `false`), `matricule` (`String`), `nom` (`String`), `posteId`
  (**`String`, pas `UUID`** -- voir Écarts identifiés §1). Getters/setters standard, pas
  d'annotation `@NotBlank`/`@NotNull` sur `matricule`/`nom`/`posteId` (voir Contrat technique §3).
- [ ] `backend/src/main/resources/application.yml` (modifié) -- section `samapiece.bootstrap-admin`
  (voir Contrat technique §3).

### 4. Runner de bootstrap

- [ ] `backend/src/main/java/sn/samapiece/iam/AdminBootstrapRunner.java` (nouveau, `@Component`,
  `implements CommandLineRunner`) -- voir Contrat technique §4 pour la logique exacte attendue.

### 5. Claim JWT et propagation

- [ ] `backend/src/main/java/sn/samapiece/iam/jwt/JwtService.java` (modifié) -- constante
  `CLAIM_DOIT_CHANGER_MOT_DE_PASSE`, signature étendue de `genererAccessToken(...)` avec un
  paramètre `boolean doitChangerMotDePasse` (voir Contrat technique §5).
- [ ] `backend/src/main/java/sn/samapiece/iam/AuthService.java` (modifié) -- `login()` et
  `refresh()` passent `agent.isDoitChangerMotDePasse()` au nouvel appel de
  `genererAccessToken(...)`.
- [ ] `backend/src/main/java/sn/samapiece/iam/jwt/JwtAuthenticationFilter.java` (modifié) -- lit
  `CLAIM_DOIT_CHANGER_MOT_DE_PASSE` et l'expose via `authentication.setDetails(Boolean)` (voir
  Contrat technique §5).
- [ ] `backend/src/test/java/sn/samapiece/iam/jwt/JwtServiceTest.java` (modifié) -- corrige les
  deux appels existants à `genererAccessToken(...)` pour la nouvelle signature (compilation) et
  ajoute une assertion sur `CLAIM_DOIT_CHANGER_MOT_DE_PASSE` (voir Plan de tests).

### 6. Filtre d'application du renouvellement forcé

- [ ] `backend/src/main/java/sn/samapiece/iam/MotDePasseTemporaireNonChangeException.java`
  (nouveau) -- `RuntimeException` sans paramètre, message fixe.
- [ ] `backend/src/main/java/sn/samapiece/iam/jwt/ForcerChangementMotDePasseFilter.java` (nouveau,
  `OncePerRequestFilter`) -- liste blanche exacte et logique de blocage (voir Contrat technique §6).
- [ ] `backend/src/main/java/sn/samapiece/iam/web/ForcerChangementMotDePasseExceptionHandler.java`
  (nouveau, `@RestControllerAdvice`) -- mappe `MotDePasseTemporaireNonChangeException` vers
  `403 FORBIDDEN`, code `MOT_DE_PASSE_TEMPORAIRE_NON_CHANGE`.

### 7. Endpoint de changement de mot de passe

- [ ] `backend/src/main/java/sn/samapiece/iam/MotDePasseActuelInvalideException.java` (nouveau) --
  `RuntimeException` sans paramètre, message fixe.
- [ ] `backend/src/main/java/sn/samapiece/iam/web/ChangerMotDePasseRequest.java` (nouveau record)
  -- `motDePasseActuel` (`@NotBlank String`), `nouveauMotDePasse` (`@NotBlank String`).
- [ ] `backend/src/main/java/sn/samapiece/iam/web/ChangerMotDePasseResponse.java` (nouveau record)
  -- `accessToken`, `refreshToken` (`String`), `expiresIn` (`long`).
- [ ] `backend/src/main/java/sn/samapiece/iam/AgentSelfService.java` (modifié) -- injecte
  `PasswordEncoder` et `JwtService` en plus de `AgentRepository` ; nouvelle méthode
  `changerMotDePasse(ChangerMotDePasseRequest request)` (voir Contrat technique §7).
- [ ] `backend/src/main/java/sn/samapiece/iam/web/AgentSelfExceptionHandler.java` (nouveau,
  `@RestControllerAdvice`) -- mappe `MotDePasseActuelInvalideException` vers `400 BAD_REQUEST`,
  code `MOT_DE_PASSE_ACTUEL_INVALIDE`.
- [ ] `backend/src/main/java/sn/samapiece/iam/web/AgentSelfController.java` (modifié) -- ajoute
  `PUT /moi/mot-de-passe` (voir Contrat technique §7).

### 8. Câblage sécurité

- [ ] `backend/src/main/java/sn/samapiece/config/SecurityConfig.java` (modifié) -- instancie
  `ForcerChangementMotDePasseFilter` avec le `HandlerExceptionResolver` déjà injecté dans
  `securityFilterChain(...)`, puis
  `.addFilterAfter(forcerChangementMotDePasseFilter, JwtAuthenticationFilter.class)`. Aucun
  changement des règles `authorizeHttpRequests` (le nouvel endpoint self-service est déjà couvert
  par `anyRequest().authenticated()`).

### 9. Documentation

- [ ] `backend/README.md` (modifié) -- nouvelle section "Bootstrap du premier compte
  administrateur" (voir Contrat technique §8).

### 10. Tests d'intégration/non-régression

- [ ] `backend/src/test/java/sn/samapiece/iam/AdminBootstrapRunnerIntegrationTest.java` (nouveau)
  -- voir Plan de tests, AC1/AC3.
- [ ] `backend/src/test/java/sn/samapiece/iam/AgentSelfIntegrationTest.java` (complété) -- tests
  pour `PUT /api/v1/agents/moi/mot-de-passe` et l'enforcement 403 (voir Plan de tests, AC2).
- [ ] `backend/src/test/java/sn/samapiece/iam/AgentAdminIntegrationTest.java` -- **aucune
  modification requise** ; exécuter la suite pour confirmer la non-régression (voir Plan de
  tests).

## Contrat technique

### 1. Migration `V12__ajoute_doit_changer_mot_de_passe_agent.sql`

```sql
ALTER TABLE agent
    ADD COLUMN doit_changer_mot_de_passe BOOLEAN NOT NULL DEFAULT false;
```

### 2. `Agent.java`

```java
@Column(name = "doit_changer_mot_de_passe", nullable = false)
private boolean doitChangerMotDePasse;

// constructeur existant (inchangé), initialise explicitement le flag a false en derniere ligne :
public Agent(Poste poste, String matricule, String nom, Role role, String hashMotDePasse) {
    // ... corps existant inchangé ...
    this.doitChangerMotDePasse = false;
}

// nouveau constructeur surchargé
public Agent(
        Poste poste, String matricule, String nom, Role role, String hashMotDePasse,
        boolean doitChangerMotDePasse) {
    this(poste, matricule, nom, role, hashMotDePasse);
    this.doitChangerMotDePasse = doitChangerMotDePasse;
}

public boolean isDoitChangerMotDePasse() {
    return doitChangerMotDePasse;
}

/** Met a jour le hash et leve le flag de renouvellement force. */
public void changerMotDePasse(String nouveauHashMotDePasse) {
    this.hashMotDePasse = Objects.requireNonNull(nouveauHashMotDePasse, "nouveauHashMotDePasse");
    this.doitChangerMotDePasse = false;
}
```

### 3. `AdminBootstrapProperties` / `application.yml`

Nouvelle section dans `application.yml`, sous `samapiece:` :

```yaml
samapiece:
  bootstrap-admin:
    enabled: ${BOOTSTRAP_ADMIN_ENABLED:false}
    matricule: ${BOOTSTRAP_ADMIN_MATRICULE:}
    nom: ${BOOTSTRAP_ADMIN_NOM:}
    poste-id: ${BOOTSTRAP_ADMIN_POSTE_ID:}
```

`posteId` est typé `String` côté `AdminBootstrapProperties`, **pas** `UUID` : un défaut vide
(`${BOOTSTRAP_ADMIN_POSTE_ID:}`) lié à un champ `UUID` ferait échouer la conversion Spring Boot au
démarrage de **toute** l'application (y compris quand `enabled=false`), puisque le binding
`@ConfigurationProperties` a lieu inconditionnellement à l'initialisation du contexte. Le runner
parse lui-même la chaîne en `UUID` (voir §4), uniquement quand `enabled=true`.

### 4. `AdminBootstrapRunner`

Comportement attendu de `run(String... args)`, dans cet ordre :

1. Si `!properties.isEnabled()` : logger en `INFO` et retourner sans rien faire (no-op).
2. Si `agentRepository.count() != 0` : logger en `INFO` et retourner sans rien faire (no-op --
   satisfait "ne s'active pas silencieusement en production si un admin existe déjà", quel que
   soit le rôle des agents déjà présents).
3. Sinon, résoudre `posteId` (`UUID.fromString(properties.getPosteId())`), `matricule` et `nom` --
   si `posteId`/`matricule`/`nom` est `null`/vide, ou si `posteId` n'est pas un UUID valide, lever
   `IllegalStateException` avec un message explicite nommant la variable d'environnement fautive
   (`BOOTSTRAP_ADMIN_POSTE_ID`/`BOOTSTRAP_ADMIN_MATRICULE`/`BOOTSTRAP_ADMIN_NOM`) -- fait échouer le
   démarrage de l'application (comportement volontaire, voir design §Décisions clés 3).
4. Charger le `Poste` via `posteRepository.findById(posteId)` -- absent : lever
   `IllegalStateException` nommant l'UUID recherché.
5. Générer le mot de passe via `motDePasseTemporaireGenerator.generer()`, créer
   `new Agent(poste, matricule, nom, Role.ADMIN_NATIONAL, passwordEncoder.encode(motDePasseTemporaire), true)`,
   `agentRepository.saveAndFlush(admin)`.
6. Logger en `WARN` (pas `INFO`, cf. sensibilité -- voir Risques du design) le matricule et le mot
   de passe en clair, avec un rappel explicite de le changer via
   `PUT /api/v1/agents/moi/mot-de-passe`.

Dépendances injectées au constructeur : `AgentRepository`, `PosteRepository`, `PasswordEncoder`,
`MotDePasseTemporaireGenerator`, `AdminBootstrapProperties`. Méthode `run(...)` annotée
`@Transactional`.

### 5. Claim JWT

`JwtService` :

```java
public static final String CLAIM_DOIT_CHANGER_MOT_DE_PASSE = "doitChangerMotDePasse";

public String genererAccessToken(UUID agentId, String matricule, Role role, boolean doitChangerMotDePasse) {
    // ... identique a l'existant, ajoute avant .signWith(signingKey) :
    // .claim(CLAIM_DOIT_CHANGER_MOT_DE_PASSE, doitChangerMotDePasse)
}
```

Tous les appelants existants (`AuthService.login()`, `AuthService.refresh()`,
`JwtServiceTest` -- 2 occurrences) doivent être mis à jour pour passer ce 4e argument. Dans
`AuthService`, la valeur vient de l'agent rechargé en base à cet endroit
(`agent.isDoitChangerMotDePasse()`), aucun accès DB supplémentaire.

`JwtAuthenticationFilter.doFilterInternal(...)`, dans le bloc où `TYPE_ACCESS` est confirmé :

```java
Boolean doitChangerMotDePasse = claims.get(JwtService.CLAIM_DOIT_CHANGER_MOT_DE_PASSE, Boolean.class);
UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(matricule, null, List.of(role));
authentication.setDetails(Boolean.TRUE.equals(doitChangerMotDePasse));
SecurityContextHolder.getContext().setAuthentication(authentication);
```

`authentication.getDetails()` porte donc toujours un `Boolean` non nul (jamais `null`) pour toute
requête authentifiée par ce filtre.

### 6. `ForcerChangementMotDePasseFilter`

Liste blanche exacte (requêtes laissées passer même si le claim est actif) --
`shouldNotFilter(HttpServletRequest request)` retourne `true` si et seulement si :

- `request.getRequestURI()` commence par `/actuator/` (toute méthode), ou
- méthode `PUT` et URI exactement `/api/v1/agents/moi/mot-de-passe`, ou
- méthode `GET` et URI exactement `/api/v1/agents/moi`, ou
- méthode `POST` et URI exactement `/api/v1/auth/refresh`.

Dans `doFilterInternal(...)` (routes non whitelistées) :

```java
Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
if (authentication != null && authentication.getDetails() instanceof Boolean actif && actif) {
    handlerExceptionResolver.resolveException(request, response, null, new MotDePasseTemporaireNonChangeException());
    return;
}
chain.doFilter(request, response);
```

Constructeur : `ForcerChangementMotDePasseFilter(HandlerExceptionResolver handlerExceptionResolver)`,
même pattern que `RecherchePubliqueCaptchaFilter`.

**Point de vigilance explicite pour le reviewer** (repris du design) : tout futur endpoint
authentifié légitimement accessible avant changement de mot de passe (ex. futur
`/api/v1/auth/logout`) doit être ajouté à cette liste blanche, sous peine de 403 inattendu.

### 7. Endpoint `PUT /api/v1/agents/moi/mot-de-passe`

`AgentSelfService.changerMotDePasse(ChangerMotDePasseRequest request)` :

1. Résoudre l'appelant courant comme dans `moi()` (matricule depuis
   `SecurityContextHolder`, `AccesRefuseException` si introuvable ou inactif).
2. `passwordEncoder.matches(request.motDePasseActuel(), appelant.getHashMotDePasse())` --
   `false` : lever `MotDePasseActuelInvalideException`.
3. `appelant.changerMotDePasse(passwordEncoder.encode(request.nouveauMotDePasse()))`,
   `agentRepository.save(appelant)`.
4. Ré-émettre un token frais : `jwtService.genererAccessToken(appelant.getId(), appelant.getMatricule(), appelant.getRole(), appelant.isDoitChangerMotDePasse())`
   (désormais `false`) et `jwtService.genererRefreshToken(appelant.getId(), appelant.getMatricule())`.
5. Retourner `new ChangerMotDePasseResponse(accessToken, refreshToken, jwtService.accessTokenTtlSecondes())`.

`AgentSelfController` :

```java
@PutMapping("/moi/mot-de-passe")
public ResponseEntity<ChangerMotDePasseResponse> changerMotDePasse(
        @Valid @RequestBody ChangerMotDePasseRequest request) {
    return ResponseEntity.ok(agentSelfService.changerMotDePasse(request));
}
```

Aucun `@PreAuthorize` (accessible à tout agent authentifié et actif, quel que soit son rôle --
cohérent avec `GET /moi`). Codes de statut : `200 OK` (succès), `400 BAD_REQUEST` /
`MOT_DE_PASSE_ACTUEL_INVALIDE` (mauvais mot de passe actuel), `403 FORBIDDEN` /
`ACCES_REFUSE` (agent introuvable/inactif), `401 UNAUTHORIZED` (pas de token).

### 8. `backend/README.md` -- section "Bootstrap du premier compte administrateur"

Contenu minimal attendu :

- Variables d'environnement : `BOOTSTRAP_ADMIN_ENABLED` (défaut `false`),
  `BOOTSTRAP_ADMIN_MATRICULE`, `BOOTSTRAP_ADMIN_NOM`, `BOOTSTRAP_ADMIN_POSTE_ID` (UUID).
- Prérequis explicite : aucune Région/Poste n'est créée automatiquement ; en créer au moins une
  paire manuellement en base (SQL direct) avant d'activer le bootstrap, et documenter
  concrètement la requête d'exemple.
- Procédure : positionner les 4 variables, démarrer le backend une fois, récupérer le matricule et
  le mot de passe temporaire dans les logs de démarrage (niveau `WARN`), se connecter via
  `POST /api/v1/auth/login`, puis changer immédiatement le mot de passe via
  `PUT /api/v1/agents/moi/mot-de-passe` (toute autre route protégée renvoie `403` tant que ce
  changement n'est pas fait).
- Rappel explicite : le mécanisme est un no-op si `BOOTSTRAP_ADMIN_ENABLED != true` ou si un agent
  existe déjà en base (aucun risque de doublon même si la variable reste positionnée en pipeline
  de déploiement) ; laisser `BOOTSTRAP_ADMIN_ENABLED=false` par défaut en `staging`/`prod`.
- Avertissement sécurité : le mot de passe temporaire apparaît en clair dans les logs de démarrage
  -- s'assurer qu'aucun agrégateur de logs ne le persiste indéfiniment.

## Plan de tests

| Critère d'acceptation du ticket #58 | Test(s) couvrant | Type |
|---|---|---|
| AC1 -- mécanisme documenté/reproductible crée un premier admin sur base neuve | `AdminBootstrapRunnerIntegrationTest.run_avecBaseVideEtBootstrapActive_shouldCreerAdminNational` | Intégration Testcontainers |
| AC1 (garde d'échec explicite) | `AdminBootstrapRunnerIntegrationTest.run_avecPosteIdInconnu_shouldLeverIllegalStateException` | Intégration Testcontainers |
| AC2 -- mot de passe initial temporaire, renouvellement imposé à la 1re connexion | `AdminBootstrapRunnerIntegrationTest.run_...` (vérifie `doitChangerMotDePasse=true` sur l'agent créé, mot de passe temporaire fonctionnel via login) + `AgentSelfIntegrationTest.premiereConnexionAdminBootstrap_avantChangementMotDePasse_shouldRetourner403SurRoutesProtegees` + `AgentSelfIntegrationTest.changerMotDePasse_avecMotDePasseActuelValide_shouldRetourner200EtLeverEnforcement` | Intégration Testcontainers (MockMvc) |
| AC2 (mauvais mot de passe actuel) | `AgentSelfIntegrationTest.changerMotDePasse_avecMotDePasseActuelInvalide_shouldRetourner400` | Intégration Testcontainers (MockMvc) |
| AC2 (routes whitelistées restent accessibles avant changement) | `AgentSelfIntegrationTest.moi_avantChangementMotDePasse_shouldRetourner200` | Intégration Testcontainers (MockMvc) |
| AC3 -- ne s'active pas silencieusement si un admin existe déjà | `AdminBootstrapRunnerIntegrationTest.run_avecAgentExistant_shouldEtreNoOp` | Intégration Testcontainers |
| AC3 (variante désactivée) | `AdminBootstrapRunnerIntegrationTest.run_avecBootstrapDesactive_shouldEtreNoOp` | Intégration Testcontainers |
| AC4 -- documenté dans `backend/README.md` | Revue manuelle du contenu de la section (voir Contrat technique §8) | Manuel |
| Non-régression `AgentAdminIntegrationTest` | Suite existante inchangée, exécutée telle quelle (le constructeur 5-arg et `AgentAdminService.creer()` gardent `doitChangerMotDePasse=false` par défaut, donc login immédiat après création par un admin continue de fonctionner sans 403) | Intégration Testcontainers |
| Non-régression `JwtServiceTest` | Suite existante corrigée pour la nouvelle signature (voir Tâches §5), plus nouveau test `genererAccessToken_avecDoitChangerMotDePasseTrue_shouldContenirClaim` | Unitaire JUnit |
| Non-régression claim absent par défaut sur connexion standard | `AgentAdminIntegrationTest` existant (`creer_avecDonneesValides_shouldRetourner201EtMotDePasseTemporaire` : login immédiat sans 403) sert déjà de garde-fou ; ne pas ajouter de test redondant | Intégration Testcontainers (déjà existant) |

Détails d'implémentation pour `AdminBootstrapRunnerIntegrationTest` (`@SpringBootTest @Testcontainers`,
pas de `@AutoConfigureMockMvc` -- aucun appel HTTP nécessaire) :

- Autowire `AgentRepository`, `PosteRepository`, `RegionRepository`, `PasswordEncoder`,
  `MotDePasseTemporaireGenerator`. **Ne pas** autowire le bean `AdminBootstrapRunner` de
  l'application (celui-ci tourne déjà, en no-op, au démarrage réel du contexte de test puisque
  `samapiece.bootstrap-admin.enabled` vaut `false` par défaut dans `application.yml` -- ce qui est
  cohérent et ne pollue pas l'état de test). Chaque test construit sa propre instance de
  `AdminBootstrapRunner` avec une `AdminBootstrapProperties` locale adaptée au scénario, et appelle
  `.run()` directement -- ceci évite le problème d'ordonnancement Spring Boot (le `Poste` de test
  doit exister avant l'exécution du runner, or `CommandLineRunner` s'exécute normalement avant tout
  code de test au démarrage du contexte).
- `@BeforeEach` : `agentRepository.deleteAll(); posteRepository.deleteAll(); regionRepository.deleteAll();`
  (même ordre que les tests existants, respecte les contraintes FK).
- Cas `run_avecBaseVideEtBootstrapActive_shouldCreerAdminNational` : créer une `Region`/`Poste` de
  test, construire des `AdminBootstrapProperties` avec `enabled=true`, `matricule`, `nom`,
  `posteId=poste.getId().toString()`, appeler `.run()`, puis asserter : `agentRepository.count()==1`,
  l'agent créé a `role==ADMIN_NATIONAL`, `isDoitChangerMotDePasse()==true`, et que le mot de passe
  temporaire loggé (capturé via une sortie interceptée, ou en réimplémentant la génération
  déterministe n'est pas nécessaire : suffisant de vérifier via `passwordEncoder.matches(...)`
  n'est pas possible sans connaître le mot de passe en clair -- **alternative recommandée** :
  extraire la logique de test en appelant directement une version testable, ou vérifier uniquement
  les champs persistés (`role`, `doitChangerMotDePasse`, `matricule`, `nom`, `poste`) sans chercher
  à récupérer le mot de passe en clair depuis les logs).
- Cas `run_avecAgentExistant_shouldEtreNoOp` : créer un agent quelconque au préalable (n'importe
  quel rôle), construire des `AdminBootstrapProperties` avec `enabled=true` et des valeurs valides,
  appeler `.run()`, asserter `agentRepository.count()` inchangé.
- Cas `run_avecBootstrapDesactive_shouldEtreNoOp` : base vide, `AdminBootstrapProperties` avec
  `enabled=false`, appeler `.run()`, asserter `agentRepository.count()==0`.
- Cas `run_avecPosteIdInconnu_shouldLeverIllegalStateException` : base vide, `enabled=true`,
  `posteId=UUID.randomUUID().toString()` (n'existe pas), asserter que `.run()` lève
  `IllegalStateException` et que `agentRepository.count()` reste `0`.

Détails pour les nouveaux tests `AgentSelfIntegrationTest` (mêmes conventions que les tests
existants du fichier : `creerPoste()`, `creerAgentActif(...)`, `login(...)`) :

- `premiereConnexionAdminBootstrap_avantChangementMotDePasse_shouldRetourner403SurRoutesProtegees` :
  créer un agent avec le constructeur 6-arg (`doitChangerMotDePasse=true`), se connecter, appeler
  une route protégée hors liste blanche (ex. `GET /api/v1/agents`), asserter `403` et
  `jsonPath("$.code").value("MOT_DE_PASSE_TEMPORAIRE_NON_CHANGE")`.
- `moi_avantChangementMotDePasse_shouldRetourner200` : même agent, `GET /api/v1/agents/moi` doit
  rester accessible (`200`).
- `changerMotDePasse_avecMotDePasseActuelValide_shouldRetourner200EtLeverEnforcement` : même
  agent, `PUT /api/v1/agents/moi/mot-de-passe` avec l'ancien mot de passe correct et un nouveau,
  asserter `200`, `accessToken`/`refreshToken` non vides, puis avec le nouvel `accessToken`,
  asserter qu'une route précédemment bloquée (ex. `GET /api/v1/agents`, en tant qu'`ADMIN_NATIONAL`)
  n'est plus `403` pour cette raison.
- `changerMotDePasse_avecMotDePasseActuelInvalide_shouldRetourner400` : même agent, mauvais
  `motDePasseActuel`, asserter `400` et `jsonPath("$.code").value("MOT_DE_PASSE_ACTUEL_INVALIDE")`.

Toutes les assertions `getContentAsString()` sur des réponses MockMvc contenant du texte
accentué doivent utiliser `getContentAsString(StandardCharsets.UTF_8)`, conformément à la
convention déjà en place dans le projet.

## Écarts identifiés

1. **Type de `AdminBootstrapProperties.posteId`** : le design mentionne "`BOOTSTRAP_ADMIN_POSTE_ID`
   (UUID)" sans préciser le type Java du champ. Un champ `UUID` avec le défaut
   `${BOOTSTRAP_ADMIN_POSTE_ID:}` (chaîne vide) ferait échouer la conversion Spring Boot au
   démarrage de **toute** l'application, y compris quand le bootstrap est désactivé -- ce qui
   contredirait directement le critère d'acceptation "ne s'active pas silencieusement" en le
   transformant en "casse le démarrage silencieusement". Tranché dans cette spec : champ `String`,
   parsing manuel en `UUID` dans le runner, uniquement quand `enabled=true` (voir Contrat
   technique §3-4).
2. **Nom et signature de la méthode `Agent` pour le changement de mot de passe** : le design ne
   nomme que `motDePasseChange()` (sans paramètre) pour "remettre le flag à `false`", sans préciser
   comment le hash du nouveau mot de passe est persisté sur l'entité (`hashMotDePasse` n'a
   aujourd'hui aucun setter). Tranché dans cette spec : une unique méthode
   `changerMotDePasse(String nouveauHashMotDePasse)` qui met à jour le hash **et** remet le flag à
   `false` en une seule opération atomique côté entité (voir Contrat technique §2), plutôt que deux
   méthodes séparées qui laisseraient une fenêtre d'incohérence possible si l'une est appelée sans
   l'autre.
3. **Statut HTTP du mot de passe actuel invalide sur `PUT /moi/mot-de-passe`** : non spécifié par
   le design. Tranché dans cette spec à `400 BAD_REQUEST` / `MOT_DE_PASSE_ACTUEL_INVALIDE` (plutôt
   que `401`, l'appelant étant déjà authentifié à ce stade) -- cohérent avec le traitement existant
   des erreurs de validation métier dans `AgentAdminExceptionHandler` (`IllegalArgumentException`
   -> `400`).
