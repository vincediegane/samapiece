package sn.samapiece.reporting;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "samapiece.reporting")
public class StatistiquesProperties {

    private int seuilAncienneteJours = 180;

    public int getSeuilAncienneteJours() {
        return seuilAncienneteJours;
    }

    public void setSeuilAncienneteJours(int seuilAncienneteJours) {
        this.seuilAncienneteJours = seuilAncienneteJours;
    }
}
