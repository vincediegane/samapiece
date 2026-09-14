package sn.samapiece.recherche.securite;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "samapiece.captcha")
public class CaptchaProperties {

    @Min(1)
    private int seuilEchecsConsecutifs = 5;

    @Min(1)
    private long ttlCompteurEchecsSecondes = 600;

    @Min(1)
    private long ttlDefiSecondes = 120;

    public int getSeuilEchecsConsecutifs() {
        return seuilEchecsConsecutifs;
    }

    public void setSeuilEchecsConsecutifs(int seuilEchecsConsecutifs) {
        this.seuilEchecsConsecutifs = seuilEchecsConsecutifs;
    }

    public long getTtlCompteurEchecsSecondes() {
        return ttlCompteurEchecsSecondes;
    }

    public void setTtlCompteurEchecsSecondes(long ttlCompteurEchecsSecondes) {
        this.ttlCompteurEchecsSecondes = ttlCompteurEchecsSecondes;
    }

    public long getTtlDefiSecondes() {
        return ttlDefiSecondes;
    }

    public void setTtlDefiSecondes(long ttlDefiSecondes) {
        this.ttlDefiSecondes = ttlDefiSecondes;
    }
}
