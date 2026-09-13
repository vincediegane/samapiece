# Spec — Ticket #7 : Authentification agent (login + JWT)

## Résumé

Ajout d'une authentification stateless par JWT auto-émis (login `matricule` + mot de passe
BCrypt, access token 15 min, refresh token 7 jours), avec verrouillage de compte après 5 échecs
consécutifs et rejet 401 de toute route non publique sans JWT valide.

## Tâches

- [ ] **`backend/pom.xml`** : ajouter les dépendances `io.jsonwebtoken:jjwt-api`,
  `jjwt-impl` (scope `runtime`), `jjwt-jackson` (scope `runtime`), version `0.12.6`
  (vérifier sur Maven Central qu'il n'existe pas de patch plus récent de la branche 0.12.x avant
  de figer la version).
- [ ] **`backend/src/main/resources/db/migration/V3__ajoute_verrouillage_agent.sql`** :
  migration Flyway (voir Contrat technique § Schéma SQL).
- [ ] **`backend/src/main/java/sn/samapiece/iam/Agent.java`** : ajouter les champs
  `tentativesEchouees`/`verrouilleJusqua`, les initialiser dans le constructeur métier, exposer
  les getters, ajouter les méthodes métier `enregistrerConnexionReussie()`,
  `enregistrerEchecConnexion(int seuil, Duration dureeVerrouillage)`, `estVerrouille()`. Aucun
  setter générique.
- [ ] **`backend/src/main/java/sn/samapiece/iam/AgentRepository.java`** : ajouter
  `Optional<Agent> findByMatricule(String matricule)`.
- [ ] **`backend/src/main/java/sn/samapiece/iam/jwt/JwtProperties.java`** (nouveau) :
  `@ConfigurationProperties(prefix = "samapiece.jwt")`, champ `secret` obligatoire.
- [ ] **`backend/src/main/java/sn/samapiece/iam/jwt/JwtService.java`** (nouveau) : génération
  access/refresh token, parsing/validation, horloge injectable.
- [ ] **`backend/src/main/java/sn/samapiece/iam/jwt/JwtAuthenticationFilter.java`** (nouveau) :
  `OncePerRequestFilter`, extraction header `Authorization`, peuplement `SecurityContext`.
- [ ] **`backend/src/main/java/sn/samapiece/iam/AuthenticationException.java`** (nouveau) :
  exception 401 (identifiants invalides / token invalide ou expiré / compte inactif).
- [ ] **`backend/src/main/java/sn/samapiece/iam/CompteVerrouilleException.java`** (nouveau) :
  exception 423 (compte verrouillé).
- [ ] **`backend/src/main/java/sn/samapiece/iam/AuthService.java`** (nouveau) : logique
  login/refresh/verrouillage (voir Contrat technique § AuthService).
- [ ] **`backend/src/main/java/sn/samapiece/iam/web/LoginRequest.java`**,
  **`LoginResponse.java`**, **`RefreshRequest.java`**, **`RefreshResponse.java`** (nouveaux,
  records, sur le modèle de `referentiel/web/PosteResponse.java`).
- [ ] **`backend/src/main/java/sn/samapiece/iam/web/AuthController.java`** (nouveau) :
  `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh`.
- [ ] **`backend/src/main/java/sn/samapiece/iam/web/AuthExceptionHandler.java`** (nouveau) :
  `@RestControllerAdvice`, mappe `AuthenticationException` → 401, `CompteVerrouilleException` →
  423, corps de réponse générique (jamais le message brut de l'exception).
- [ ] **`backend/src/main/java/sn/samapiece/config/SecurityConfig.java`** (modifié) :
  `SessionCreationPolicy.STATELESS`, `csrf().disable()`, `permitAll()` sur
  `POST /api/v1/auth/login` et `POST /api/v1/auth/refresh`, bean `PasswordEncoder`
  (`BCryptPasswordEncoder`), insertion de `JwtAuthenticationFilter` via `addFilterBefore`,
  **et** configuration explicite d'un `AuthenticationEntryPoint` renvoyant 401 (voir Écarts
  identifiés — sans cela, le comportement par défaut de Spring Security renvoie 403, pas 401).
- [ ] **`backend/src/main/resources/application.yml`** : ajouter
  `samapiece.jwt.secret: ${JWT_SECRET}` (pas de valeur par défaut).
- [ ] **`backend/src/test/resources/application.yml`** (nouveau) : fournir une valeur de test
  pour `samapiece.jwt.secret` (voir Écarts identifiés — sans ce fichier, tous les
  `@SpringBootTest` existants, y compris `AgentIntegrationTest`/`PosteIntegrationTest`, cessent
  de démarrer dès que `JwtProperties` devient un bean requis sans défaut).
- [ ] **`.env.example`** : ajouter une section `# --- Authentification (JWT) ---` avec
  `JWT_SECRET=` (valeur d'exemple non sensible ≥ 32 caractères, voir Contrat technique).
- [ ] **`docker-compose.yml`** : transmettre `JWT_SECRET: ${JWT_SECRET}` dans
  `services.backend.environment` (sinon `docker-compose up backend` échoue faute de variable,
  cf. `.env.example`).
- [ ] **`backend/README.md`** : documenter la variable `JWT_SECRET` (obligatoire, ≥ 256 bits soit
  ≥ 32 caractères, HMAC-SHA256, pas de valeur par défaut) et une commande pour en générer une en
  local (ex. `openssl rand -base64 32`).
- [ ] **`backend/src/test/java/sn/samapiece/iam/jwt/JwtServiceTest.java`** (nouveau, test
  unitaire, pas de Spring/Testcontainers) : génération/validation des claims, expiration via
  horloge injectée.
- [ ] **`backend/src/test/java/sn/samapiece/iam/AuthIntegrationTest.java`** (nouveau,
  Testcontainers + MockMvc, même pattern que `PosteIntegrationTest`) : tous les scénarios listés
  en Plan de tests.
- [ ] **`backend/src/test/java/sn/samapiece/referentiel/PosteIntegrationTest.java`** (modifié) :
  remplacer l'assertion `status().is4xxClientError()` de
  `getRouteNonPubliqueSansAuthentification_shouldReturn4xx` par `status().isUnauthorized()`
  (renommer le test en conséquence, ex. `...shouldReturn401`), maintenant que l'entry point 401
  est explicite.

## Contrat technique

### Schéma SQL — `V3__ajoute_verrouillage_agent.sql`

```sql
ALTER TABLE agent
    ADD COLUMN tentatives_echouees INT NOT NULL DEFAULT 0,
    ADD COLUMN verrouille_jusqu_a TIMESTAMPTZ;
```

Pas de contrainte `CHECK` sur `tentatives_echouees` (bornage géré côté applicatif). Le défaut
`0`/`NULL` garantit que `AgentIntegrationTest` existant reste valide sans modification.

### `Agent.java` — ajouts

```java
@Column(name = "tentatives_echouees", nullable = false)
private int tentativesEchouees;

@Column(name = "verrouille_jusqu_a")
private OffsetDateTime verrouilleJusqua;
```

Dans le constructeur métier `Agent(Poste, String, String, Role, String)`, ajouter
`this.tentativesEchouees = 0;` et `this.verrouilleJusqua = null;`.

```java
public int getTentativesEchouees() {
    return tentativesEchouees;
}

public OffsetDateTime getVerrouilleJusqua() {
    return verrouilleJusqua;
}

public void enregistrerConnexionReussie() {
    this.tentativesEchouees = 0;
    this.verrouilleJusqua = null;
    this.derniereConnexion = OffsetDateTime.now();
}

/**
 * Incrémente le compteur d'échecs ; si le seuil est atteint, verrouille le compte pour la durée
 * donnée et réinitialise le compteur (le prochain cycle de comptage repart de zéro après
 * expiration du verrouillage).
 */
public void enregistrerEchecConnexion(int seuil, Duration dureeVerrouillage) {
    this.tentativesEchouees++;
    if (this.tentativesEchouees >= seuil) {
        this.verrouilleJusqua = OffsetDateTime.now().plus(dureeVerrouillage);
        this.tentativesEchouees = 0;
    }
}

public boolean estVerrouille() {
    return verrouilleJusqua != null && verrouilleJusqua.isAfter(OffsetDateTime.now());
}
```

Import supplémentaire requis : `java.time.Duration`.

### `AgentRepository.java` — ajout

```java
Optional<Agent> findByMatricule(String matricule);
```

(import `java.util.Optional`, dérivé automatiquement par Spring Data du nom de méthode, aucune
`@Query` nécessaire.)

### `JwtProperties.java`

```java
package sn.samapiece.iam.jwt;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "samapiece.jwt")
public class JwtProperties {

    @NotBlank
    private String secret;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }
}
```

`@Component` + `@ConfigurationProperties` suffit (scan automatique sous `sn.samapiece`, pas
besoin de `@EnableConfigurationProperties`).

### `JwtService.java`

```java
package sn.samapiece.iam.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import sn.samapiece.iam.Role;

@Component
public class JwtService {

    public static final String CLAIM_MATRICULE = "matricule";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_TYPE = "typ";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);
    static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(7);

    private final SecretKey signingKey;
    private final Clock clock;

    @Autowired
    public JwtService(JwtProperties jwtProperties) {
        this(jwtProperties, Clock.systemUTC());
    }

    /** Constructeur secondaire public : permet d'injecter une horloge fixe en test
     * (ex. pour générer un token déjà expiré), sans passer par un mock du temps système. */
    public JwtService(JwtProperties jwtProperties, Clock clock) {
        this.signingKey = Keys.hmacShaKeyFor(
                jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    public String genererAccessToken(UUID agentId, String matricule, Role role) {
        Instant maintenant = clock.instant();
        return Jwts.builder()
                .subject(agentId.toString())
                .claim(CLAIM_MATRICULE, matricule)
                .claim(CLAIM_ROLE, role.name())
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(Date.from(maintenant))
                .expiration(Date.from(maintenant.plus(ACCESS_TOKEN_TTL)))
                .signWith(signingKey)
                .compact();
    }

    public String genererRefreshToken(UUID agentId, String matricule) {
        Instant maintenant = clock.instant();
        return Jwts.builder()
                .subject(agentId.toString())
                .claim(CLAIM_MATRICULE, matricule)
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuedAt(Date.from(maintenant))
                .expiration(Date.from(maintenant.plus(REFRESH_TOKEN_TTL)))
                .signWith(signingKey)
                .compact();
    }

    /** @throws io.jsonwebtoken.JwtException si signature invalide, token expiré ou malformé. */
    public Claims analyserToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long accessTokenTtlSecondes() {
        return ACCESS_TOKEN_TTL.getSeconds();
    }
}
```

Ne jamais logger `token`, `signingKey` ni le contenu de `Claims` (voir risques design.md).

### Claims JWT — structure exacte

Access token :

| claim | valeur |
|---|---|
| `sub` | `agent.getId()` (UUID en string) |
| `matricule` | `agent.getMatricule()` |
| `role` | `agent.getRole().name()` (ex. `AGENT`) |
| `typ` | `"access"` |
| `iat` | horodatage d'émission |
| `exp` | `iat + 15 min` |

Refresh token : `sub`, `matricule`, `typ = "refresh"`, `iat`, `exp = iat + 7 jours`. **Pas** de
claim `role` (le rôle est relu en base à chaque refresh).

### `JwtAuthenticationFilter.java`

```java
package sn.samapiece.iam.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import sn.samapiece.iam.Role;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIXE_BEARER = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIXE_BEARER)) {
            String token = header.substring(PREFIXE_BEARER.length());
            try {
                Claims claims = jwtService.analyserToken(token);
                if (JwtService.TYPE_ACCESS.equals(claims.get(JwtService.CLAIM_TYPE, String.class))) {
                    String matricule = claims.get(JwtService.CLAIM_MATRICULE, String.class);
                    Role role = Role.valueOf(claims.get(JwtService.CLAIM_ROLE, String.class));
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(matricule, null, List.of(role)));
                }
                // typ = refresh présenté sur une route protégée : on ignore silencieusement,
                // aucune authentification n'est posée -> 401 via anyRequest().authenticated().
            } catch (JwtException | IllegalArgumentException e) {
                // Token invalide/expiré/malformé : ne jamais logger token ou message brut ;
                // ne pas authentifier, laisser la chaîne Spring Security répondre 401.
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
```

### `AuthenticationException.java` / `CompteVerrouilleException.java`

```java
package sn.samapiece.iam;

public class AuthenticationException extends RuntimeException {
    public AuthenticationException(String message) {
        super(message);
    }
}
```

```java
package sn.samapiece.iam;

public class CompteVerrouilleException extends RuntimeException {
    public CompteVerrouilleException(String message) {
        super(message);
    }
}
```

### `AuthService.java`

```java
package sn.samapiece.iam;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.time.Duration;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.samapiece.iam.jwt.JwtService;
import sn.samapiece.iam.web.LoginRequest;
import sn.samapiece.iam.web.LoginResponse;
import sn.samapiece.iam.web.RefreshRequest;
import sn.samapiece.iam.web.RefreshResponse;

@Service
public class AuthService {

    static final int SEUIL_ECHECS = 5;
    static final Duration DUREE_VERROUILLAGE = Duration.ofMinutes(15);

    private final AgentRepository agentRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            AgentRepository agentRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.agentRepository = agentRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        Agent agent = agentRepository.findByMatricule(request.matricule())
                .orElseThrow(() -> new AuthenticationException("Identifiants invalides"));

        // Compte inactif rejeté avant toute vérification de mot de passe, sans incrémenter
        // le compteur d'échecs (désactivation != mot de passe erroné).
        if (!agent.isActif()) {
            throw new AuthenticationException("Identifiants invalides");
        }
        if (agent.estVerrouille()) {
            throw new CompteVerrouilleException("Compte verrouille");
        }
        if (!passwordEncoder.matches(request.motDePasse(), agent.getHashMotDePasse())) {
            agent.enregistrerEchecConnexion(SEUIL_ECHECS, DUREE_VERROUILLAGE);
            agentRepository.save(agent);
            // Le 5e échec consécutif déclenche le verrouillage : la réponse de cette 5e
            // tentative est déjà 423, pas 401.
            if (agent.estVerrouille()) {
                throw new CompteVerrouilleException("Compte verrouille");
            }
            throw new AuthenticationException("Identifiants invalides");
        }

        agent.enregistrerConnexionReussie();
        agentRepository.save(agent);

        String accessToken = jwtService.genererAccessToken(agent.getId(), agent.getMatricule(), agent.getRole());
        String refreshToken = jwtService.genererRefreshToken(agent.getId(), agent.getMatricule());
        return new LoginResponse(
                accessToken, refreshToken, jwtService.accessTokenTtlSecondes(),
                agent.getRole().name(), agent.getNom());
    }

    @Transactional
    public RefreshResponse refresh(RefreshRequest request) {
        Claims claims;
        try {
            claims = jwtService.analyserToken(request.refreshToken());
        } catch (JwtException | IllegalArgumentException e) {
            throw new AuthenticationException("Token invalide ou expire");
        }

        if (!JwtService.TYPE_REFRESH.equals(claims.get(JwtService.CLAIM_TYPE, String.class))) {
            throw new AuthenticationException("Token invalide ou expire");
        }

        UUID agentId = UUID.fromString(claims.getSubject());
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new AuthenticationException("Token invalide ou expire"));

        if (!agent.isActif()) {
            throw new AuthenticationException("Token invalide ou expire");
        }
        if (agent.estVerrouille()) {
            throw new CompteVerrouilleException("Compte verrouille");
        }

        String accessToken = jwtService.genererAccessToken(agent.getId(), agent.getMatricule(), agent.getRole());
        return new RefreshResponse(accessToken, jwtService.accessTokenTtlSecondes());
    }
}
```

### DTOs (records, package `sn.samapiece.iam.web`)

```java
public record LoginRequest(@NotBlank String matricule, @NotBlank String motDePasse) {}

public record LoginResponse(
        String accessToken, String refreshToken, long expiresIn, String role, String nom) {}

public record RefreshRequest(@NotBlank String refreshToken) {}

public record RefreshResponse(String accessToken, long expiresIn) {}
```

(`jakarta.validation.constraints.NotBlank`, exploité via `@Valid @RequestBody` dans le
contrôleur — `spring-boot-starter-validation` est déjà une dépendance du module.)

### `AuthController.java`

```java
package sn.samapiece.iam.web;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sn.samapiece.iam.AuthService;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<RefreshResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }
}
```

### `AuthExceptionHandler.java`

```java
package sn.samapiece.iam.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import sn.samapiece.iam.AuthenticationException;
import sn.samapiece.iam.CompteVerrouilleException;

@RestControllerAdvice
public class AuthExceptionHandler {

    public record ErreurReponse(String code, String message) {}

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErreurReponse> gererAuthentificationInvalide(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErreurReponse("IDENTIFIANTS_INVALIDES", "Matricule ou mot de passe invalide."));
    }

    @ExceptionHandler(CompteVerrouilleException.class)
    public ResponseEntity<ErreurReponse> gererCompteVerrouille(CompteVerrouilleException ex) {
        return ResponseEntity.status(HttpStatus.LOCKED)
                .body(new ErreurReponse("COMPTE_VERROUILLE", "Compte temporairement verrouillé."));
    }
}
```

Important : le corps de réponse est **toujours** un message générique fixe, jamais
`ex.getMessage()` (qui pourrait varier selon la cause interne) — conforme à l'exigence de ne
jamais révéler le nombre de tentatives restantes ni la raison précise (matricule inconnu vs mot
de passe faux vs compte inactif sont tous mappés au même message 401).

### `SecurityConfig.java` — version cible

```java
package sn.samapiece.config;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import sn.samapiece.iam.jwt.JwtAuthenticationFilter;
import sn.samapiece.iam.jwt.JwtService;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions ->
                    exceptions.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(authorize -> authorize
                    .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/postes").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                    .anyRequest().authenticated())
            .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

### Contrat JSON — `POST /api/v1/auth/login`

Requête :
```json
{ "matricule": "PN-2024-00123", "motDePasse": "MotDePasse123!" }
```

Réponse `200 OK` :
```json
{
  "accessToken": "eyJ...",
  "refreshToken": "eyJ...",
  "expiresIn": 900,
  "role": "AGENT",
  "nom": "Diop Awa"
}
```

Réponse `401 Unauthorized` (matricule inconnu, mot de passe invalide, ou compte inactif) :
```json
{ "code": "IDENTIFIANTS_INVALIDES", "message": "Matricule ou mot de passe invalide." }
```

Réponse `423 Locked` (compte verrouillé, y compris sur la tentative qui déclenche le
verrouillage) :
```json
{ "code": "COMPTE_VERROUILLE", "message": "Compte temporairement verrouillé." }
```

### Contrat JSON — `POST /api/v1/auth/refresh`

Requête :
```json
{ "refreshToken": "eyJ..." }
```

Réponse `200 OK` :
```json
{ "accessToken": "eyJ...", "expiresIn": 900 }
```

`401` si le refresh token est invalide, expiré, de type `access` (mauvais `typ`), ou si l'agent
associé n'existe plus ou est inactif. `423` si l'agent est verrouillé au moment du refresh.

### `.env.example` — bloc à ajouter

```
# --- Authentification (JWT) ---
# Secret HMAC-SHA256, >= 32 caracteres (256 bits). Generer une valeur en local avec :
#   openssl rand -base64 32
# Ne jamais utiliser cette valeur d'exemple en staging/prod.
JWT_SECRET=changez_moi_avec_une_valeur_aleatoire_dau_moins_32_caracteres
```

## Plan de tests

| Critère d'acceptation | Test |
|---|---|
| Login valide → JWT + refresh token | `AuthIntegrationTest.login_avecIdentifiantsValides_shouldRetournerAccessEtRefreshToken` : crée un agent actif via `agentRepository`/`passwordEncoder`, POST `/api/v1/auth/login`, vérifie `200`, `accessToken`/`refreshToken` non vides, `expiresIn == 900`, `role`/`nom` corrects. |
| Login rejette mot de passe invalide | `AuthIntegrationTest.login_avecMotDePasseInvalide_shouldRetourner401` : POST avec mauvais `motDePasse`, vérifie `401` et corps `code = IDENTIFIANTS_INVALIDES`. |
| (complément) Login rejette matricule inconnu | `AuthIntegrationTest.login_avecMatriculeInconnu_shouldRetourner401`. |
| (complément, design décision 6) Compte inactif rejeté sans incrémenter le compteur | `AuthIntegrationTest.login_avecCompteInactif_shouldRetourner401SansIncrementerCompteur` : agent `actif=false` (via `jdbcTemplate.update` puisqu'il n'y a pas de setter), vérifie `401` puis relit l'agent en base pour vérifier `tentativesEchouees == 0`. |
| Verrouillage après 5 échecs consécutifs | `AuthIntegrationTest.login_apresCinqEchecsConsecutifs_shouldRetourner423DesLaCinquiemeTentative` : 5 POST avec mauvais mot de passe consécutifs, vérifie que les 4 premières réponses sont `401` et la 5e est `423` ; relit l'agent en base pour vérifier `tentativesEchouees == 0` et `verrouilleJusqua` dans le futur. |
| Compte verrouillé rejette même un mot de passe correct | `AuthIntegrationTest.login_surCompteDejaVerrouille_shouldRetourner423MemeAvecMotDePasseCorrect` : provoque le verrouillage puis retente avec le bon mot de passe, vérifie `423`. |
| Refresh à partir d'un refresh token valide | `AuthIntegrationTest.refresh_avecRefreshTokenValide_shouldRetournerNouvelAccessToken` : login préalable pour obtenir un refresh token réel, POST `/api/v1/auth/refresh`, vérifie `200` et un nouvel `accessToken` non vide (`expiresIn == 900`). |
| Refresh rejette un token expiré | `AuthIntegrationTest.refresh_avecTokenExpire_shouldRetourner401` : construit un `JwtService` de test avec `new JwtService(jwtProperties, Clock.fixed(Instant.now().minus(Duration.ofDays(8)), ZoneOffset.UTC))` (même secret que le contexte, via `@Autowired JwtProperties`), génère un refresh token déjà expiré, POST `/api/v1/auth/refresh`, vérifie `401`. |
| (complément) Refresh rejette un access token présenté comme refresh token | `AuthIntegrationTest.refresh_avecAccessTokenAuLieuDeRefresh_shouldRetourner401` : réutilise l'`accessToken` du login sur `/auth/refresh`, vérifie `401` (claim `typ` invalide). |
| Route non publique sans JWT → 401 | `AuthIntegrationTest.routeProtegee_sansJwt_shouldRetourner401` : GET sur une route arbitraire non listée en `permitAll()` (ex. `/api/v1/inexistant-protege`), vérifie `401`. |
| (complément) Route non publique avec JWT invalide/malformé → 401 | `AuthIntegrationTest.routeProtegee_avecJwtMalforme_shouldRetourner401` : header `Authorization: Bearer token-invalide`, vérifie `401`. |
| (complément) Route non publique avec access token valide → filtre laisse passer | `AuthIntegrationTest.routeProtegee_avecAccessTokenValide_shouldPasserLeFiltreJwt` : login préalable, GET même route arbitraire avec `Authorization: Bearer <accessToken>`, vérifie que la réponse **n'est pas** `401` (ex. `404` puisque la route n'existe pas côté MVC — prouve que l'authentification a réussi et que seul le routage échoue). |
| Régression : route protégée sans JWT, cas générique historique | `PosteIntegrationTest.getRouteNonPubliqueSansAuthentification_shouldReturn401` (renommé depuis `...shouldReturn4xx`) : assertion resserrée à `status().isUnauthorized()`. |
| Génération/validation des claims + expiration (niveau unitaire) | `JwtServiceTest.genererAccessToken_shouldContenirClaimsAttendus`, `JwtServiceTest.genererRefreshToken_shouldPasContenirClaimRole`, `JwtServiceTest.analyserToken_avecTokenExpire_shouldLeverJwtException` (via `Clock` fixé dans le passé). |
| Verrouillage — logique métier isolée (niveau unitaire, complément) | `AgentTest.enregistrerEchecConnexion_shouldVerrouillerApresSeuilAtteint`, `AgentTest.enregistrerConnexionReussie_shouldReinitialiserCompteurEtVerrouillage`, `AgentTest.estVerrouille_shouldRetournerFauxApresExpiration` (test unitaire pur JUnit, pas de Spring). |
| Ne jamais logger secrets (risque design) | Pas de test automatisé pertinent — vérification manuelle en revue de code : aucun `log.debug`/`log.info` sur `motDePasse`, `hashMotDePasse`, `accessToken`, `refreshToken`, ou en-tête `Authorization` dans `AuthService`/`AuthController`/`JwtAuthenticationFilter`. |
| `JWT_SECRET` absent bloque le démarrage en dev sans erreur silencieuse | Manuel : `unset JWT_SECRET && mvn -pl backend spring-boot:run` doit échouer au démarrage avec un message clair (échec de binding `samapiece.jwt.secret`), pas un `NullPointerException` opaque plus tard dans `JwtService`. |

## Écarts identifiés

1. **Statut HTTP par défaut de Spring Security (403 vs 401).** Le `SecurityConfig` actuel
   (`anyRequest().authenticated()` sans `httpBasic()`/`formLogin()` ni `AuthenticationEntryPoint`
   explicite) renvoie **403** par défaut pour une requête non authentifiée
   (`Http403ForbiddenEntryPoint`), pas 401 — c'est d'ailleurs pourquoi le test existant
   `PosteIntegrationTest` se contente d'un `is4xxClientError()` générique. Le critère
   d'acceptation #7 exige explicitement 401. Le design.md ne mentionne pas la configuration d'un
   `AuthenticationEntryPoint` : ce point est tranché ici (tâche `SecurityConfig.java` +
   `HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)`), à ne pas oublier lors de l'implémentation.
2. **Fichier de configuration de test manquant.** L'ajout de `samapiece.jwt.secret` sans valeur
   par défaut dans `application.yml` casse le démarrage de **tous** les `@SpringBootTest`
   existants (`AgentIntegrationTest`, `PosteIntegrationTest`, etc.), pas seulement les nouveaux
   tests d'auth, car `JwtProperties` devient un bean requis au contexte. Le design.md ne liste
   pas de fichier de test dédié pour ce secret ; la tâche `backend/src/test/resources/application.yml`
   ci-dessus comble ce trou et doit être livrée avant/avec la migration, pas après.
2bis. Conséquence directe : le test `PosteIntegrationTest.getRouteNonPubliqueSansAuthentification_shouldReturn4xx`
   doit de toute façon être touché (voir écart n°1) — ce n'est donc pas un fichier laissé de
   côté par erreur, mais un ajustement volontaire de ce ticket.
3. **`docker-compose.yml` non listé dans design.md.** Le design mentionne le risque de secret
   absent en dev mais ne couvre que `README`/`.env.example`. Sans passer `JWT_SECRET` dans
   `services.backend.environment`, `docker-compose up backend` échouera au démarrage même avec
   un `.env` correctement rempli. Ajouté comme tâche explicite ci-dessus.
4. **Statut HTTP pour compte inactif.** Ni le ticket ni design.md ne précisent le code HTTP pour
   un agent `actif=false` qui tente de se connecter. Ce document tranche : traité comme
   identifiants invalides (401, message générique), cohérent avec le principe de ne pas
   distinguer les causes d'échec dans la réponse ; à confirmer en revue si un comportement
   différent était attendu.
