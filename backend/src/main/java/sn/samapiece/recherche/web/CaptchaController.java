package sn.samapiece.recherche.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.samapiece.recherche.securite.CaptchaVerifier;

@RestController
@RequestMapping("/api/v1/recherche-publique/captcha")
public class CaptchaController {

    public record CaptchaDefiResponse(String captchaToken, String question) {}

    private final CaptchaVerifier captchaVerifier;

    public CaptchaController(CaptchaVerifier captchaVerifier) {
        this.captchaVerifier = captchaVerifier;
    }

    @GetMapping
    public ResponseEntity<CaptchaDefiResponse> obtenirDefi() {
        CaptchaVerifier.DefiCaptcha defi = captchaVerifier.genererDefi();
        return ResponseEntity.ok(new CaptchaDefiResponse(defi.captchaToken(), defi.question()));
    }
}
