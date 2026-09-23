# Design -- Ticket #58 : Bootstrap du premier compte administrateur

## Approche

Ajouter un `CommandLineRunner` (`AdminBootstrapRunner`, module `sn.samapiece.iam`) explicitement
conditionné par une variable d'environnement (`BOOTSTRAP_ADMIN_ENABLED=true`), qui crée un unique
agent `ADMIN_NATIONAL` avec mot de passe temporaire généré aléatoirement, uniquement si la table
`agent` est vide (`agentRepository.count() == 0`) -- idempotent et sans risque de doublon même si
la variable reste positionnée dans un pipeline de déploiement. Le mot de passe en clair est loggé
une seule fois au démarrage (seul canal disponible, cohérent avec la limitation déjà documentée en
#9 pour `POST /api/v1/agents` -- pas de SMS/email). Constat clé en explorant le code : contrairement
à ce que suggère le corps du ticket, aucun mécanisme de renouvellement forcé à la première
connexion n'existe aujourd'hui pour les agents créés par `POST /api/v1/agents` -- `motDePasseTemporaire`
dans `CreerAgentResponse` n'est que le mot de passe en clair retourné une fois, sans flag persisté
ni contrôle d'accès bloquant (confirmé par lecture de `Agent.java`, `AgentAdminService`, `AuthService`,
`AgentSelfService`/`AgentSelfController` -- aucun champ, endpoint ou vérification de ce type). Ce
ticket construit donc ce mécanisme génériquement (colonne + JWT claim + endpoint de changement +
filtre d'application) mais ne l'active que pour le compte bootstrap, sans toucher au comportement
existant de création d'agent par un admin (prix assumé : l'incohérence relevée par le ticket
subsiste pour les agents créés via #9, à traiter par un futur ticket -- voir "Hors périmètre").
Le choix `CommandLineRunner` plutôt que migration Flyway est dicté par le besoin de hasher un mot
de passe (`PasswordEncoder`/BCrypt) et de lire une config d'environnement variable par déploiement
-- une migration SQL versionnée et fixe ne peut faire ni l'un ni l'autre proprement.

## Fichiers/modules impactés

Backend :
- `backend/src/main/resources/db/migration/V12__ajoute_doit_changer_mot_de_passe_agent.sql`
  (nouveau) -- `ALTER TABLE agent ADD COLUMN doit_changer_mot_de_passe BOOLEAN NOT NULL DEFAULT false;`
  suit la convention de nommage/numérotation des migrations existantes (V1..V11 déjà en place).
- `backend/src/main/java/sn/samapiece/iam/Agent.java` (modifié) -- champ `doitChangerMotDePasse`,
  getter, nouveau constructeur surchargé acceptant le flag (le constructeur 5-arg existant reste
  inchangé, flag `false` par défaut, pour ne rien changer au comportement de #9), méthode métier
  `motDePasseChange()` qui remet le flag à `false`.
- `backend/src/main/java/sn/samapiece/iam/MotDePasseTemporaireGenerator.java` (nouveau, `@Component`)
  -- extraction de la génération (`SecureRandom`, alphabet, longueur 12) actuellement dupliquée en
  privé dans `AgentAdminService.genererMotDePasseTemporaire()`, réutilisée par `AgentAdminService`
  (refactor, comportement inchangé) et par `AdminBootstrapRunner`.
- `backend/src/main/java/sn/samapiece/iam/AdminBootstrapRunner.java` (nouveau, `CommandLineRunner`)
  -- logique de création du premier admin, voir "Décisions clés".
- `backend/src/main/java/sn/samapiece/iam/AdminBootstrapProperties.java` (nouveau, `@ConfigurationProperties`,
  préfixe `samapiece.bootstrap-admin`) -- `enabled`, `matricule`, `nom`, `posteId`, suit le style
  déjà établi par `JwtProperties`/`RateLimitingProperties`.
- `backend/src/main/java/sn/samapiece/iam/jwt/JwtService.java` (modifié) -- nouveau claim
  `CLAIM_DOIT_CHANGER_MOT_DE_PASSE` embarqué dans l'access token par `genererAccessToken(...)`
  (signature étendue avec un `boolean`).
- `backend/src/main/java/sn/samapiece/iam/AuthService.java` (modifié) -- `login()` et `refresh()`
  passent `agent.isDoitChangerMotDePasse()` à `genererAccessToken(...)` (donnée déjà chargée en
  base à cet endroit, aucun accès DB supplémentaire).
- `backend/src/main/java/sn/samapiece/iam/jwt/JwtAuthenticationFilter.java` (modifié) -- lit le
  nouveau claim et le stocke dans `authentication.setDetails(...)` pour le rendre disponible au
  filtre suivant sans re-décoder le token.
- `backend/src/main/java/sn/samapiece/iam/jwt/ForcerChangementMotDePasseFilter.java` (nouveau,
  `OncePerRequestFilter`) -- bloque en 403 toute requête authentifiée avec le claim actif, sauf une
  liste blanche courte (`PUT /api/v1/agents/moi/mot-de-passe`, `GET /api/v1/agents/moi`,
  `/api/v1/auth/refresh`, `/actuator/**`).
- `backend/src/main/java/sn/samapiece/iam/AgentSelfService.java` (modifié) -- nouvelle méthode
  `changerMotDePasse(ChangerMotDePasseRequest)` : vérifie l'ancien mot de passe via
  `PasswordEncoder`, encode le nouveau, appelle `agent.motDePasseChange()`, ré-émet un
  access/refresh token frais (pour ne pas attendre l'expiration 15 min de l'ancien token porteur
  du claim).
- `backend/src/main/java/sn/samapiece/iam/web/AgentSelfController.java` (modifié) -- nouveau
  `PUT /api/v1/agents/moi/mot-de-passe`.
- `backend/src/main/java/sn/samapiece/iam/web/ChangerMotDePasseRequest.java`,
  `ChangerMotDePasseResponse.java` (nouveaux records DTO, style `LoginRequest`/`LoginResponse`).
- `backend/src/main/java/sn/samapiece/config/SecurityConfig.java` (modifié) -- instanciation et
  `addFilterAfter(forcerChangementMotDePasseFilter, JwtAuthenticationFilter.class)`, suit le style
  déjà en place pour `captchaFilter`/`rateLimitFilter`.
- `backend/src/main/resources/application.yml` (modifié) -- section `samapiece.bootstrap-admin`
  avec `enabled: ${BOOTSTRAP_ADMIN_ENABLED:false}` (défaut désactivé partout, y compris `dev`/`prod`).
- `backend/README.md` (modifié) -- section "Bootstrap du premier compte administrateur" : variables
  d'environnement, procédure (créer une Région/un Poste au préalable -- voir Risques --, positionner
  les variables, démarrer une fois, récupérer le mot de passe dans les logs, se connecter, le
  changer via le nouvel endpoint), rappel que le mécanisme est un no-op si un agent existe déjà.
- Tests : `backend/src/test/java/sn/samapiece/iam/AdminBootstrapRunnerIntegrationTest.java` (nouveau),
  `AgentSelfServiceTest.java`/`AgentAdminIntegrationTest.java` (complétés pour le changement de mot
  de passe et l'enforcement 403), `JwtServiceTest.java` (nouveau claim).

## Décisions clés

1. **Déclenchement** : `CommandLineRunner` + `BOOTSTRAP_ADMIN_ENABLED` (défaut `false` partout),
   plutôt qu'un profil Spring dédié (`bootstrap`) -- une variable d'environnement évite de coupler
   ce comportement ponctuel à `SPRING_PROFILES_ACTIVE`, qui pilote déjà `dev`/`staging`/`prod`
   (section 11 du projet) et ne doit pas être détourné pour un besoin d'exécution unique.
2. **Garde d'idempotence** : `agentRepository.count() == 0` (pas `existsByRole(ADMIN_NATIONAL)`).
   Plus strict et plus sûr : ne bootstrappe que sur une base réellement vierge, ne crée jamais de
   second admin si des comptes existent déjà pour quelque raison que ce soit (satisfait "ne
   s'active pas silencieusement en production si un admin existe déjà").
3. **Rattachement à un `Poste`** : `Agent.poste` est `NOT NULL` (V1/V2) et il n'existe aucun
   mécanisme (API ou seed) pour créer une `Region`/un `Poste` -- `PosteController` n'expose qu'un
   `GET` (`referentiel/web/PosteController.java:19`). Décision : le runner exige
   `BOOTSTRAP_ADMIN_POSTE_ID` (UUID) en variable d'environnement et échoue au démarrage
   (exception, pas de démarrage silencieux dégradé) si le poste n'existe pas -- l'opérateur doit
   créer au moins une Région+Poste par SQL direct avant d'activer le bootstrap. Documenté
   explicitement dans le README comme prérequis.
4. **Rôle du compte bootstrap** : `ADMIN_NATIONAL` (seul rôle sans restriction de périmètre dans
   `verifierRoleAssignable`/`verifierPerimetrePoste` de `AgentAdminService` -- nécessaire pour
   ensuite créer/scoper tous les autres comptes).
5. **Renouvellement forcé** : construit génériquement (colonne + claim JWT + filtre + endpoint) mais
   activé uniquement pour le compte bootstrap (nouveau constructeur `Agent(..., doitChangerMotDePasse=true)`),
   pas pour `AgentAdminService.creer()` qui garde son comportement actuel (`false` par défaut) --
   voir "Hors périmètre" pour la justification de ce périmètre volontairement réduit.
6. **Mécanisme d'enforcement** : claim JWT (`doitChangerMotDePasse`) plutôt qu'un flag lu en base à
   chaque requête -- cohérent avec l'architecture JWT stateless existante (aucun appel DB
   supplémentaire par requête dans `JwtAuthenticationFilter`, qui n'en fait aujourd'hui aucun).
   Prix : après changement de mot de passe, l'ancien access token (jusqu'à 15 min de TTL) porte
   encore l'ancien claim s'il est réutilisé -- compensé en ré-émettant un token frais dans la
   réponse de l'endpoint de changement, pour que le flux normal (frontend remplace immédiatement
   son token) ne rencontre jamais ce cas.
7. **Alternative écartée** : renouvellement "imposé" uniquement côté UI (flag informatif dans
   `LoginResponse`, sans blocage serveur). Écartée car le mot "imposé" du critère d'acceptation et
   la posture sécurité du projet (verrouillage de compte, chiffrement au repos #27) appellent un
   contrôle serveur réel, pas une simple convention frontend -- d'autant qu'aucune UI de login
   n'existe encore côté frontend pour porter cette convention (voir Hors périmètre).

## Risques / points d'attention

- **Dépendance non résolue Poste/Région** : sur une base vraiment neuve, il n'existe aujourd'hui
  aucun moyen versionné de créer une Région/un Poste (seul `GET /api/v1/postes` existe). Le
  bootstrap admin est donc bloqué tant qu'un opérateur n'a pas inséré ces lignes manuellement --
  à documenter très explicitement dans le README pour éviter un `CommandLineRunner` qui échoue de
  façon peu claire au démarrage.
- **Mot de passe en clair dans les logs de démarrage** : seul canal disponible (même limitation
  qu'en #9), mais plus sensible ici car les logs de démarrage sont souvent plus largement
  accessibles (CI/CD, orchestrateur) qu'une réponse HTTP à un admin déjà authentifié -- à souligner
  au reviewer, s'assurer qu'aucun agrégateur de logs ne persiste ce niveau indéfiniment en prod.
- **Extension du domaine de `JwtAuthenticationFilter`/nouveau filtre** : ajoute une liste blanche de
  routes en dur dans `ForcerChangementMotDePasseFilter` ; un oubli lors d'un futur endpoint
  légitimement accessible avant changement de mot de passe (ex. futur `/api/v1/auth/logout`)
  provoquerait un 403 inattendu -- documenté comme point de vigilance pour le reviewer.
- **Portée volontairement limitée à l'enforcement JWT** : le refresh token (7 jours, sans claim de
  ce type aujourd'hui) n'est pas concerné par le flag -- un agent avec `doitChangerMotDePasse=true`
  qui rafraîchit son access token reçoit bien un nouveau claim à jour (car `refresh()` recharge
  l'agent depuis la base), donc pas de contournement possible par ce biais.
- **Tests existants non affectés** : le constructeur 5-arg `Agent(...)` et `AgentAdminService.creer()`
  restent inchangés en comportement par défaut (`doitChangerMotDePasse=false`), donc
  `AgentAdminIntegrationTest` (login immédiat après création par un admin) ne devrait pas se
  casser -- à vérifier explicitement par le codeur/reviewer puisque le constructeur change de
  signature (surcharge, pas remplacement).
- **Minimisation des données** : le compte bootstrap ne stocke que `matricule`/`nom` fournis par
  variable d'environnement, aucune donnée personnelle supplémentaire -- cohérent avec le modèle
  `agent` existant (§9/§10 du document produit).

## Hors périmètre

- Étendre le renouvellement forcé du mot de passe aux agents créés via `POST /api/v1/agents`
  (`AgentAdminService.creer`) : c'est l'écart que le corps du ticket #58 présuppose déjà comblé et
  qui ne l'est pas -- délibérément non traité ici pour ne pas modifier silencieusement le
  comportement/tests de #9 en marge d'un ticket "infra bootstrap" ; à formaliser en ticket de suivi
  explicite.
- Créer un mécanisme de seed pour `Region`/`Poste` (aucun n'existe) -- prérequis manuel documenté,
  pas construit ici.
- Toute interface frontend (page de login, écran "changer mon mot de passe") : aucune UI
  d'authentification n'existe encore dans `frontend/src` (confirmé par absence de fichier
  `*login*`) -- ce ticket reste backend uniquement.
- Rotation/révocation immédiate d'un access token déjà émis avant bootstrap (blacklist JWT) --
  non demandé, cohérent avec la limitation déjà actée en #9.
- Envoi du mot de passe temporaire par un canal autre que les logs de démarrage (SMS/email) --
  hors périmètre, comme pour la création d'agent standard.
- Gestion de plusieurs comptes bootstrap ou ré-exécution pour créer un admin supplémentaire après
  coup : une fois qu'un agent existe, la création de comptes supplémentaires reste le rôle exclusif
  de `POST /api/v1/agents`.
