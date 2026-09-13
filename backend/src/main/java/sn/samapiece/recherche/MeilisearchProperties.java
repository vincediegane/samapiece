package sn.samapiece.recherche;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "samapiece.meilisearch")
public class MeilisearchProperties {

    @NotBlank
    private String host;

    @NotBlank
    private String apiKey;

    @NotBlank
    private String indexPieces;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getIndexPieces() {
        return indexPieces;
    }

    public void setIndexPieces(String indexPieces) {
        this.indexPieces = indexPieces;
    }
}
