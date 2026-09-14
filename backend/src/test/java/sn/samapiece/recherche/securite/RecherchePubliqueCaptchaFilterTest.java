package sn.samapiece.recherche.securite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import sn.samapiece.recherche.CaptchaRequisException;

class RecherchePubliqueCaptchaFilterTest {

    private static final String CHEMIN = "/api/v1/recherche-publique";

    private final EchecRechercheCounterService compteurService = mock(EchecRechercheCounterService.class);
    private final CaptchaVerifier captchaVerifier = mock(CaptchaVerifier.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HandlerExceptionResolver handlerExceptionResolver = mock(HandlerExceptionResolver.class);
    private final FilterChain chain = mock(FilterChain.class);

    private RecherchePubliqueCaptchaFilter filtre;

    @BeforeEach
    void initialiser() {
        filtre = new RecherchePubliqueCaptchaFilter(compteurService, captchaVerifier, objectMapper, handlerExceptionResolver);
    }

    private MockHttpServletRequest requete() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", CHEMIN);
        request.setRemoteAddr("1.2.3.4");
        return request;
    }

    private FilterChain chainQuiRepond(int statut, String corps) {
        return (req, res) -> {
            jakarta.servlet.http.HttpServletResponse httpRes = (jakarta.servlet.http.HttpServletResponse) res;
            httpRes.setStatus(statut);
            httpRes.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.getWriter().write(corps);
        };
    }

    @Test
    void shouldNotFilter_devraitIgnorerLesAutresRoutesEtMethodes() {
        MockHttpServletRequest autreRoute = new MockHttpServletRequest("POST", "/api/v1/postes");
        MockHttpServletRequest autreMethode = new MockHttpServletRequest("GET", CHEMIN);

        assertThat(filtre.shouldNotFilter(autreRoute)).isTrue();
        assertThat(filtre.shouldNotFilter(autreMethode)).isTrue();
        assertThat(filtre.shouldNotFilter(requete())).isFalse();
    }

    @Test
    void captchaNonRequis_devraitLaisserPasserEtEnregistrerLeSucces() throws Exception {
        when(compteurService.captchaRequis("1.2.3.4")).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filtre.doFilterInternal(requete(), response, chainQuiRepond(200, "{\"trouve\":true}"));

        verify(compteurService).enregistrerSucces("1.2.3.4");
        verify(compteurService, never()).enregistrerEchec(any());
        verifyNoInteractions(handlerExceptionResolver);
    }

    @Test
    void captchaRequisSansEnTetes_devraitDelegerAuHandlerExceptionResolverEtCourtCircuiter() throws Exception {
        when(compteurService.captchaRequis("1.2.3.4")).thenReturn(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filtre.doFilterInternal(requete(), response, chain);

        ArgumentCaptor<Exception> exceptionCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(handlerExceptionResolver).resolveException(any(), eq(response), isNull(), exceptionCaptor.capture());
        assertThat(exceptionCaptor.getValue()).isInstanceOf(CaptchaRequisException.class);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void captchaRequisSansEnTetes_neDevraitJamaisMettreAJourLeCompteur() throws Exception {
        when(compteurService.captchaRequis("1.2.3.4")).thenReturn(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filtre.doFilterInternal(requete(), response, chain);

        verify(compteurService, never()).enregistrerEchec(any());
        verify(compteurService, never()).enregistrerSucces(any());
    }

    @Test
    void captchaRequisAvecReponseValide_devraitAppelerLaChaine() throws Exception {
        when(compteurService.captchaRequis("1.2.3.4")).thenReturn(true);
        when(captchaVerifier.verifier("token-1", "12")).thenReturn(true);
        MockHttpServletRequest request = requete();
        request.addHeader("X-Captcha-Token", "token-1");
        request.addHeader("X-Captcha-Reponse", "12");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filtre.doFilterInternal(request, response, chainQuiRepond(200, "{\"trouve\":true}"));

        verifyNoInteractions(handlerExceptionResolver);
        verify(compteurService).enregistrerSucces("1.2.3.4");
    }

    @Test
    void erreurRedisSurCaptchaRequis_devraitLaisserPasserFailOpen() throws Exception {
        when(compteurService.captchaRequis("1.2.3.4")).thenThrow(new RuntimeException("Redis indisponible"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filtre.doFilterInternal(requete(), response, chainQuiRepond(200, "{\"trouve\":true}"));

        verifyNoInteractions(handlerExceptionResolver);
        verify(compteurService).enregistrerSucces("1.2.3.4");
    }

    @Test
    void erreurRedisSurMiseAJourDuCompteur_devraitLaisserPasserFailOpenSansAlterLaReponse() throws Exception {
        when(compteurService.captchaRequis("1.2.3.4")).thenReturn(false);
        doThrow(new RuntimeException("Redis indisponible")).when(compteurService).enregistrerSucces("1.2.3.4");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filtre.doFilterInternal(requete(), response, chainQuiRepond(200, "{\"trouve\":true}"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("{\"trouve\":true}");
        verifyNoInteractions(handlerExceptionResolver);
    }

    @Test
    void exceptionApplicativeDansLaChaine_neDoitJamaisEtreAvaleeParLeFailOpen() {
        when(compteurService.captchaRequis("1.2.3.4")).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chainQuiEchoue = (req, res) -> {
            throw new IllegalStateException("bug applicatif sans rapport avec Redis");
        };

        assertThatThrownBy(() -> filtre.doFilterInternal(requete(), response, chainQuiEchoue))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("bug applicatif sans rapport avec Redis");

        verify(compteurService, never()).enregistrerSucces(any());
        verify(compteurService, never()).enregistrerEchec(any());
    }
}
