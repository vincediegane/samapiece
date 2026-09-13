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

/**
 * Bucket4j-Redis a besoin d'une {@link StatefulRedisConnection} Lettuce brute
 * ({@code RedisCodec<String, byte[]>}), distincte du {@code StringRedisTemplate} auto-configuré par
 * {@code spring-boot-starter-data-redis} (utilisé, lui, par {@link EchecRechercheCounterService} et
 * {@link DefiMathematiqueCaptchaVerifier}).
 */
@Configuration
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
