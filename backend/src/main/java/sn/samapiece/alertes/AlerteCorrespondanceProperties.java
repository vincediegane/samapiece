package sn.samapiece.alertes;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "samapiece.alerte-correspondance")
public class AlerteCorrespondanceProperties {

    @Min(1)
    private int maxTentatives;

    @Positive
    private long retryDelai30sMs;

    @Positive
    private long retryDelai2mMs;

    @Positive
    private long retryDelai10mMs;

    public int getMaxTentatives() {
        return maxTentatives;
    }

    public void setMaxTentatives(int maxTentatives) {
        this.maxTentatives = maxTentatives;
    }

    public long getRetryDelai30sMs() {
        return retryDelai30sMs;
    }

    public void setRetryDelai30sMs(long retryDelai30sMs) {
        this.retryDelai30sMs = retryDelai30sMs;
    }

    public long getRetryDelai2mMs() {
        return retryDelai2mMs;
    }

    public void setRetryDelai2mMs(long retryDelai2mMs) {
        this.retryDelai2mMs = retryDelai2mMs;
    }

    public long getRetryDelai10mMs() {
        return retryDelai10mMs;
    }

    public void setRetryDelai10mMs(long retryDelai10mMs) {
        this.retryDelai10mMs = retryDelai10mMs;
    }
}
