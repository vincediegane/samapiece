package sn.samapiece.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import sn.samapiece.iam.jwt.JwtAuthenticationFilter;
import sn.samapiece.iam.jwt.JwtService;
import sn.samapiece.recherche.securite.CaptchaVerifier;
import sn.samapiece.recherche.securite.EchecRechercheCounterService;
import sn.samapiece.recherche.securite.RateLimitingProperties;
import sn.samapiece.recherche.securite.RecherchePubliqueCaptchaFilter;
import sn.samapiece.recherche.securite.RecherchePubliqueRateLimitFilter;

/**
 * Authentification stateless par JWT auto-émis (voir {@code sn.samapiece.iam.jwt}) : sessions
 * désactivées, CSRF désactivé (API Bearer, pas de cookies), {@link JwtAuthenticationFilter}
 * inséré avant le filtre standard de Spring Security, et {@link HttpStatusEntryPoint} explicite
 * pour renvoyer 401 (le comportement par défaut de Spring Security sans entry point configuré
 * est 403).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtService jwtService,
            @Lazy ProxyManager<String> bucket4jProxyManager,
            RateLimitingProperties rateLimitingProperties,
            EchecRechercheCounterService echecRechercheCounterService,
            CaptchaVerifier captchaVerifier,
            ObjectMapper objectMapper,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver) throws Exception {

        JwtAuthenticationFilter jwtAuthenticationFilter = new JwtAuthenticationFilter(jwtService);
        RecherchePubliqueCaptchaFilter captchaFilter = new RecherchePubliqueCaptchaFilter(
                echecRechercheCounterService, captchaVerifier, objectMapper, handlerExceptionResolver);
        RecherchePubliqueRateLimitFilter rateLimitFilter = new RecherchePubliqueRateLimitFilter(
                bucket4jProxyManager, rateLimitingProperties, objectMapper);

        http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions ->
                    exceptions.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(authorize -> authorize
                    .requestMatchers(EndpointRequest.to(HealthEndpoint.class, InfoEndpoint.class)).permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/postes").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/recherche-publique").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/recherche-publique/captcha").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/alertes").permitAll()
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/alertes/*").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                    .anyRequest().authenticated())
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(captchaFilter, JwtAuthenticationFilter.class)
            .addFilterBefore(rateLimitFilter, RecherchePubliqueCaptchaFilter.class);
        return http.build();
    }
}
