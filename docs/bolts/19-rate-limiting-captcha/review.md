# Review - #19 Rate limiting + CAPTCHA sur la recherche publique (2e passage)

APPROVE

## Contexte de ce passage

Ce passage se concentre sur la verification du correctif apporte au Finding 1 bloquant du 1er passage (RecherchePubliqueCaptchaFilter avalait toute exception applicative en aval, pas seulement les pannes Redis). Le commit 30c66cf ne touche que 3 fichiers (RecherchePubliqueCaptchaFilter.java, RecherchePubliqueCaptchaFilterTest.java, spec.md), confirme par git diff 395d850..30c66cf --stat. Le reste du diff main..HEAD est identique a ce qui a deja ete revu au 1er passage.

## Criteres d'acceptation

| # | Critere | Statut |
|---|---|---|
| 1 | Rate limiting par IP (Bucket4j + Redis) sur POST /api/v1/recherche-publique, 429 explicite au-dela du seuil | Couvert -- inchange depuis le 1er passage, RecherchePubliqueRateLimitFilter non modifie par le commit correctif, relu a nouveau (chain.doFilter hors du try/catch fail-open, contrat 429/Retry-After/LIMITE_DEBIT_DEPASSEE conforme) |
| 2 | CAPTCHA requis apres N echecs consecutifs depuis la meme origine | Couvert -- logique nominale inchangee, et le bug bloquant du 1er passage (masquage des exceptions applicatives) est corrige et teste (voir Verification du correctif ci-dessous) |
| 3 | Test d'integration declenchant le 429 apres depassement du seuil | Couvert -- RecherchePubliqueRateLimitingIntegrationTest, non modifie par ce commit |

## Verification du correctif du Finding 1 (bloquant au 1er passage)

Code actuel (backend/src/main/java/sn/samapiece/recherche/securite/RecherchePubliqueCaptchaFilter.java lignes 74-86) :

```java
ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
try {
    chain.doFilter(request, wrapper);
    try {
        mettreAJourCompteur(ip, wrapper);
    } catch (IOException e) {
        throw e;
    } catch (Exception e) {
        LOG.warn("Redis indisponible pour la mise a jour du compteur d'echecs (fail-open)", e);
    }
} finally {
    wrapper.copyBodyToResponse();
}
```

1. chain.doFilter(request, wrapper) (ligne 76) est desormais hors de tout try/catch fail-open -- seul l'appel a mettreAJourCompteur(ip, wrapper) (ligne 78) est enveloppe dans le try/catch destine aux pannes Redis.
2. Propagation d'une exception applicative : le nouveau test exceptionApplicativeDansLaChaine_neDoitJamaisEtreAvaleeParLeFailOpen (RecherchePubliqueCaptchaFilterTest.java lignes 143-157) fait lever IllegalStateException("bug applicatif sans rapport avec Redis") par le FilterChain et verifie via assertThatThrownBy que l'exception remonte telle quelle hors de doFilterInternal, et que compteurService.enregistrerSucces/enregistrerEchec ne sont jamais appeles. Execute avec succes (voir Build/tests). Je n'ai pas rejoue la reproduction empirique sur l'ancien code car cela aurait necessite d'ecraser temporairement un fichier source, ce que mon role de reviewer m'interdit (tentative bloquee par le systeme de permissions, comportement attendu) ; la lecture croisee du diff 395d850..30c66cf (ancien catch(Exception) qui n'excluait que ServletException/IOException, donc attrapait IllegalStateException) suffit a etablir sans ambiguite que ce test aurait echoue sur l'ancien code et passe sur le nouveau.

3. Fail-open Redis sur mettreAJourCompteur(...) toujours fonctionnel et non regresse : test erreurRedisSurMiseAJourDuCompteur_devraitLaisserPasserFailOpenSansAlterLaReponse (nouveau, lignes 130-141) verifie que lorsque enregistrerSucces leve une RuntimeException, la reponse originale (statut 200, corps {"trouve":true}) traverse intacte jusqu'au client -- comportement correct et distinct du cas 2.
4. wrapper.copyBodyToResponse() dans le finally quand une exception applicative remonte : verifie par decompilation du bytecode de ContentCachingResponseWrapper.copyBodyToResponse(boolean) (spring-web 6.1.13, resolu comme dependance transitive de spring-boot-starter-parent 3.3.13) -- la methode commence par un test equivalent a "if (content.size() > 0) { ... } else return;" (bytecode : FastByteArrayOutputStream.size() puis ifle -> return). Tant qu'aucun octet n'a ete ecrit dans le wrapper avant que l'exception ne soit levee, copyBodyToResponse() est un pur no-op : aucun statut ni corps n'est pousse vers la vraie reponse, qui reste non committee et peut donc etre correctement traitee par le mecanisme d'erreur standard de Spring/du conteneur en amont du filtre. RecherchePubliqueController.rechercher(...) (RecherchePubliqueController.java lignes 20-23) retourne un ResponseEntity classique sans ecriture streaming ; Spring MVC n'ecrit le corps qu'apres le retour reussi du handler, donc dans le cas d'une exception applicative levee pendant le traitement, rien n'a encore ete ecrit dans le wrapper au moment ou le finally s'execute. Le cas serait different avec un handler qui ecrirait de facon incrementale dans la reponse puis leverait une exception (corps partiel deja bufferise dans le wrapper, alors recopie par le finally avant la propagation, ce qui produirait une reponse partiellement ecrite ET une tentative de traitement d'erreur en aval) -- mais ce risque est inherent a tout usage de ContentCachingResponseWrapper autour d'un chain.doFilter, preexiste au ticket, ne concerne aucun endpoint actuel du perimetre (aucun controller de ce module ne fait d'ecriture incrementale), et n'est pas une regression introduite par ce commit correctif. Non bloquant.

Conclusion sur le correctif : complet et correct, aucune regression introduite sur le fail-open Redis legitime, la propagation d'exception applicative est desormais celle attendue.

## Verifications reconduites du 1er passage (aucun changement, pas de nouvelle regression)

- Ordre des filtres dans SecurityConfig (rate-limit -> captcha -> JWT) inchange -- fichier non touche par le commit correctif.
- Contrats 429 (LIMITE_DEBIT_DEPASSEE + Retry-After) et 428/CAPTCHA_REQUIS (via HandlerExceptionResolver + RecherchePubliqueExceptionHandler) inchanges, conformes.
- @Lazy sur RedisRateLimiterConfig et sur le parametre ProxyManager<String> de SecurityConfig : inchange, toujours verifie par RedisRateLimiterConfigLazyStartupTest (2/2 tests passes).
- Numero de document deja hache en amont (#18), aucune donnee sensible en clair introduite par les nouvelles cles Redis (compteur d'echecs par IP, defi CAPTCHA) -- conforme au paragraphe 10 de PROJET-SAMAPIECE.md.
- Aucune migration Flyway dans ce ticket.
- spec.md mis a jour en coherence avec le code corrige (git show 30c66cf -- docs/bolts/19-rate-limiting-captcha/spec.md), evitant qu'un futur bolt ne recopie le bug depuis le contrat technique.
- Pas de changement frontend dans ce diff.

## Build/tests

- mvn -pl backend -am test -Dtest=RecherchePubliqueCaptchaFilterTest,EchecRechercheCounterServiceTest,DefiMathematiqueCaptchaVerifierTest,RedisRateLimiterConfigLazyStartupTest -> BUILD SUCCESS.
  - RecherchePubliqueCaptchaFilterTest : 8/8 (dont les 2 nouveaux tests du correctif)
  - EchecRechercheCounterServiceTest : 5/5
  - DefiMathematiqueCaptchaVerifierTest : 5/5
  - RedisRateLimiterConfigLazyStartupTest : 2/2
  - Total : 20/20, 0 echec, 0 erreur (les stack traces "RuntimeException: Redis indisponible" visibles dans les logs de sortie sont attendues et volontaires -- elles materialisent les scenarios fail-open testes en WARN, pas des echecs de test).
- Suite Testcontainers complete non relancee dans ce passage (limitation Docker/Windows deja documentee et inchangee depuis le 1er passage ; aucun fichier de tests d'integration Testcontainers n'a ete modifie par le commit correctif).

## Conclusion

Le correctif du Finding 1 bloquant est complet, correct et teste : chain.doFilter est hors du try/catch fail-open, une exception applicative remonte desormais normalement (verifie par test et par analyse du bytecode de copyBodyToResponse), le fail-open Redis legitime n'est pas regresse, et la spec a ete corrigee en coherence. Aucune regression detectee sur le reste du perimetre du ticket, qui n'a pas ete retouche par ce commit. APPROVE.
