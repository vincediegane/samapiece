# Spec — #19 Rate limiting + CAPTCHA sur la recherche publique

## Résumé

Ajout de deux filtres Spring Security indépendants sur `POST /api/v1/recherche-publique` (rate
limiting Bucket4j+Redis par IP avec réponse 429, CAPTCHA mathématique backend-only par en-têtes HTTP
avec réponse 428 après N échecs consécutifs), fail-open sur panne Redis, sans aucun changement du
contrat JSON `RecherchePubliqueRequest`/`RecherchePubliqueResponse` livré par #18.

## Tâches

1. - [ ] `backend/pom.xml` — ajouter `org.springframework.boot:spring-boot-starter-data-redis` et
   `com.bucket4j:bucket4j-redis` (voir Contrat technique pour les imports Java attendus ; **vérifier sur
   Maven Central, avant tout code, la dernière version stable 8.x de `bucket4j-redis` compatible client
   Lettuce et Java 21**, aucune version n'étant gérée par le BOM `spring-boot-starter-parent` — pin la
   version explicitement dans le `<dependency>`).
2. - [ ] `backend/src/main/resources/application-dev.yml` — ajouter `spring.data.redis.host` /
   `spring.data.redis.port` (défauts `localhost`/`6379`, même patron que `DB_HOST`/`MINIO_ENDPOINT`) et
   les propriétés `samapiece.rate-limiting.recherche-publique.*` / `samapiece.captcha.*` (voir Contrat
   technique pour les clés et défauts exacts).
3. - [ ] `backend/src/main/java/sn/samapiece/recherche/securite/RateLimitingProperties.java` — nouveau,
   `@ConfigurationProperties(prefix = "samapiece.rate-limiting.recherche-publique")`, même patron que
   `MeilisearchProperties`.
4. - [ ] `backend/src/main/java/sn/samapiece/recherche/securite/CaptchaProperties.java` — nouveau,
   `@ConfigurationProperties(prefix = "samapiece.captcha")`.
5. - [ ] `backend/src/main/java/sn/samapiece/recherche/securite/RedisRateLimiterConfig.java` — nouveau,
   expose le bean `ProxyManager<String>` (Bucket4j-Redis/Lettuce) consommé par le filtre de rate
   limiting.
6. - [ ] `backend/src/main/java/sn/samapiece/recherche/securite/RecherchePubliqueRateLimitFilter.java` —
   nouveau, `OncePerRequestFilter`, scope `POST /api/v1/recherche-publique` uniquement, consomme un
   jeton du bucket Redis par IP, écrit un 429 JSON en cas d'épuisement, fail-open sur exception Redis.
7. - [ ] `backend/src/main/java/sn/samapiece/recherche/securite/EchecRechercheCounterService.java` —
   nouveau, encapsule `StringRedisTemplate` pour `enregistrerEchec(ip)` / `enregistrerSucces(ip)` /
   `captchaRequis(ip)` ; ne catch pas les exceptions Redis (propagées aux filtres appelants).
8. - [ ] `backend/src/main/java/sn/samapiece/recherche/securite/CaptchaVerifier.java` — nouveau,
   interface pluggable (`genererDefi()` / `verifier(...)`).
9. - [ ] `backend/src/main/java/sn/samapiece/recherche/securite/DefiMathematiqueCaptchaVerifier.java` —
   nouveau, implémentation par défaut (`@Component`), défi additif stocké dans Redis (clé aléatoire, TTL
   court, usage unique).
10. - [ ] `backend/src/main/java/sn/samapiece/recherche/CaptchaRequisException.java` — nouveau,
    exception non contrôlée mappée en 428 par `RecherchePubliqueExceptionHandler`.
11. - [ ] `backend/src/main/java/sn/samapiece/recherche/web/RecherchePubliqueExceptionHandler.java` —
    modifier, ajouter le `@ExceptionHandler(CaptchaRequisException.class)` (voir Contrat technique pour
    le corps JSON exact).
12. - [ ] `backend/src/main/java/sn/samapiece/recherche/web/CaptchaController.java` — nouveau,
    `GET /api/v1/recherche-publique/captcha` (public), délègue à `CaptchaVerifier.genererDefi()`.
13. - [ ] `backend/src/main/java/sn/samapiece/recherche/securite/RecherchePubliqueCaptchaFilter.java` —
    nouveau, `OncePerRequestFilter`, scope identique au filtre de rate limiting ; consulte
    `captchaRequis(ip)`, exige les en-têtes `X-Captcha-Token`/`X-Captcha-Reponse` sinon délègue à
    `HandlerExceptionResolver` avec `CaptchaRequisException` (→ 428 via la tâche 11) ; après appel de la
    chaîne (via `ContentCachingResponseWrapper`), incrémente ou réinitialise le compteur selon le
    statut/corps produit par le contrôleur ; fail-open sur exception Redis à chaque étape.
14. - [ ] `backend/src/main/java/sn/samapiece/config/SecurityConfig.java` — modifier :
    `permitAll()` sur `GET /api/v1/recherche-publique/captcha`, instanciation et enregistrement des deux
    nouveaux filtres via `addFilterBefore(...)` dans l'ordre rate-limit → captcha → JWT (voir Contrat
    technique pour le code exact).
15. - [ ] `docker-compose.yml` — ajouter `REDIS_HOST: redis` / `REDIS_PORT: 6379` au bloc
    `environment:` du service `backend` (même patron que `MINIO_ENDPOINT`). Pas de changement
    `.env.example` (déjà couvert par `REDIS_HOST_PORT` pour le mapping de port hôte uniquement).
16. - [ ] `backend/src/test/java/sn/samapiece/recherche/web/RecherchePubliqueRateLimitingIntegrationTest.java`
    — nouveau, exigé par le critère d'acceptation (déclenchement du 429).
17. - [ ] `backend/src/test/java/sn/samapiece/recherche/web/RecherchePubliqueCaptchaIntegrationTest.java`
    — nouveau, non explicitement exigé mais couvre le second critère d'acceptation (CAPTCHA requis après
    N échecs) et le mécanisme de résolution.
18. - [ ] Exécuter la suite existante (`RecherchePubliqueIntegrationTest`,
    `RecherchePubliqueMeilisearchIndisponibleIntegrationTest`, et le reste des `@SpringBootTest`) sans
    aucune modification et vérifier qu'elle reste verte (fail-open, aucun conteneur Redis démarré pour
    ces tests) — pas un fichier à créer, mais une vérification à consigner dans la PR.

## Contrat technique

### Propriétés de configuration

`backend/src/main/resources/application-dev.yml` (ajouts) :

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

samapiece:
  rate-limiting:
    recherche-publique:
      capacite: ${RATE_LIMIT_RECHERCHE_CAPACITE:10}
      periode-secondes: ${RATE_LIMIT_RECHERCHE_PERIODE_SECONDES:60}
  captcha:
    seuil-echecs-consecutifs: ${CAPTCHA_SEUIL_ECHECS:5}
    ttl-compteur-echecs-secondes: ${CAPTCHA_TTL_COMPTEUR_SECONDES:600}
    ttl-defi-secondes: ${CAPTCHA_TTL_DEFI_SECONDES:120}
```

`docker-compose.yml`, bloc `backend.environment` (ajout, valeurs internes fixes comme `MINIO_ENDPOINT`) :

```yaml
      REDIS_HOST: redis
      REDIS_PORT: 6379
```

### `RateLimitingProperties` / `CaptchaProperties`

```java
package sn.samapiece.recherche.securite;

@Component
@Validated
@ConfigurationProperties(prefix = "samapiece.rate-limiting.recherche-publique")
public class RateLimitingProperties {
    @Min(1) private long capacite = 10;
    @Min(1) private long periodeSecondes = 60;
    // getters/setters classiques, patron MeilisearchProperties
}
```

```java
package sn.samapiece.recherche.securite;

@Component
@Validated
@ConfigurationProperties(prefix = "samapiece.captcha")
public class CaptchaProperties {
    @Min(1) private int seuilEchecsConsecutifs = 5;
    @Min(1) private long ttlCompteurEchecsSecondes = 600;
    @Min(1) private long ttlDefiSecondes = 120;
    // getters/setters classiques
}
```

### `RedisRateLimiterConfig`

Bean dédié, indépendant de l'auto-configuration `spring-boot-starter-data-redis` (celle-ci fournit
`StringRedisTemplate`, consommé par `EchecRechercheCounterService`/`DefiMathematiqueCaptchaVerifier` ;
Bucket4j-Redis a besoin d'une `StatefulRedisConnection<String, byte[]>` Lettuce brute, incompatible
avec `RedisTemplate`) :

```java
package sn.samapiece.recherche.securite;

@Configuration
public class RedisRateLimiterConfig {

    @Bean(destroyMethod = "shutdown")
    public RedisClient redisClientBucket4j(
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port) {
        return RedisClient.create(RedisURI.Builder.redis(host, port).build());
    }

    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, byte[]> bucket4jRedisConnection(RedisClient redisClientBucket4j) {
        return redisClientBucket4j.connect(RedisCodec.of(new StringCodec(), new ByteArrayCodec()));
    }

    @Bean
    public ProxyManager<String> bucket4jProxyManager(
            StatefulRedisConnection<String, byte[]> bucket4jRedisConnection) {
        return LettuceBasedProxyManager.builderFor(bucket4jRedisConnection)
                .withExpirationStrategy(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(60)))
                .build();
    }
}
```

Note d'implémentation : les imports exacts (`io.github.bucket4j.distributed.proxy.ProxyManager`,
`io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager`,
`io.github.bucket4j.distributed.ExpirationAfterWriteStrategy`) correspondent à l'API Bucket4j 8.x ; à
ajuster si la version pinnée en tâche 1 diffère (vérifier la Javadoc/les release notes de la version
résolue avant d'écrire ce fichier).

### `RecherchePubliqueRateLimitFilter`

```java
package sn.samapiece.recherche.securite;

public class RecherchePubliqueRateLimitFilter extends OncePerRequestFilter {

    private static final String CHEMIN = "/api/v1/recherche-publique";
    private static final Logger LOG = LoggerFactory.getLogger(RecherchePubliqueRateLimitFilter.class);

    public record LimiteDebitReponse(String code, String message) {}

    private final ProxyManager<String> proxyManager;
    private final RateLimitingProperties proprietes;
    private final ObjectMapper objectMapper;

    public RecherchePubliqueRateLimitFilter(
            ProxyManager<String> proxyManager, RateLimitingProperties proprietes, ObjectMapper objectMapper) { ... }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod()) && CHEMIN.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = request.getRemoteAddr();
        try {
            String cle = "rate-limit:recherche-publique:" + ip;
            Bandwidth limite = Bandwidth.builder()
                    .capacity(proprietes.getCapacite())
                    .refillGreedy(proprietes.getCapacite(), Duration.ofSeconds(proprietes.getPeriodeSecondes()))
                    .build();
            Bucket bucket = proxyManager.builder().build(cle,
                    () -> BucketConfiguration.builder().addLimit(limite).build());
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            if (!probe.isConsumed()) {
                long secondes = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
                response.setStatus(429);
                response.setHeader("Retry-After", String.valueOf(secondes));
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(new LimiteDebitReponse(
                        "LIMITE_DEBIT_DEPASSEE", "Trop de requêtes, veuillez réessayer plus tard.")));
                return;
            }
        } catch (Exception e) {
            LOG.warn("Redis/Bucket4j indisponible pour le rate limiting de la recherche publique, "
                    + "requête laissée passer (fail-open)", e);
        }
        chain.doFilter(request, response);
    }
}
```

Contrat 429 (identique au wording du design) :
- Statut : `429 Too Many Requests`.
- Header : `Retry-After: <secondes>`.
- Corps : `{"code":"LIMITE_DEBIT_DEPASSEE","message":"Trop de requêtes, veuillez réessayer plus tard."}`.
- Clé Redis Bucket4j : `rate-limit:recherche-publique:{ip}` (gérée en interne par `ProxyManager`, pas de
  TTL explicite à coder — Bucket4j-Redis gère l'expiration via `ExpirationAfterWriteStrategy`).

### `EchecRechercheCounterService`

```java
package sn.samapiece.recherche.securite;

@Service
public class EchecRechercheCounterService {

    private static final String PREFIXE_CLE = "recherche-publique:echecs:";

    private final StringRedisTemplate redisTemplate;
    private final CaptchaProperties proprietes;

    public EchecRechercheCounterService(StringRedisTemplate redisTemplate, CaptchaProperties proprietes) { ... }

    // Ne catch AUCUNE exception Redis : propagée telle quelle à l'appelant (le filtre décide du
    // comportement fail-open, voir RecherchePubliqueCaptchaFilter).

    public void enregistrerEchec(String ip) {
        String cle = PREFIXE_CLE + ip;
        redisTemplate.opsForValue().increment(cle);
        redisTemplate.expire(cle, Duration.ofSeconds(proprietes.getTtlCompteurEchecsSecondes()));
    }

    public void enregistrerSucces(String ip) {
        redisTemplate.delete(PREFIXE_CLE + ip);
    }

    public boolean captchaRequis(String ip) {
        String valeur = redisTemplate.opsForValue().get(PREFIXE_CLE + ip);
        return valeur != null && Long.parseLong(valeur) >= proprietes.getSeuilEchecsConsecutifs();
    }
}
```

Clé Redis : `recherche-publique:echecs:{ip}` (String, valeur = compteur entier), TTL glissant renouvelé
(`expire(...)`) à **chaque** `enregistrerEchec`, valeur par défaut `samapiece.captcha.ttl-compteur-echecs-secondes`
(600 s). `enregistrerSucces` supprime la clé (pas de TTL à gérer).

### `CaptchaVerifier` / `DefiMathematiqueCaptchaVerifier`

```java
package sn.samapiece.recherche.securite;

public interface CaptchaVerifier {

    record DefiCaptcha(String captchaToken, String question) {}

    DefiCaptcha genererDefi();

    boolean verifier(String captchaToken, String reponseFournie);
}
```

```java
package sn.samapiece.recherche.securite;

@Component
public class DefiMathematiqueCaptchaVerifier implements CaptchaVerifier {

    private static final String PREFIXE_CLE = "captcha:defi:";
    private static final SecureRandom ALEATOIRE = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final CaptchaProperties proprietes;

    public DefiMathematiqueCaptchaVerifier(StringRedisTemplate redisTemplate, CaptchaProperties proprietes) { ... }

    @Override
    public DefiCaptcha genererDefi() {
        int a = 1 + ALEATOIRE.nextInt(20);
        int b = 1 + ALEATOIRE.nextInt(20);
        String captchaToken = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(
                PREFIXE_CLE + captchaToken, String.valueOf(a + b),
                Duration.ofSeconds(proprietes.getTtlDefiSecondes()));
        return new DefiCaptcha(captchaToken, a + " + " + b + " = ?");
    }

    @Override
    public boolean verifier(String captchaToken, String reponseFournie) {
        if (captchaToken == null || reponseFournie == null) {
            return false;
        }
        String cle = PREFIXE_CLE + captchaToken;
        String reponseAttendue = redisTemplate.opsForValue().get(cle);
        redisTemplate.delete(cle); // usage unique, y compris si la reponse est fausse
        return reponseAttendue != null && reponseAttendue.equals(reponseFournie.trim());
    }
}
```

Contrat de la question : format exact `"{a} + {b} = ?"` avec `a`,`b` entiers 1-20 — figé ici pour que le
test d'intégration puisse parser la réponse attendue par regex (`^(\d+) \+ (\d+) = \?$`). Clé Redis :
`captcha:defi:{captchaToken}` (captchaToken = UUID aléatoire), TTL `samapiece.captcha.ttl-defi-secondes`
(120 s par défaut), valeur = somme attendue en `String`, supprimée dès la première vérification (que la
réponse soit correcte ou non).

### `CaptchaRequisException` + `RecherchePubliqueExceptionHandler`

```java
package sn.samapiece.recherche;

public class CaptchaRequisException extends RuntimeException {
}
```

Ajout dans `RecherchePubliqueExceptionHandler` (fichier existant, modifié) :

```java
public record CaptchaRequisReponse(String code, String message, String captchaChallengeUrl) {}

@ExceptionHandler(CaptchaRequisException.class)
public ResponseEntity<CaptchaRequisReponse> gererCaptchaRequis(CaptchaRequisException ex) {
    return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).body(new CaptchaRequisReponse(
            "CAPTCHA_REQUIS",
            "Veuillez résoudre le CAPTCHA avant de continuer.",
            "/api/v1/recherche-publique/captcha"));
}
```

Contrat 428 :
- Statut : `428 Precondition Required` (`HttpStatus.PRECONDITION_REQUIRED`).
- Corps : `{"code":"CAPTCHA_REQUIS","message":"Veuillez résoudre le CAPTCHA avant de continuer.","captchaChallengeUrl":"/api/v1/recherche-publique/captcha"}`.

Point d'implémentation important (à figer, non explicite dans le design) : un `OncePerRequestFilter`
s'exécute **hors** du `DispatcherServlet`, donc une exception qu'il laisse remonter n'est **pas**
interceptée par un `@RestControllerAdvice`. Pour que `CaptchaRequisException` produise effectivement le
corps 428 ci-dessus depuis `RecherchePubliqueCaptchaFilter`, le filtre doit déléguer explicitement au
`HandlerExceptionResolver` composite auto-configuré par Spring MVC (bean nommé
`"handlerExceptionResolver"`, qui inclut `ExceptionHandlerExceptionResolver` et exécute donc les
méthodes `@ExceptionHandler`), plutôt que de laisser l'exception se propager nue ou d'écrire le JSON à
la main dans le filtre :

```java
@Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver
...
handlerExceptionResolver.resolveException(request, response, null, new CaptchaRequisException());
return;
```

### `CaptchaController`

```java
package sn.samapiece.recherche.web;

@RestController
@RequestMapping("/api/v1/recherche-publique/captcha")
public class CaptchaController {

    public record CaptchaDefiResponse(String captchaToken, String question) {}

    private final CaptchaVerifier captchaVerifier;

    public CaptchaController(CaptchaVerifier captchaVerifier) { ... }

    @GetMapping
    public ResponseEntity<CaptchaDefiResponse> obtenirDefi() {
        CaptchaVerifier.DefiCaptcha defi = captchaVerifier.genererDefi();
        return ResponseEntity.ok(new CaptchaDefiResponse(defi.captchaToken(), defi.question()));
    }
}
```

Endpoint : `GET /api/v1/recherche-publique/captcha`, public, statut 200, corps
`{"captchaToken":"<uuid>","question":"7 + 3 = ?"}`.

### `RecherchePubliqueCaptchaFilter`

```java
package sn.samapiece.recherche.securite;

public class RecherchePubliqueCaptchaFilter extends OncePerRequestFilter {

    private static final String CHEMIN = "/api/v1/recherche-publique";
    private static final String EN_TETE_TOKEN = "X-Captcha-Token";
    private static final String EN_TETE_REPONSE = "X-Captcha-Reponse";
    private static final Logger LOG = LoggerFactory.getLogger(RecherchePubliqueCaptchaFilter.class);

    private final EchecRechercheCounterService compteurService;
    private final CaptchaVerifier captchaVerifier;
    private final ObjectMapper objectMapper;
    private final HandlerExceptionResolver handlerExceptionResolver;

    public RecherchePubliqueCaptchaFilter(
            EchecRechercheCounterService compteurService,
            CaptchaVerifier captchaVerifier,
            ObjectMapper objectMapper,
            HandlerExceptionResolver handlerExceptionResolver) { ... }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod()) && CHEMIN.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = request.getRemoteAddr();

        boolean captchaRequis;
        try {
            captchaRequis = compteurService.captchaRequis(ip);
        } catch (Exception e) {
            LOG.warn("Redis indisponible pour le compteur d'échecs, CAPTCHA non exigé (fail-open)", e);
            captchaRequis = false;
        }

        if (captchaRequis) {
            String token = request.getHeader(EN_TETE_TOKEN);
            String reponse = request.getHeader(EN_TETE_REPONSE);
            boolean valide;
            try {
                valide = token != null && reponse != null && captchaVerifier.verifier(token, reponse);
            } catch (Exception e) {
                LOG.warn("Redis indisponible pour la vérification du CAPTCHA, requête laissée passer (fail-open)", e);
                valide = true;
            }
            if (!valide) {
                handlerExceptionResolver.resolveException(request, response, null, new CaptchaRequisException());
                return;
            }
        }

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapper);
            mettreAJourCompteur(ip, wrapper);
        } catch (Exception e) {
            if (!(e instanceof ServletException || e instanceof IOException)) {
                LOG.warn("Redis indisponible pour la mise à jour du compteur d'échecs (fail-open)", e);
            } else {
                throw e;
            }
        } finally {
            wrapper.copyBodyToResponse();
        }
    }

    private void mettreAJourCompteur(String ip, ContentCachingResponseWrapper wrapper) throws IOException {
        int statut = wrapper.getStatus();
        JsonNode corps = objectMapper.readTree(wrapper.getContentAsByteArray());
        if (statut == 200) {
            if (corps.path("trouve").asBoolean(false)) {
                compteurService.enregistrerSucces(ip);
            } else {
                compteurService.enregistrerEchec(ip);
            }
        } else if (statut == 400 && "CRITERES_INSUFFISANTS".equals(corps.path("code").asText(null))) {
            compteurService.enregistrerEchec(ip);
        }
        // Tout autre statut (429 déjà court-circuité en amont par l'autre filtre avant d'atteindre
        // celui-ci, 5xx imprévu) : ni échec ni succès, compteur inchangé.
    }
}
```

Contrat des en-têtes (transport CAPTCHA, jamais dans le corps JSON de #18) :
- `X-Captcha-Token` : le `captchaToken` reçu de `GET /captcha`.
- `X-Captcha-Reponse` : la réponse numérique du citoyen à la question, en texte brut.

### `SecurityConfig`

```java
@Bean
public SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        JwtService jwtService,
        ProxyManager<String> bucket4jProxyManager,
        RateLimitingProperties rateLimitingProperties,
        EchecRechercheCounterService echecRechercheCounterService,
        CaptchaVerifier captchaVerifier,
        ObjectMapper objectMapper,
        @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver) throws Exception {

    JwtAuthenticationFilter jwtAuthenticationFilter = new JwtAuthenticationFilter(jwtService);
    RecherchePubliqueCaptchaFilter captchaFilter = new RecherchePubliqueCaptchaFilter(
            echecRechercheCounterService, captchaVerifier, objectMapper, handlerExceptionResolver);
    RecherchePubliqueRateLimitFilter rateLimitFilter = new RecherchePubliqueRateLimitFilter(
            bucket4jProxyManager, rateLimitingProperties, objectMapper);

    http.csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(exceptions ->
                exceptions.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/postes").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/recherche-publique").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/recherche-publique/captcha").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                .anyRequest().authenticated())
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(captchaFilter, JwtAuthenticationFilter.class)
        .addFilterBefore(rateLimitFilter, RecherchePubliqueCaptchaFilter.class);
    return http.build();
}
```

Ordre de chaîne obtenu : `RecherchePubliqueRateLimitFilter` → `RecherchePubliqueCaptchaFilter` →
`JwtAuthenticationFilter` → `UsernamePasswordAuthenticationFilter` → ... (rate limit d'abord, cohérent
avec le design : une requête qui épuise le bucket ne doit même pas atteindre la logique CAPTCHA).

## Plan de tests

| Critère d'acceptation / comportement attendu | Test |
|---|---|
| Rate limiting par IP avec 429 explicite au-delà du seuil (**exigé**) | `RecherchePubliqueRateLimitingIntegrationTest` — Testcontainers Redis (`redis:7-alpine`) en plus de Postgres/Meilisearch existants ; `@DynamicPropertySource` pour `spring.data.redis.host/port` **et** pour abaisser `samapiece.rate-limiting.recherche-publique.capacite` (ex. 3) tout en relevant `samapiece.captcha.seuil-echecs-consecutifs` à une valeur élevée (ex. 1000) pour isoler le rate limiting du CAPTCHA ; simulate IP fixe via `.with(request -> { request.setRemoteAddr("1.2.3.4"); return request; })` ; envoyer `capacite` requêtes (200/400 attendus), puis une requête supplémentaire → assert statut 429, corps `code = "LIMITE_DEBIT_DEPASSEE"`, header `Retry-After` présent et parseable en entier positif. |
| CAPTCHA requis après N échecs consécutifs depuis la même origine (**exigé**, non couvert par un test automatisé explicite dans le ticket mais dérivé du design) | `RecherchePubliqueCaptchaIntegrationTest` — même patron Testcontainers Redis ; `@DynamicPropertySource` pour abaisser `samapiece.captcha.seuil-echecs-consecutifs` (ex. 2) et relever `samapiece.rate-limiting.recherche-publique.capacite` (ex. 1000) pour isoler le CAPTCHA du rate limiting ; même IP simulée fixe ; provoquer N réponses "échec" (mélange 400 `CRITERES_INSUFFISANTS` et 200 `trouve=false`, cf. définition exacte du design) puis une requête supplémentaire sans en-têtes CAPTCHA → assert statut 428, corps `code = "CAPTCHA_REQUIS"` et `captchaChallengeUrl = "/api/v1/recherche-publique/captcha"`. |
| Résolution du CAPTCHA débloque la requête suivante | Même test : `GET /api/v1/recherche-publique/captcha` → 200, extraire `a`/`b` de `question` par regex `^(\d+) \+ (\d+) = \?$` ; rejouer la requête de recherche avec `X-Captcha-Token`/`X-Captcha-Reponse = a+b` en en-têtes → assert que la requête n'est plus bloquée par un 428 (statut 200 ou 400 selon les critères envoyés, jamais 428). |
| Succès (`trouve=true`) réinitialise le compteur d'échecs | Dans `RecherchePubliqueCaptchaIntegrationTest` : atteindre le seuil (N-1 échecs), résoudre le CAPTCHA avec une recherche qui aboutit à `trouve=true` (pièce créée au préalable comme dans `RecherchePubliqueIntegrationTest`), puis renvoyer une requête `criteres insuffisants` (400) immédiatement après sans en-têtes CAPTCHA → assert statut 400 (pas 428), preuve que le compteur a été remis à zéro par le succès. |
| Requête bloquée en amont (429 ou 428) non comptée comme échec/succès | Dans `RecherchePubliqueCaptchaIntegrationTest` : après avoir déclenché un 428, renvoyer immédiatement une requête avec CAPTCHA valide et critères insuffisants (400, un échec réel) ; vérifier que seul cet échec réel a été compté (ex. en calibrant le seuil pour que N-1 échecs "vrais" + le 428 lui-même ne déclenchent pas un second 428 avant le Nème vrai échec). Alternative plus simple si la calibration precise s'avère fragile : test unitaire Mockito sur `RecherchePubliqueCaptchaFilter` isolé, vérifiant que `EchecRechercheCounterService.enregistrerEchec/Succes` ne sont jamais invoqués lorsque le filtre court-circuite avant `chain.doFilter(...)`. |
| Fail-open des deux contrôles si Redis indisponible | Pas de nouveau test dédié : réexécuter sans modification `RecherchePubliqueIntegrationTest` et `RecherchePubliqueMeilisearchIndisponibleIntegrationTest` (aucun conteneur Redis démarré dans ces classes) et vérifier qu'ils restent verts — la connexion Redis étant paresseuse (Lettuce, `validateConnection=false` par défaut) et les deux filtres explicitement try/catch, l'absence de Redis ne doit produire ni 500 ni blocage. À consigner comme vérification manuelle/CI dans la PR, pas comme fichier de test séparé. |
| Filtres scopés uniquement à `POST /api/v1/recherche-publique` (pas de régression sur `GET /api/v1/postes`, `/api/v1/auth/login`, routes agent) | Couvert implicitement par la suite `@SpringBootTest` existante (login, CRUD agents, etc.) qui reste verte sans modification (tâche 18) — `shouldNotFilter` exclut toute autre route/méthode. |
| `RateLimitingProperties`/`CaptchaProperties`/`EchecRechercheCounterService`/`DefiMathematiqueCaptchaVerifier` — logique unitaire (calcul du seuil, TTL glissant, usage unique du défi) | Tests unitaires JUnit/Mockito optionnels mais recommandés, ex. `EchecRechercheCounterServiceTest` avec un `StringRedisTemplate` mocké (vérifier `expire(...)` appelé à chaque `enregistrerEchec`, `delete(...)` appelé par `enregistrerSucces`) ; `DefiMathematiqueCaptchaVerifierTest` (vérifier que `verifier(...)` renvoie `false` et supprime la clé même en cas de mauvaise réponse — usage unique). Non explicitement exigés par le ticket, à la discrétion du codeur/reviewer. |

## Écarts identifiés

1. **Ambiguïté de mécanisme pour le 428** : le design demande à la fois que le filtre "écrit
   directement" le 428 et l'introduction d'une `CaptchaRequisException` + `@ExceptionHandler` (patron
   normalement réservé aux exceptions levées depuis un contrôleur, donc dans le périmètre du
   `DispatcherServlet`). Un `OncePerRequestFilter` s'exécute hors de ce périmètre : une exception qu'il
   laisse remonter nue n'atteint jamais un `@RestControllerAdvice`. Résolu ci-dessus (section Contrat
   technique, `RecherchePubliqueCaptchaFilter`) par délégation explicite au bean
   `HandlerExceptionResolver` auto-configuré — à valider par le codeur/reviewer, c'est la seule façon de
   satisfaire littéralement les deux exigences du design sans les rendre incompatibles.
2. **Version Maven de `bucket4j-redis` non fixée par le design** : celui-ci nomme l'artefact
   (`com.bucket4j:bucket4j-redis`) mais aucune version, et cette dépendance n'est pas gérée par le BOM
   Spring Boot 3.3.13. Signalé en tâche 1 comme vérification obligatoire avant de coder
   `RedisRateLimiterConfig`, plutôt que de figer ici un numéro de version non vérifiable sans accès à
   Maven Central au moment de la rédaction de cette spec.
3. **Second critère d'acceptation (CAPTCHA) sans test automatisé explicitement exigé** : le ticket ne
   cite littéralement que le déclenchement du 429 comme test d'intégration obligatoire. Le design
   qualifie le test CAPTCHA de "souhaitable". Cette spec le traite néanmoins comme couvrant un critère
   d'acceptation réel (tâche 17, `RecherchePubliqueCaptchaIntegrationTest`) — à ne pas sauter sans
   validation explicite du product owner/reviewer, sous peine de livrer un critère d'acceptation non
   vérifié automatiquement.
