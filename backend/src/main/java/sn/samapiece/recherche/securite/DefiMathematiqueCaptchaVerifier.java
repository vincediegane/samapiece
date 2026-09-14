package sn.samapiece.recherche.securite;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class DefiMathematiqueCaptchaVerifier implements CaptchaVerifier {

    private static final String PREFIXE_CLE = "captcha:defi:";
    private static final SecureRandom ALEATOIRE = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final CaptchaProperties proprietes;

    public DefiMathematiqueCaptchaVerifier(StringRedisTemplate redisTemplate, CaptchaProperties proprietes) {
        this.redisTemplate = redisTemplate;
        this.proprietes = proprietes;
    }

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
        redisTemplate.delete(cle);
        return reponseAttendue != null && reponseAttendue.equals(reponseFournie.trim());
    }
}
