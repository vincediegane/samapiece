package sn.samapiece.config;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Configuration de sécurité minimale et temporaire : rend {@code GET /api/v1/postes} public
 * et exige l'authentification pour tout le reste. Sera remplacée/étendue par le futur ticket
 * IAM (RBAC complet).
 *
 * <p>Définir ce {@link SecurityFilterChain} désactive {@code ManagementWebSecurityAutoConfiguration}
 * (conditionnée par {@code @ConditionalOnDefaultWebSecurity}, qui recule dès qu'un bean
 * {@link SecurityFilterChain} existe) : on reproduit donc explicitement ici l'autorisation de
 * {@code /actuator/health} pour conserver le comportement par défaut existant.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/postes").permitAll()
                .anyRequest().authenticated());
        return http.build();
    }
}
