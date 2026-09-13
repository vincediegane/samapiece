package sn.samapiece.recherche;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MeilisearchConfig {

    @Bean
    public Client meilisearchClient(MeilisearchProperties proprietes) {
        return new Client(new Config(proprietes.getHost(), proprietes.getApiKey()));
    }
}
