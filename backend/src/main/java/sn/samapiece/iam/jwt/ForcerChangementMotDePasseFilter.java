package sn.samapiece.iam.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import sn.samapiece.iam.MotDePasseTemporaireNonChangeException;

public class ForcerChangementMotDePasseFilter extends OncePerRequestFilter {

    private static final String CHEMIN_CHANGER_MOT_DE_PASSE = "/api/v1/agents/moi/mot-de-passe";
    private static final String CHEMIN_MOI = "/api/v1/agents/moi";
    private static final String CHEMIN_REFRESH = "/api/v1/auth/refresh";

    private final HandlerExceptionResolver handlerExceptionResolver;

    public ForcerChangementMotDePasseFilter(HandlerExceptionResolver handlerExceptionResolver) {
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String methode = request.getMethod();
        if (uri.startsWith("/actuator/")) {
            return true;
        }
        if ("PUT".equalsIgnoreCase(methode) && CHEMIN_CHANGER_MOT_DE_PASSE.equals(uri)) {
            return true;
        }
        if ("GET".equalsIgnoreCase(methode) && CHEMIN_MOI.equals(uri)) {
            return true;
        }
        return "POST".equalsIgnoreCase(methode) && CHEMIN_REFRESH.equals(uri);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getDetails() instanceof Boolean actif && actif) {
            handlerExceptionResolver.resolveException(
                    request, response, null, new MotDePasseTemporaireNonChangeException());
            return;
        }
        chain.doFilter(request, response);
    }
}
