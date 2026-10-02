package sn.samapiece.recherche.securite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.web.filter.OncePerRequestFilter;

public class RecherchePubliqueRateLimitFilter extends OncePerRequestFilter {

    private static final String CHEMIN = "/api/v1/recherche-publique";

    public record LimiteDebitReponse(String code, String message) {}

    // Un bucket par IP, en memoire (instance unique), evince apres une periode sans acces.
    private final Cache<String, Bucket> buckets;
    private final Bandwidth limite;
    private final ObjectMapper objectMapper;

    public RecherchePubliqueRateLimitFilter(RateLimitingProperties proprietes, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.limite = Bandwidth.builder()
                .capacity(proprietes.getCapacite())
                .refillGreedy(proprietes.getCapacite(), Duration.ofSeconds(proprietes.getPeriodeSecondes()))
                .build();
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofSeconds(proprietes.getPeriodeSecondes()))
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod()) && CHEMIN.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Bucket bucket = buckets.get(request.getRemoteAddr(), ip -> Bucket.builder().addLimit(limite).build());
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
        chain.doFilter(request, response);
    }
}
