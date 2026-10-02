package sn.samapiece.recherche.securite;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DefiMathematiqueCaptchaVerifier implements CaptchaVerifier {

    private static final SecureRandom ALEATOIRE = new SecureRandom();

    private final Cache<String, String> defis;

    public DefiMathematiqueCaptchaVerifier(CaptchaProperties proprietes) {
        this.defis = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(proprietes.getTtlDefiSecondes()))
                .build();
    }

    @Override
    public DefiCaptcha genererDefi() {
        int a = 1 + ALEATOIRE.nextInt(20);
        int b = 1 + ALEATOIRE.nextInt(20);
        String captchaToken = UUID.randomUUID().toString();
        defis.put(captchaToken, String.valueOf(a + b));
        return new DefiCaptcha(captchaToken, a + " + " + b + " = ?");
    }

    @Override
    public boolean verifier(String captchaToken, String reponseFournie) {
        if (captchaToken == null || reponseFournie == null) {
            return false;
        }
        // Usage unique : le defi est retire, que la reponse soit bonne ou non.
        String reponseAttendue = defis.asMap().remove(captchaToken);
        return reponseAttendue != null && reponseAttendue.equals(reponseFournie.trim());
    }
}
