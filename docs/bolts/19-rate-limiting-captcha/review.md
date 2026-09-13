# Review — #19 Rate limiting + CAPTCHA sur la recherche publique

CHANGES_REQUESTED

## Criteres d'acceptation

| # | Critere | Statut |
|---|---|---|
| 1 | Rate limiting par IP (Bucket4j + Redis) sur POST /api/v1/recherche-publique, 429 explicite au-dela du seuil | Couvert - RecherchePubliqueRateLimitFilter + RedisRateLimiterConfig conformes au contrat (statut 429, header Retry-After, corps LIMITE_DEBIT_DEPASSEE), teste par RecherchePubliqueRateLimitingIntegrationTest (non executable dans cet environnement, voir Build/tests) |
| 2 | CAPTCHA requis apres N echecs consecutifs depuis la meme origine | Couvert pour les cas nominaux (200 trouve=false, 400 CRITERES_INSUFFISANTS), teste par RecherchePubliqueCaptchaIntegrationTest ; mais le filtre porteur de cette logique contient un bug qui masque toute erreur applicative imprevue sur l'endpoint protege - voir Finding 1, bloquant |
| 3 | Test d'integration declenchant le 429 apres depassement du seuil | Couvert - RecherchePubliqueRateLimitingIntegrationTest.depassementDuSeuil_devraitRenvoyer429AvecCorpsEtRetryAfter correspond au plan de tests de la spec |

## Findings

### 1. [Bloquant] RecherchePubliqueCaptchaFilter avale silencieusement toute exception applicative du controleur, pas seulement les pannes Redis

Fichier: backend/src/main/java/sn/samapiece/recherche/securite/RecherchePubliqueCaptchaFilter.java lignes 74-86 :

```java
ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
try {
    chain.doFilter(request, wrapper);
    mettreAJourCompteur(ip, wrapper);
} catch (Exception e) {
    if (!(e instanceof ServletException || e instanceof IOException)) {
        LOG.warn("Redis indisponible pour la mise a jour du compteur d'echecs (fail-open)", e);
    } else {
        throw e;
    }
} finally {
    wrapper.copyBodyToResponse();
}
```

`chain.doFilter(request, wrapper)` est a l'interieur du try, alors que seul `mettreAJourCompteur(...)` est cense echouer pour cause de panne Redis. Toute exception non-ServletException/non-IOException levee n'importe ou en aval (le DispatcherServlet, le controleur RecherchePubliqueController, un bug applicatif quelconque - NPE, IllegalStateException, etc.) est interceptee ici, loguee a tort comme "Redis indisponible" (WARN au lieu d'ERROR), puis avalee. Le finally recopie ensuite un ContentCachingResponseWrapper dont le statut n'a jamais ete positionne (defaut servlet = 200) et dont le corps est vide, vers la vraie reponse HTTP: le citoyen recoit donc un 200 OK avec un corps vide au lieu du 500 que Spring aurait normalement produit, et l'erreur reelle disparait sans jamais atteindre les logs ERROR ni le monitoring.

Scenario reproduit et verifie empiriquement (test Mockito jetable, execute puis supprime, non livre dans le diff): un FilterChain qui leve `new IllegalStateException("bug applicatif ... rien a voir avec Redis")` produit en sortie `STATUT_FINAL=200` et `CORPS_FINAL=[]` - confirmant que le filtre masque un bug qui n'a strictement rien a voir avec la disponibilite de Redis.

Comparer avec RecherchePubliqueRateLimitFilter (RecherchePubliqueRateLimitFilter.java lignes 46-68), qui fait cela correctement: `chain.doFilter(request, response)` est appele en dehors du try/catch, donc une erreur applicative y remonte normalement.

Correctif attendu: ne wrapper que `mettreAJourCompteur(ip, wrapper)` dans le try/catch fail-open, et laisser `chain.doFilter(request, wrapper)` hors de ce try (ou dans un try separe qui rethrow tout, finally garde pour copyBodyToResponse()). Note: ce defaut provient du contrat technique propose tel quel dans spec.md (section RecherchePubliqueCaptchaFilter, lignes 458-471) - la spec elle-meme doit etre corrigee en meme temps que le code, sinon un futur bolt la recopiera telle quelle.

Impact: les criteres d'acceptation "chemin heureux" passent malgre tout, mais il s'agit d'un bug de fiabilite/observabilite reel et verifie sur l'endpoint public le plus expose du produit (recherche sans authentification) - a corriger avant merge.

### 2. [Mineur, non bloquant] GET /api/v1/recherche-publique/captcha n'est protege par aucun des deux filtres

`shouldNotFilter` des deux filtres compare l'URI a l'exact "/api/v1/recherche-publique" (RecherchePubliqueRateLimitFilter.java lignes 38-40, RecherchePubliqueCaptchaFilter.java lignes 41-43), donc l'endpoint de generation de defi n'est ni rate-limite ni compte. Conforme a la spec (qui ne l'exige pas) et au perimetre du ticket, mais un attaquant peut appeler cet endpoint a volonte pour preparer des reponses valides avant un brute force sur /recherche-publique. A signaler pour un futur ticket, pas bloquant ici.

## Verifications positives (pas de regression detectee)

- Ordre des filtres dans SecurityConfig: rate-limit -> captcha -> JWT, conforme a la spec (SecurityConfig.java lignes 76-78).
- Fail-open reel et teste (RecherchePubliqueRateLimitFilter, RecherchePubliqueCaptchaFilterTest.erreurRedisSurCaptchaRequis_devraitLaisserPasserFailOpen) pour les scenarios ou Redis est reellement en panne cote compteur/CAPTCHA/rate-limit eux-memes (le bug du Finding 1 est distinct: il masque aussi des erreurs qui n'ont rien a voir avec Redis).
- EchecRechercheCounterService ne catch aucune exception Redis en interne - conforme a la spec, verifie par lecture du fichier.
- Le mecanisme 428 delegue bien au bean HandlerExceptionResolver (RecherchePubliqueCaptchaFilter.java ligne 69), pas d'ecriture JSON manuelle ni de propagation nue - CaptchaRequisException produit bien le 428 via RecherchePubliqueExceptionHandler.
- Correctif @Lazy (commit d53a7d5) verifie comme suffisant: @Lazy sur la classe @Configuration RedisRateLimiterConfig retarde bien tous ses @Bean (Spring applique le marqueur @Lazy d'une classe @Configuration a l'ensemble de ses methodes @Bean), et le @Lazy supplementaire sur le parametre ProxyManager<String> de SecurityConfig.securityFilterChain est necessaire en plus (sans lui, la resolution de ce parametre pour l'appel de la methode @Bean securityFilterChain forcerait quand meme la creation anticipee de la dependance, lazy-init ou non). RedisRateLimiterConfigLazyStartupTest teste effectivement ce scenario exact (demarrage de contexte avec Redis injoignable ne doit pas echouer, et l'usage reel du ProxyManager echoue bien lui, prouvant que la paresse est reelle).
- Corps/statuts 429 et 428 exactement conformes au contrat (LIMITE_DEBIT_DEPASSEE + Retry-After ; CAPTCHA_REQUIS + captchaChallengeUrl).
- Numero de document deja hache en amont (NumeroDocumentHasher, herite de #18, non modifie par ce ticket) - aucune regression sur le paragraphe 10 de PROJET-SAMAPIECE.md ; les nouvelles cles Redis introduites par #19 (compteur d'echecs par IP, defi CAPTCHA) ne stockent aucune donnee personnelle sensible en clair.
- Aucune migration Flyway ajoutee (ticket n'en necessite pas).
- docker-compose.yml/application-dev.yml/pom.xml conformes au contrat technique de la spec (service redis deja present sur main avant ce ticket, seules les variables d'env et la dependance manquantes ont ete ajoutees).
- Tests unitaires nouveaux (EchecRechercheCounterServiceTest, DefiMathematiqueCaptchaVerifierTest, RecherchePubliqueCaptchaFilterTest, RedisRateLimiterConfigLazyStartupTest) executes avec succes, logique correcte (TTL glissant, usage unique du defi, court-circuit sans mise a jour du compteur).
- Piege piece_sequence/UTF-8 deja rencontre sur ce projet: correctement anticipe dans les deux nouveaux tests d'integration (jdbcTemplate.update("DELETE FROM piece_sequence") en @BeforeEach, getContentAsString(StandardCharsets.UTF_8) partout).

## Build/tests

- `mvn -pl backend -am test -Dtest=EchecRechercheCounterServiceTest,DefiMathematiqueCaptchaVerifierTest,RecherchePubliqueCaptchaFilterTest,RedisRateLimiterConfigLazyStartupTest` -> BUILD SUCCESS, 18/18 tests passes.
- `mvn -pl backend -am test` (suite complete) -> BUILD FAILURE, mais causee uniquement par une incompatibilite documentee Testcontainers/Docker Desktop sur Windows (Could not find a valid Docker environment / Status 400 sur docker info via npipe), touchant de facon identique et indiscriminee les 14 classes @SpringBootTest avec Testcontainers, aussi bien preexistantes (AgentIntegrationTest, AuthIntegrationTest, PieceIntegrationTest, PosteIntegrationTest, SamaPieceApplicationTests, etc.) que nouvelles (RecherchePubliqueRateLimitingIntegrationTest, RecherchePubliqueCaptchaIntegrationTest). Resultat detaille: Tests run: 103, Failures: 0, Errors: 14 - les 89 tests restants (sans Testcontainers) passent tous, confirmant l'affirmation du codeur et l'absence de regression introduite par ce ticket sur la suite existante. Limitation d'environnement pure, pas un signal sur la qualite du code de ce ticket ; les deux nouveaux fichiers Testcontainers (141 + 304 lignes) ont donc ete relus ligne a ligne a la place d'une execution reelle, et sont conformes au plan de tests de la spec.
- Pas de changement frontend dans ce diff (git diff main..HEAD --stat -- frontend vide) -> pas de npm run build/npm test necessaires.

## Conclusion

Implementation globalement fidele a la spec, correctif @Lazy bien verifie et suffisant, bonne couverture de tests unitaires, tests d'integration Testcontainers bien concus (relecture stricte faite faute de Docker fonctionnel dans cet environnement). Le point bloquant est le Finding 1: RecherchePubliqueCaptchaFilter doit restreindre son try/catch fail-open au seul appel mettreAJourCompteur(...), sinon un bug applicatif quelconque sur l'endpoint public de recherche devient un 200 OK vide silencieux au lieu d'un 500 visible - inacceptable sur l'endpoint le plus expose du produit. A corriger (et a repercuter dans spec.md) avant nouvelle revue.
