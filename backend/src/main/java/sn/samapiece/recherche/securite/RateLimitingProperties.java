package sn.samapiece.recherche.securite;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "samapiece.rate-limiting.recherche-publique")
public class RateLimitingProperties {

    @Min(1)
    private long capacite = 10;

    @Min(1)
    private long periodeSecondes = 60;

    public long getCapacite() {
        return capacite;
    }

    public void setCapacite(long capacite) {
        this.capacite = capacite;
    }

    public long getPeriodeSecondes() {
        return periodeSecondes;
    }

    public void setPeriodeSecondes(long periodeSecondes) {
        this.periodeSecondes = periodeSecondes;
    }
}
