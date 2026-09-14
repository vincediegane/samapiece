package sn.samapiece.notifications;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "samapiece.sms")
public class SmsProperties {

    @NotBlank
    private String apiEndpoint;

    @NotBlank
    private String apiKey;

    @NotBlank
    private String senderId;

    @Positive
    private int timeoutMs;

    @Min(1)
    private int maxTentatives;

    @Positive
    private long retryTtl30sMs;

    @Positive
    private long retryTtl2mMs;

    @Positive
    private long retryTtl10mMs;

    public String getApiEndpoint() {
        return apiEndpoint;
    }

    public void setApiEndpoint(String apiEndpoint) {
        this.apiEndpoint = apiEndpoint;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

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
