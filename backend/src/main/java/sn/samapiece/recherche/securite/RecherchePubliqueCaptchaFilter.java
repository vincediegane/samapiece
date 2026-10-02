package sn.samapiece.recherche.securite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.ContentCachingResponseWrapper;
import sn.samapiece.recherche.CaptchaRequisException;

public class RecherchePubliqueCaptchaFilter extends OncePerRequestFilter {

    private static final String CHEMIN = "/api/v1/recherche-publique";
    private static final String EN_TETE_TOKEN = "X-Captcha-Token";
    private static final String EN_TETE_REPONSE = "X-Captcha-Reponse";

    private final EchecRechercheCounterService compteurService;
    private final CaptchaVerifier captchaVerifier;
    private final ObjectMapper objectMapper;
    private final HandlerExceptionResolver handlerExceptionResolver;

    public RecherchePubliqueCaptchaFilter(
            EchecRechercheCounterService compteurService,
            CaptchaVerifier captchaVerifier,
            ObjectMapper objectMapper,
            HandlerExceptionResolver handlerExceptionResolver) {
        this.compteurService = compteurService;
        this.captchaVerifier = captchaVerifier;
        this.objectMapper = objectMapper;
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod()) && CHEMIN.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = request.getRemoteAddr();

        if (compteurService.captchaRequis(ip)) {
            String token = request.getHeader(EN_TETE_TOKEN);
            String reponse = request.getHeader(EN_TETE_REPONSE);
            boolean valide = token != null && reponse != null && captchaVerifier.verifier(token, reponse);
            if (!valide) {
                handlerExceptionResolver.resolveException(request, response, null, new CaptchaRequisException());
                return;
            }
        }

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapper);
            mettreAJourCompteur(ip, wrapper);
        } finally {
            wrapper.copyBodyToResponse();
        }
    }

    private void mettreAJourCompteur(String ip, ContentCachingResponseWrapper wrapper) throws IOException {
        int statut = wrapper.getStatus();
        JsonNode corps = objectMapper.readTree(wrapper.getContentAsByteArray());
        if (statut == 200) {
            if (corps.path("trouve").asBoolean(false)) {
                compteurService.enregistrerSucces(ip);
            } else {
                compteurService.enregistrerEchec(ip);
            }
        } else if (statut == 400 && "CRITERES_INSUFFISANTS".equals(corps.path("code").asText(null))) {
            compteurService.enregistrerEchec(ip);
        }
        // Tout autre statut (429 déjà court-circuité en amont par l'autre filtre avant d'atteindre
        // celui-ci, 5xx imprévu) : ni échec ni succès, compteur inchangé.
    }
}
