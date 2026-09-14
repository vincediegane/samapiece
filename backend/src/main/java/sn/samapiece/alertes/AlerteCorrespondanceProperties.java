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
    private long retryTtl30sMs;

    @Positive
    private long retryTtl2mMs;

    @Positive
    private long retryTtl10mMs;

    public int getMaxTentatives() {
        return maxTentatives;
    }

    public void setMaxTentatives(int maxTentatives) {
        this.maxTentatives = maxTentatives;
    }

    public long getRetryTtl30sMs() {
        return retryTtl30sMs;
    }

    public void setRetryTtl30sMs(long retryTtl30sMs) {
        this.retryTtl30sMs = retryTtl30sMs;
    }

    public long getRetryTtl2mMs() {
        return retryTtl2mMs;
    }

    public void setRetryTtl2mMs(long retryTtl2mMs) {
        this.retryTtl2mMs = retryTtl2mMs;
    }

    public long getRetryTtl10mMs() {
        return retryTtl10mMs;
    }

    public void setRetryTtl10mMs(long retryTtl10mMs) {
        this.retryTtl10mMs = retryTtl10mMs;
    }
}
