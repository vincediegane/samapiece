package sn.samapiece.iam.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import sn.samapiece.iam.Role;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIXE_BEARER = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIXE_BEARER)) {
            String token = header.substring(PREFIXE_BEARER.length());
            try {
                Claims claims = jwtService.analyserToken(token);
                if (JwtService.TYPE_ACCESS.equals(claims.get(JwtService.CLAIM_TYPE, String.class))) {
                    String matricule = claims.get(JwtService.CLAIM_MATRICULE, String.class);
                    Role role = Role.valueOf(claims.get(JwtService.CLAIM_ROLE, String.class));
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(matricule, null, List.of(role)));
                }
                // typ = refresh présenté sur une route protégée : on ignore silencieusement,
                // aucune authentification n'est posée -> 401 via anyRequest().authenticated().
            } catch (JwtException | IllegalArgumentException e) {
                // Token invalide/expiré/malformé : ne jamais logger token ou message brut ;
                // ne pas authentifier, laisser la chaîne Spring Security répondre 401.
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
