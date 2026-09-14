package sn.samapiece.recherche.securite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Régression : {@link RedisRateLimiterConfig} doit rester {@code @Lazy} pour que le contexte Spring
 * démarre sans connexion Redis joignable (aucun conteneur Redis n'est démarré par les
 * {@code @SpringBootTest} existants, voir spec #19). La {@code StatefulRedisConnection} Lettuce se
 * connecte réellement dès sa création, contrairement au {@code LettuceConnectionFactory}
 * auto-configuré ; sans {@code @Lazy} sur la config et sur le point d'injection du consommateur
 * (ici simulé, dans l'app réelle {@code SecurityConfig.securityFilterChain}), le démarrage du contexte
 * échouerait dès que Redis est indisponible.
 */
class RedisRateLimiterConfigLazyStartupTest {

    /** Simule un consommateur non paresseux, comme {@code SecurityConfig.securityFilterChain}. */
    @Configuration
    static class ConsommateurNonParesseux {
        @Bean
        String consommateur(@Lazy ProxyManager<String> proxyManager) {
            return "consommateur-pret";
        }
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues(
                    "spring.data.redis.host=127.0.0.1",
                    "spring.data.redis.port=1")
            .withUserConfiguration(RedisRateLimiterConfig.class, ConsommateurNonParesseux.class);

    @Test
    void demarrageDuContexte_devraitReussirSansConnexionRedisReelle() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("consommateur", String.class)).isEqualTo("consommateur-pret");
        });
    }

    @Test
    void premiereUtilisationReelleDuProxyManager_devraitEchouerCarRedisIndisponible() {
        contextRunner.run(context ->
                assertThatThrownBy(() -> context.getBean(ProxyManager.class)).isInstanceOf(Exception.class));
    }
}
