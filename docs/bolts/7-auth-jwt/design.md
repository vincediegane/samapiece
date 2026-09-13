# Design — Ticket #7 : Authentification agent (login + JWT)

## Approche

Authentification stateless par JWT auto-emis et auto-verifie par le backend (pas de serveur
d'autorisation separe, pas de Keycloak) : un AuthService verifie matricule + mot de passe (BCrypt)
contre Agent, emet un access token JWT courte duree (15 min) et un refresh token JWT longue duree
(7 jours), tous deux signes HMAC-SHA256 avec un secret unique partage (JWT_SECRET, jamais en clair
dans le repo). Un filtre OncePerRequestFilter valide le token sur chaque requete et peuple le
SecurityContext directement depuis les claims (matricule, role), sans round-trip base de donnees a
chaque appel. Le verrouillage de compte est un etat persistant sur Agent (compteur d'echecs +
horodatage de fin de verrouillage), mute via deux methodes metier dediees plutot que des setters
generiques, pour rester coherent avec l'entite immuable du ticket #6.

Compromis assume : pas de table de refresh tokens en base (pas de revocation explicite/logout), donc
un refresh token vole reste valide jusqu'a son expiration naturelle (7 jours) meme si le mot de passe
est change entre-temps, c'est le prix de la simplicite pour un monolithe sans besoin de logout
exprime dans ce ticket. Le controle actif/verrouillage est reverifie en base a chaque refresh, ce
qui limite (sans l'eliminer) la fenetre d'exposition pour un compte desactive.

## Fichiers/modules impactes

Modifies (existants, tous dans backend/src/main/java/sn/samapiece/) :
- iam/Agent.java : ajout des champs tentativesEchouees (int) et verrouilleJusqua
  (OffsetDateTime, nullable), plus deux methodes metier enregistrerConnexionReussie() et
  enregistrerEchecConnexion(int seuil, Duration dureeVerrouillage), plus estVerrouille(). Aucun
  setter generique ajoute.
- iam/AgentRepository.java : ajout de Optional<Agent> findByMatricule(String matricule).
- config/SecurityConfig.java : passage en SessionCreationPolicy.STATELESS, csrf().disable()
  (API Bearer, pas de cookies), ajout de permitAll() sur /api/v1/auth/login et
  /api/v1/auth/refresh, insertion du filtre JWT via
  addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class), ajout d'un
  bean PasswordEncoder (BCryptPasswordEncoder).
- backend/pom.xml : ajout de la dependance JWT (voir decisions).
- backend/src/main/resources/application.yml : ajout de samapiece.jwt.secret: ${JWT_SECRET}
  (pas de valeur par defaut, coherent avec la convention DB_USER/DB_PASSWORD deja en place dans
  application-dev.yml/application-prod.yml/application-staging.yml).
- backend/src/test/java/sn/samapiece/iam/AgentIntegrationTest.java : l'INSERT SQL brut du test
  insertAgentAvecRoleInvalide_shouldViolerContrainteCheck ne liste pas les nouvelles colonnes ; la
  migration doit leur donner un defaut (tentatives_echouees DEFAULT 0, verrouille_jusqu_a
  nullable sans defaut) pour que ce test reste valide sans modification. Ce fichier n'a donc pas
  besoin d'etre touche par ce ticket, mais un nouveau fichier de test dedie a l'auth doit etre cree.

Nouveaux fichiers a creer :
- backend/src/main/resources/db/migration/V3__ajoute_verrouillage_agent.sql : migration Flyway
  (voir decisions).
- iam/jwt/JwtService.java : generation/validation des tokens (acces + refresh), lecture des claims.
- iam/jwt/JwtAuthenticationFilter.java : filtre de securite, extraction du header Authorization.
- iam/jwt/JwtProperties.java (@ConfigurationProperties(prefix = "samapiece.jwt")) : secret.
- iam/AuthService.java : logique metier login/refresh/verrouillage.
- iam/web/AuthController.java : POST /api/v1/auth/login, POST /api/v1/auth/refresh.
- iam/web/LoginRequest.java, LoginResponse.java, RefreshRequest.java, RefreshResponse.java
  (records, sur le modele de referentiel/web/PosteResponse.java).
- iam/AuthenticationException.java (ou equivalent) + iam/web/AuthExceptionHandler.java
  (@RestControllerAdvice) : aucun @ControllerAdvice/gestion d'erreurs centralisee n'existe encore
  dans le repo, ce ticket introduit le premier, a mapper vers 401 (identifiants invalides / token
  invalide ou expire) et 423 (compte verrouille).
- backend/src/test/java/sn/samapiece/iam/AuthIntegrationTest.java : tests d'integration
  login valide, mot de passe invalide, compte verrouille, token expire (Testcontainers PostgreSQL,
  meme pattern que AgentIntegrationTest).

## Decisions cles

1. Bibliotheque JWT : io.jsonwebtoken:jjwt-api / jjwt-impl / jjwt-jackson (0.12.x), signature
   HMAC-SHA256 avec un secret symetrique unique (samapiece.jwt.secret, >= 256 bits, fourni par
   JWT_SECRET). Rejete : spring-boot-starter-oauth2-resource-server (concu pour valider des
   tokens emis par un IdP externe via JWKS, surdimensionne ici puisque le backend est a la fois
   emetteur et verificateur, sans serveur d'autorisation separe) ; Nimbus directement (API plus bas
   niveau, jjwt offre une API fluide suffisante pour ce besoin simple).
2. Migration V3 (V3__ajoute_verrouillage_agent.sql) :
   ALTER TABLE agent ADD COLUMN tentatives_echouees INT NOT NULL DEFAULT 0,
   ADD COLUMN verrouille_jusqu_a TIMESTAMPTZ;
   Pas de contrainte CHECK sur tentatives_echouees (bornage gere en code applicatif).
3. Claims du token d'acces : sub = agent.getId() (UUID en string), matricule, role (nom
   de l'enum, ex. AGENT), typ = access, iat, exp (= iat + 15 min). Refresh token :
   memes sub/matricule, typ = refresh, exp (= iat + 7 jours), pas de claim role (le
   role est revalide en base au moment du refresh pour refleter un eventuel changement).
4. Refresh token stateless, non stocke en base : valide uniquement par sa signature, son expiration
   et son claim typ, puis l'agent est relu en base (findById) pour verifier actif et estVerrouille()
   avant d'emettre un nouvel access token. Le refresh renvoie uniquement un nouvel access token (pas
   de rotation du refresh token), ce qui suffit au critere d'acceptation tel que formule.
5. Filtre JWT sans UserDetailsService/AuthenticationManager : AuthService.login() compare
   directement passwordEncoder.matches(...) contre agent.getHashMotDePasse(), sans passer par le
   provider Spring Security standard. Le filtre construit lui-meme un
   UsernamePasswordAuthenticationToken (principal = matricule, authorities = liste avec le role
   issu du claim role) et le pose dans le SecurityContext. Plus simple qu'un
   DaoAuthenticationProvider complet pour une seule source d'identite ; prix : moins idiomatique
   Spring Security, a documenter si un besoin d'auth multi-source apparait plus tard.
6. Verrouillage : seuil fixe de 5 echecs consecutifs, duree de verrouillage 15 minutes (constante
   applicative, pas exposee en configuration pour rester simple). Logique dans
   Agent.enregistrerEchecConnexion : incremente tentativesEchouees ; si le compteur atteint 5,
   fixe verrouilleJusqua = maintenant + 15 min et reinitialise le compteur a 0 (le verrouillage
   suivant repart d'un cycle propre apres expiration). Agent.enregistrerConnexionReussie remet
   tentativesEchouees a 0, verrouilleJusqua a null, et met a jour derniereConnexion. Un compte
   actif = false est rejete par AuthService avant toute verification de mot de passe et sans
   incrementer le compteur d'echecs (desactivation n'est pas equivalent a mot de passe errone).
7. Statut HTTP compte verrouille : 423 Locked, distinct du 401 generique pour mot de passe
   invalide (le corps de la reponse ne doit toutefois pas reveler le nombre de tentatives restantes
   a un attaquant, uniquement un message generique cote 401/423).
8. Reponse de login/refresh : LoginResponse(accessToken, refreshToken, expiresIn, role, nom),
   pas d'informations sensibles (pas de matricule d'autrui, pas de hash).

## Risques / points d'attention

- Coherence avec le ticket #6 : Agent est actuellement sans setters par choix explicite ; ce
  ticket doit ajouter des methodes metier ciblees (pas de setter generique type setActif ou
  setDerniereConnexion) pour ne pas ouvrir la porte a des mutations arbitraires ailleurs dans le
  code. A verifier en review que le codeur respecte bien ce principe.
- Latence de revocation : un access token reste valide jusqu'a 15 min apres desactivation ou
  verrouillage d'un compte (le filtre ne relit pas la base a chaque requete, par choix de
  performance). Acceptable pour ce ticket mais a documenter comme limite connue.
- Pas de logout / blacklist de token : hors perimetre de ce ticket faute de critere
  d'acceptation le demandant ; un refresh token compromis reste exploitable jusqu'a expiration.
- Ne jamais logger le mot de passe en clair, le hash BCrypt, ni les tokens JWT complets (le
  logging sn.samapiece a niveau DEBUG est actif en dev, verifier qu'aucun log.debug sur la requete
  de login ou sur le filtre JWT n'expose ces valeurs).
- Test "token expire" : necessite soit une TTL configurable injectable en test (ex. une horloge
  Clock injectee dans JwtService plutot qu'un Instant.now() fige), soit la generation directe
  d'un token expire via JwtService avec une duree negative dans le test, a trancher par le
  spec-writer/codeur, mais JwtService doit etre concu pour permettre l'un ou l'autre (eviter un
  Instant.now() statique non substituable).
- Secret JWT absent en environnement local : application-dev.yml n'a aujourd'hui aucune variable
  JWT_SECRET documentee ; sans valeur d'environnement, le demarrage echouera (comportement voulu,
  coherent avec l'interdiction de secret en clair), mais le README/.env.example de dev devra etre
  mis a jour par le codeur pour ne pas bloquer silencieusement les autres developpeurs.
- Premier @RestControllerAdvice du backend : aucune convention d'erreur HTTP n'existe encore
  dans le repo ; le format de reponse d'erreur choisi ici (401/423) fera precedent pour les tickets
  futurs, a garder volontairement minimal (code + message) pour ne pas sur-engager l'architecture
  d'erreurs globale.
- RBAC fin (section 10.3 du PROJET-SAMAPIECE.md : un agent ne voit que son poste) n'est pas
  construit par ce ticket, seul le role est porte dans les authorities. Le filtrage par poste_id
  reste a faire au niveau des futurs modules metier (enregistrement, retraitaudit, etc.).

## Hors perimetre

- MFA/OTP pour les agents (mentionne en vision produit sections 7.6.1/10.3, mais absent des
  criteres d'acceptation de ce ticket).
- Endpoint de logout / revocation explicite de token / table de refresh tokens en base.
- Rotation du refresh token a chaque appel de /auth/refresh.
- RBAC fin par ressource (scoping par poste, separation des taches sur les retraits sensibles).
- Rate limiting / CAPTCHA sur /api/v1/auth/login (pertinent contre le brute force distribue,
  mais non demande par ce ticket, le verrouillage par compte suffit aux criteres d'acceptation).
- Federation avec un futur SSO gouvernemental ADIE (section 11.4, explicitement une evolution
  future).
- Rotation obligatoire des mots de passe (mentionnee en section 7.6.1, non couverte par ce ticket).
