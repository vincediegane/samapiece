package sn.samapiece.config;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import sn.samapiece.iam.jwt.JwtAuthenticationFilter;
import sn.samapiece.iam.jwt.JwtService;

/**
 * Authentification stateless par JWT auto-émis (voir {@code sn.samapiece.iam.jwt}) : sessions
 * désactivées, CSRF désactivé (API Bearer, pas de cookies), {@link JwtAuthenticationFilter}
 * inséré avant le filtre standard de Spring Security, et {@link HttpStatusEntryPoint} explicite
 * pour renvoyer 401 (le comportement par défaut de Spring Security sans entry point configuré
 * est 403).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions ->
                    exceptions.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(authorize -> authorize
                    .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/postes").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                    .anyRequest().authenticated())
            .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
