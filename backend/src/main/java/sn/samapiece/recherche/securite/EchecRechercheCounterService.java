package sn.samapiece.recherche.securite;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Ne catch aucune exception Redis : propagée telle quelle à l'appelant (le filtre décide du
 * comportement fail-open, voir {@link RecherchePubliqueCaptchaFilter}).
 */
@Service
public class EchecRechercheCounterService {

    private static final String PREFIXE_CLE = "recherche-publique:echecs:";

    private final StringRedisTemplate redisTemplate;
    private final CaptchaProperties proprietes;

    public EchecRechercheCounterService(StringRedisTemplate redisTemplate, CaptchaProperties proprietes) {
        this.redisTemplate = redisTemplate;
        this.proprietes = proprietes;
    }

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
