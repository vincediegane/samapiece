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
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import sn.samapiece.iam.jwt.ForcerChangementMotDePasseFilter;
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
 * inséré avant le filtre standard de Spring Security, {@link HttpStatusEntryPoint} explicite
 * pour renvoyer 401 sur une requête non authentifiée, et {@link AccessDeniedHandlerImpl} explicite
 * pour renvoyer 403 sur un rôle insuffisant (le 403 explicite documente un comportement qui était
 * déjà celui par défaut de Spring Security, pour ne plus en dépendre implicitement).
 *
 * <p>{@code /error} est en {@code permitAll()} : {@link HttpStatusEntryPoint} et
 * {@link AccessDeniedHandlerImpl} utilisent tous deux {@code response.sendError(...)}, ce qui
 * déclenche un forward conteneur (dispatcher ERROR) vers {@code /error}. Ce forward retraverse
 * intégralement cette même chaîne de filtres avec un {@code SecurityContext} vide (les filtres
 * JWT sont des {@code OncePerRequestFilter}, qui ignorent par défaut le dispatcher ERROR). Sans
 * cette règle, {@code /error} tombe sous {@code anyRequest().authenticated()}, l'utilisateur y est
 * anonyme, et l'{@code authenticationEntryPoint} écrase silencieusement le 403 déjà émis par un
 * 401 — c'est la cause racine du ticket #60 (403 attendu sur rôle insuffisant, 401 observé en
 * conditions réelles, non reproductible avec MockMvc qui ne simule pas ce forward).
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
        ForcerChangementMotDePasseFilter forcerChangementMotDePasseFilter =
                new ForcerChangementMotDePasseFilter(handlerExceptionResolver);
        RecherchePubliqueCaptchaFilter captchaFilter = new RecherchePubliqueCaptchaFilter(
                echecRechercheCounterService, captchaVerifier, objectMapper, handlerExceptionResolver);
        RecherchePubliqueRateLimitFilter rateLimitFilter = new RecherchePubliqueRateLimitFilter(
                bucket4jProxyManager, rateLimitingProperties, objectMapper);

        http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions
                    .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                    .accessDeniedHandler(new AccessDeniedHandlerImpl()))
            .authorizeHttpRequests(authorize -> authorize
                    .requestMatchers(EndpointRequest.to(HealthEndpoint.class, InfoEndpoint.class)).permitAll()
                    // Voir Javadoc de la classe : nécessaire pour que le forward ERROR déclenché
                    // par sendError() (401/403) ne soit pas lui-même bloqué et réécrit en 401.
                    .requestMatchers("/error").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/postes").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/recherche-publique").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/recherche-publique/captcha").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/alertes").permitAll()
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/alertes/*").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                    .anyRequest().authenticated())
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(forcerChangementMotDePasseFilter, JwtAuthenticationFilter.class)
            .addFilterBefore(captchaFilter, JwtAuthenticationFilter.class)
            .addFilterBefore(rateLimitFilter, RecherchePubliqueCaptchaFilter.class);
        return http.build();
    }
}
