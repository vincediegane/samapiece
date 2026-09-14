package sn.samapiece.recherche.securite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class DefiMathematiqueCaptchaVerifierTest {

    private static final Pattern QUESTION = Pattern.compile("^(\\d+) \\+ (\\d+) = \\?$");

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final CaptchaProperties proprietes = new CaptchaProperties();

    private final DefiMathematiqueCaptchaVerifier verifier =
            new DefiMathematiqueCaptchaVerifier(redisTemplate, proprietes);

    @Test
    void genererDefi_devraitProduireUneQuestionAuFormatAttenduEtLaStockerDansRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        proprietes.setTtlDefiSecondes(120);

        CaptchaVerifier.DefiCaptcha defi = verifier.genererDefi();

        Matcher matcher = QUESTION.matcher(defi.question());
        assertThat(matcher.matches()).isTrue();
        int a = Integer.parseInt(matcher.group(1));
        int b = Integer.parseInt(matcher.group(2));
        assertThat(a).isBetween(1, 20);
        assertThat(b).isBetween(1, 20);
        verify(valueOperations).set(
                "captcha:defi:" + defi.captchaToken(), String.valueOf(a + b), Duration.ofSeconds(120));
    }

    @Test
    void verifier_avecBonneReponse_devraitRenvoyerTrueEtSupprimerLaCle() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("captcha:defi:token-1")).thenReturn("12");

        boolean resultat = verifier.verifier("token-1", "12");

        assertThat(resultat).isTrue();
        verify(redisTemplate).delete("captcha:defi:token-1");
    }

    @Test
    void verifier_avecMauvaiseReponse_devraitRenvoyerFalseEtSupprimerQuandMemeLaCle() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("captcha:defi:token-1")).thenReturn("12");

        boolean resultat = verifier.verifier("token-1", "99");

        assertThat(resultat).isFalse();
        verify(redisTemplate).delete("captcha:defi:token-1");
    }

    @Test
    void verifier_avecTokenInconnu_devraitRenvoyerFalse() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        assertThat(verifier.verifier("token-inconnu", "12")).isFalse();
    }

    @Test
    void verifier_avecTokenOuReponseNulle_devraitRenvoyerFalseSansAppelerRedis() {
        assertThat(verifier.verifier(null, "12")).isFalse();
        assertThat(verifier.verifier("token-1", null)).isFalse();
        verify(redisTemplate, never()).opsForValue();
    }
}
