package sn.samapiece.recherche.securite;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.stereotype.Service;

/**
 * Compteur d'echecs consecutifs par IP, garde en memoire (instance unique). Le TTL est renouvele
 * a chaque echec enregistre.
 */
@Service
public class EchecRechercheCounterService {

    private final Cache<String, Integer> compteurs;
    private final CaptchaProperties proprietes;

    public EchecRechercheCounterService(CaptchaProperties proprietes) {
        this.proprietes = proprietes;
        this.compteurs = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(proprietes.getTtlCompteurEchecsSecondes()))
                .build();
    }

    public void enregistrerEchec(String ip) {
        compteurs.asMap().merge(ip, 1, Integer::sum);
    }

    public void enregistrerSucces(String ip) {
        compteurs.invalidate(ip);
    }

    public boolean captchaRequis(String ip) {
        Integer valeur = compteurs.getIfPresent(ip);
        return valeur != null && valeur >= proprietes.getSeuilEchecsConsecutifs();
    }
}
