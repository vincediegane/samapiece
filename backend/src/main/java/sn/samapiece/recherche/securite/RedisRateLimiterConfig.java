package sn.samapiece.recherche.securite;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Bucket4j-Redis a besoin d'une {@link StatefulRedisConnection} Lettuce brute
 * ({@code RedisCodec<String, byte[]>}), distincte du {@code StringRedisTemplate} auto-configuré par
 * {@code spring-boot-starter-data-redis} (utilisé, lui, par {@link EchecRechercheCounterService} et
 * {@link DefiMathematiqueCaptchaVerifier}).
 *
 * <p>{@code @Lazy} sur la classe : {@link StatefulRedisConnection} Lettuce se connecte réellement au
 * moment de sa création (contrairement au {@code LettuceConnectionFactory} auto-configuré, paresseux
 * par défaut) ; sans cette annotation, le démarrage de tout le contexte Spring échouerait dès que
 * Redis est indisponible, y compris pour les tests qui ne démarrent jamais de conteneur Redis. La
 * connexion n'est donc tentée qu'à la première requête réelle vers la recherche publique, dans le
 * try/catch fail-open de {@link RecherchePubliqueRateLimitFilter} (voir aussi le paramètre
 * {@code @Lazy} de {@code SecurityConfig.securityFilterChain}, nécessaire pour éviter qu'un
 * consommateur non paresseux ne force quand même la résolution anticipée).
 */
@Configuration
@Lazy
public class RedisRateLimiterConfig {

    @Bean(destroyMethod = "shutdown")
    public RedisClient redisClientBucket4j(
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port) {
        return RedisClient.create(RedisURI.Builder.redis(host, port).build());
    }

    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, byte[]> bucket4jRedisConnection(RedisClient redisClientBucket4j) {
        return redisClientBucket4j.connect(RedisCodec.of(new StringCodec(), new ByteArrayCodec()));
    }

    @Bean
    public ProxyManager<String> bucket4jProxyManager(
            StatefulRedisConnection<String, byte[]> bucket4jRedisConnection) {
        return LettuceBasedProxyManager.builderFor(bucket4jRedisConnection)
                .withExpirationStrategy(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(60)))
                .build();
    }
}
