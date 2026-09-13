# Design — #19 Rate limiting + CAPTCHA sur la recherche publique

## Approche

Deux controles independants, tous deux scopes au seul `POST /api/v1/recherche-publique` (endpoint
livre par #18, `permitAll()` dans `SecurityConfig`) : (1) un rate limiting distribue par IP via
Bucket4j + Redis (le service Redis existe deja dans `docker-compose.yml` depuis #3 mais n'est
consomme par aucun code backend a ce jour) ; (2) un compteur d'echecs consecutifs par IP, egalement
dans Redis, qui declenche l'exigence d'un CAPTCHA une fois un seuil atteint.

Conformement a la section 11.4 du PROJET-SAMAPIECE.md (Cache et rate limiting : Redis via Spring Data
Redis / Bucket4j), ce choix suit la stack deja actee au niveau architecture.

Point critique tranche ici : un vrai CAPTCHA tiers (reCAPTCHA/hCaptcha) demande une cle API externe,
un appel reseau sortant, et surtout un widget JS cote frontend, qui n'existe pas encore (le formulaire
de recherche citoyenne est le sujet du futur #20, pas fait). Le critere d'acceptation est rempli par
un defi mathematique simple backend-only, expose via un endpoint dedie et verifie via une interface
CaptchaVerifier pluggable.

Decision de resilience : les deux controles fail-open si Redis est indisponible (requete laissee
passer, warning logue) plutot que fail-closed (500 ou blocage total) — coherent avec le precedent #18
(repli PostgreSQL si Meilisearch tombe) et avec le fait que Redis est qualifie de cache dans le
diagramme d'architecture (section 11.2), pas de source de verite.

## Fichiers/modules impactes

Nouveaux fichiers (package sn.samapiece.recherche.securite, n'existe pas encore) :
- RateLimitingProperties.java / CaptchaProperties.java (ou un seul
  RecherchePubliqueSecuriteProperties) — capacite/periode du bucket, seuil d'echecs consecutifs, TTL
  du defi et du compteur, tous configurables via application.yml (valeurs par defaut raisonnables
  pour le pilote, ex. 10 requetes/60s, seuil de 5 echecs consecutifs).
- RedisRateLimiterConfig.java — bean ProxyManager<String> (Bucket4j-Redis, client Lettuce) pour les
  buckets distribues, distinct du RedisConnectionFactory autoconfigure par
  spring-boot-starter-data-redis (utilise, lui, pour le compteur d'echecs et le defi CAPTCHA via
  StringRedisTemplate).
- RecherchePubliqueRateLimitFilter.java (extends OncePerRequestFilter, meme patron que
  sn.samapiece.iam.jwt.JwtAuthenticationFilter) — actif uniquement sur POST
  /api/v1/recherche-publique (shouldNotFilter sur les autres routes/methodes), consomme un jeton du
  bucket Redis cle par IP ; si epuise, ecrit directement la reponse 429 (JSON) et court-circuite la
  chaine ; si Redis est indisponible, log WARN et laisse passer (fail-open).
- EchecRechercheCounterService.java — encapsule Redis (StringRedisTemplate, cle
  recherche-publique:echecs:{ip}, TTL glissant renouvele a chaque echec) : enregistrerEchec(ip),
  enregistrerSucces(ip) (suppression de la cle), captchaRequis(ip) (compteur >= seuil). Fail-open si
  Redis indisponible (captchaRequis renvoie false).
- CaptchaVerifier.java (interface) — DefiCaptcha genererDefi(), boolean verifier(String captchaToken,
  String reponseFournie).
- DefiMathematiqueCaptchaVerifier.java — implementation par defaut : addition de deux entiers
  aleatoires, reponse attendue stockee dans Redis sous une cle aleatoire (captcha:defi:{id}, TTL
  120s), lue-et-supprimee (a usage unique) a la verification.
- RecherchePubliqueCaptchaFilter.java (extends OncePerRequestFilter) — meme perimetre que le filtre
  precedent ; avant d'appeler la chaine, si captchaRequis(ip) et que les en-tetes X-Captcha-Token /
  X-Captcha-Reponse sont absents ou invalides, ecrit 428 (CAPTCHA_REQUIS) et court-circuite ; sinon
  appelle la chaine avec une reponse enveloppee (ContentCachingResponseWrapper) puis, apres coup,
  inspecte le statut/corps produit par le controleur pour incrementer ou reinitialiser le compteur
  d'echecs (voir Decisions cles pour la definition exacte d'un echec).
- sn/samapiece/recherche/CaptchaRequisException.java + ajout d'un @ExceptionHandler (nouveau, ou
  extension de RecherchePubliqueExceptionHandler) mappant vers 428 avec un corps {code, message,
  captchaChallengeUrl}.
- sn/samapiece/recherche/web/CaptchaController.java — GET /api/v1/recherche-publique/captcha (public,
  hors des deux filtres ci-dessus) renvoyant {captchaToken, question}.

Fichiers modifies :
- backend/pom.xml — ajout de spring-boot-starter-data-redis (client Lettuce, RedisConnectionFactory
  autoconfigure via spring.data.redis.host/port) et com.bucket4j:bucket4j-redis (module Lettuce de
  Bucket4j). Aucune de ces deux dependances n'existe actuellement (verifie : absentes du pom).
- backend/src/main/resources/application.yml / application-dev.yml — spring.data.redis.host,
  spring.data.redis.port, et les proprietes samapiece.rate-limiting.* / samapiece.captcha.*.
- docker-compose.yml — le service redis existe deja mais aucune variable REDIS_HOST/REDIS_PORT n'est
  aujourd'hui injectee dans le service backend (verifie : absent du bloc environment: contrairement a
  MINIO_ENDPOINT qui suit le meme besoin) ; a ajouter, meme patron que MINIO_ENDPOINT:
  http://minio:9000 (valeurs internes fixes, ex. REDIS_HOST: redis, REDIS_PORT: 6379). .env.example
  n'a pas besoin de changement (seul REDIS_HOST_PORT, pour le mapping de port hote, y est deja
  present).
- backend/src/main/java/sn/samapiece/config/SecurityConfig.java — enregistrement des deux nouveaux
  filtres via addFilterBefore(...) (avant JwtAuthenticationFilter, ordre : rate limit puis captcha) et
  ajout de GET /api/v1/recherche-publique/captcha a la liste permitAll().

Tests :
- backend/src/test/java/sn/samapiece/recherche/web/RecherchePubliqueRateLimitingIntegrationTest.java
  (nouveau, exige par le critere d'acceptation) — @Testcontainers avec un GenericContainer Redis
  (redis:7-alpine, meme image que docker-compose.yml) en plus des containers Postgres/Meilisearch deja
  utilises par RecherchePubliqueIntegrationTest, @DynamicPropertySource pour
  spring.data.redis.host/port, seuil abaisse via propriete de test pour rendre le test rapide ;
  scenario : N+1 requetes depuis la meme IP simulee (MockMvc ne permet pas de forcer getRemoteAddr()
  directement, utiliser .with(request -> { request.setRemoteAddr("1.2.3.4"); return request; }))
  attendent 200/400 puis 429 avec le code LIMITE_DEBIT_DEPASSEE.
- Un test dedie au CAPTCHA (RecherchePubliqueCaptchaIntegrationTest, souhaitable mais non
  explicitement exige par le critere d'acceptation qui ne cite que le 429) verifiant : N echecs
  consecutifs -> 428 sans jeton, resolution du defi via GET /captcha -> requete suivante acceptee.
- Aucune modification necessaire aux tests existants (RecherchePubliqueIntegrationTest,
  RecherchePubliqueMeilisearchIndisponibleIntegrationTest) : ils ne demarrent pas de container Redis
  et beneficient du fail-open (Redis injoignable, filtres transparents), donc restent verts sans
  changement.

## Decisions cles

- Cle du bucket : IP cliente brute, getRemoteAddr() (voir Risques pour la question du proxy). Cle
  Redis Bucket4j : rate-limit:recherche-publique:{ip}.
- Taux retenu : capacite 10 requetes, refill greedy de 10 jetons toutes les 60 secondes (configurable,
  pas de valeur figee en dur dans le code) — dimensionne pour un usage citoyen normal (quelques
  recherches par minute) tout en bornant un scraping brutal ; a ajuster en fonctionnement reel.
- Definition exacte d'une tentative infructueuse (necessaire pour le compteur CAPTCHA) : une reponse
  HTTP 200 avec trouve = false, ou une reponse HTTP 400 CRITERES_INSUFFISANTS. Les deux traduisent une
  requete qui n'a rien produit d'exploitable pour un citoyen legitime et sont, a l'inverse, exactement
  ce que produit une energie d'enumeration/scraping en boucle. Une reponse HTTP 200 avec trouve = true
  est un succes et reinitialise le compteur (supprime la cle Redis) — une recherche legitime qui
  aboutit ne doit pas laisser un residu d'echecs anterieurs peser sur l'utilisateur suivant partageant
  la meme IP (ex. plusieurs citoyens dans un cybercafe ou derriere le meme NAT mobile). Une requete
  bloquee en amont (429 rate limit, 428 captcha requis) n'est jamais comptee comme un echec ou un
  succes : le controleur n'a pas ete atteint.
- Consecutif : compteur Redis avec TTL glissant (renouvele a chaque increment, ex. 10 minutes) plutot
  que fenetre fixe — une IP qui echoue, s'arrete 10 minutes, puis reessaie repart de zero ; une IP qui
  echoue en continu voit son compteur ne jamais expirer tant qu'elle ne reussit pas ou ne s'arrete pas
  assez longtemps.
- CAPTCHA simplifie backend-only : interface CaptchaVerifier + implementation par defaut defi
  mathematique (addition simple), reponse attendue stockee server-side dans Redis (cle aleatoire, TTL
  120s, usage unique), plutot qu'un jeton signe HMAC — evite d'introduire un nouveau secret de
  configuration pour un mecanisme explicitement provisoire. Transport : en-tetes HTTP dedies
  (X-Captcha-Token, X-Captcha-Reponse) plutot qu'extension du DTO RecherchePubliqueRequest livre par
  #18 — garde le contrat JSON de #18 intact pour l'immense majorite des requetes (celles sous le
  seuil, qui ne fournissent jamais ces en-tetes).
- Contrat 429 : corps JSON {code: "LIMITE_DEBIT_DEPASSEE", message: "..."}, header Retry-After
  (secondes avant reinitialisation partielle du bucket) en plus du statut 429.
- Contrat 428 (CAPTCHA requis) : choix de 428 Precondition Required (RFC 6585) plutot que 403, plus
  semantiquement precis (il manque une condition prealable a satisfaire, pas acces refuse
  definitivement) ; corps {code: "CAPTCHA_REQUIS", message: "...", captchaChallengeUrl:
  "/api/v1/recherche-publique/captcha"}.
- Panne Redis = fail-open des deux controles, pas fail-closed : voir Approche. Implique un try/catch
  explicite autour de chaque appel Redis/Bucket4j dans les deux filtres (le comportement par defaut
  d'une exception de connexion non interceptee serait un 500, donc fail-closed par accident si on ne
  le code pas explicitement).
- RBAC : aucun changement de perimetre d'autorisation — les deux nouveaux filtres et le nouvel
  endpoint GET /api/v1/recherche-publique/captcha restent publics (permitAll()), coherent avec
  l'endpoint qu'ils protegent.

## Risques / points d'attention

- Fiabilite de l'IP cliente : getRemoteAddr() est fiable uniquement parce qu'aucun reverse proxy ne se
  trouve aujourd'hui devant le backend dans docker-compose.yml (le frontend nginx sert uniquement le
  SPA statique, il ne proxifie pas /api/* — verifie dans frontend/nginx.conf, le frontend appelle le
  backend directement sur son propre port). Ne pas faire confiance a X-Forwarded-For dans ce ticket :
  sans proxy de confiance en amont pour le poser/l'ecraser, n'importe quel client pourrait le forger
  pour contourner le rate limiting (rotation d'IP falsifiees). A revisiter explicitement le jour ou un
  reverse proxy/WAF (section 10.4) est introduit en prod (necessitera ForwardedHeaderFilter + un
  filtrage reseau garantissant que le backend n'est joignable que via ce proxy).
- Casse des tests existants : introduire Redis comme dependance globale de l'application risquait de
  faire echouer tous les @SpringBootTest existants (login, CRUD agents, etc.) si la connexion Redis
  etait etablie de facon eager au demarrage du contexte. LettuceConnectionFactory ne se connecte pas
  eagerement par defaut (validateConnection=false) — combine au fail-open explicite des deux filtres,
  aucun test existant n'a besoin d'un container Redis. A verifier neanmoins a l'implementation (ne pas
  activer validateConnection ni tout autre appel Redis eager dans un @PostConstruct/CommandLineRunner).
- TTL et fenetre consecutive : un TTL glissant trop long retarde le retour a la normale pour une IP
  partagee (NAT/cybercafe) apres une periode d'echecs legitimes (ex. plusieurs citoyens qui se
  trompent de nom) ; trop court, un attaquant patient (une requete toutes les 9 minutes) ne declenche
  jamais le CAPTCHA. Valeur de depart (10 min) a ajuster en usage reel, pas de garantie theorique.
- CAPTCHA simplifie n'est pas une vraie protection anti-bot : un script peut resoudre l'addition
  trivialement ; la protection reelle contre le scraping automatise repose sur le rate limiting
  Bucket4j, pas sur ce CAPTCHA. A documenter clairement pour ne pas donner un faux sentiment de
  securite au moment de la revue/deploiement pilote.
- Minimisation des donnees : l'IP cliente est une donnee a caractere personnel ; elle ne doit etre
  conservee dans Redis que pour la duree du TTL de rate limiting/compteur (pas de journalisation
  permanente de l'IP associee a une recherche dans ce ticket), et jamais correlee au contenu de la
  recherche (nom/numero) dans les logs applicatifs (meme exigence deja posee par #18).
- Offline-first / PWA agent : aucun impact — ces filtres ne s'appliquent qu'a l'endpoint public
  citoyen, jamais aux routes de l'app agent.
- Endpoint GET /captcha non lui-meme rate-limite : un attaquant pourrait generer des defis en boucle
  pour epuiser Redis (memoire) sans jamais les resoudre ; attenue par le TTL court (120s) mais pas
  explicitement protege par un rate limiting dedie dans ce ticket (risque mineur, volume pilote).

## Hors perimetre

- Integration d'un vrai fournisseur CAPTCHA externe (reCAPTCHA/hCaptcha) — prevu pour une evolution
  ulterieure une fois le frontend de recherche citoyenne (#20) livre ; ce ticket pose seulement
  l'interface CaptchaVerifier et une implementation de pilotage.
- Widget CAPTCHA cote frontend / formulaire de recherche citoyenne (#20, pas encore existant).
- Reverse proxy, WAF, ou toute configuration X-Forwarded-For de confiance (section 10.4,
  infrastructure hors perimetre applicatif de ce ticket).
- Rate limiting ou CAPTCHA sur d'autres endpoints publics (ex. GET /api/v1/postes) — perimetre
  strictement POST /api/v1/recherche-publique (et son nouvel endpoint satellite GET .../captcha).
- Verrouillage de compte agent apres tentatives infructueuses (section 10.3, authentification forte
  des agents) — sujet distinct, concerne le portail agent, pas le portail citoyen public.
- Toute modification du contrat RecherchePubliqueRequest/RecherchePubliqueResponse livre par #18 (le
  CAPTCHA transite par en-tetes HTTP dedies, pas par le corps JSON existant).
