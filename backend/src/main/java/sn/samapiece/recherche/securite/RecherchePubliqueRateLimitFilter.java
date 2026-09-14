package sn.samapiece.recherche.securite;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

public class RecherchePubliqueRateLimitFilter extends OncePerRequestFilter {

    private static final String CHEMIN = "/api/v1/recherche-publique";
    private static final Logger LOG = LoggerFactory.getLogger(RecherchePubliqueRateLimitFilter.class);

    public record LimiteDebitReponse(String code, String message) {}

    private final ProxyManager<String> proxyManager;
    private final RateLimitingProperties proprietes;
    private final ObjectMapper objectMapper;

    public RecherchePubliqueRateLimitFilter(
            ProxyManager<String> proxyManager, RateLimitingProperties proprietes, ObjectMapper objectMapper) {
        this.proxyManager = proxyManager;
        this.proprietes = proprietes;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod()) && CHEMIN.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = request.getRemoteAddr();
        try {
            String cle = "rate-limit:recherche-publique:" + ip;
            Bandwidth limite = Bandwidth.builder()
                    .capacity(proprietes.getCapacite())
                    .refillGreedy(proprietes.getCapacite(), Duration.ofSeconds(proprietes.getPeriodeSecondes()))
                    .build();
            Bucket bucket = proxyManager.builder().build(cle,
                    () -> BucketConfiguration.builder().addLimit(limite).build());
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            if (!probe.isConsumed()) {
                long secondes = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
                response.setStatus(429);
                response.setHeader("Retry-After", String.valueOf(secondes));
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(new LimiteDebitReponse(
                        "LIMITE_DEBIT_DEPASSEE", "Trop de requêtes, veuillez réessayer plus tard.")));
                return;
            }
        } catch (Exception e) {
            LOG.warn("Redis/Bucket4j indisponible pour le rate limiting de la recherche publique, "
                    + "requête laissée passer (fail-open)", e);
        }
        chain.doFilter(request, response);
    }
}
