package sn.samapiece.recherche.securite;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class DefiMathematiqueCaptchaVerifierTest {

    private static final Pattern QUESTION = Pattern.compile("^(\\d+) \\+ (\\d+) = \\?$");

    private final DefiMathematiqueCaptchaVerifier verifier =
            new DefiMathematiqueCaptchaVerifier(new CaptchaProperties());

    private int reponseAttendue(CaptchaVerifier.DefiCaptcha defi) {
        Matcher matcher = QUESTION.matcher(defi.question());
        assertThat(matcher.matches()).isTrue();
        int a = Integer.parseInt(matcher.group(1));
        int b = Integer.parseInt(matcher.group(2));
        assertThat(a).isBetween(1, 20);
        assertThat(b).isBetween(1, 20);
        return a + b;
    }

    @Test
    void genererDefi_devraitProduireUneQuestionAuFormatAttendu() {
        CaptchaVerifier.DefiCaptcha defi = verifier.genererDefi();

        reponseAttendue(defi);
        assertThat(defi.captchaToken()).isNotBlank();
    }

    @Test
    void verifier_avecBonneReponse_devraitRenvoyerTrue() {
        CaptchaVerifier.DefiCaptcha defi = verifier.genererDefi();

        assertThat(verifier.verifier(defi.captchaToken(), String.valueOf(reponseAttendue(defi)))).isTrue();
    }

    @Test
    void verifier_devraitIgnorerLesEspacesAutourDeLaReponse() {
        CaptchaVerifier.DefiCaptcha defi = verifier.genererDefi();

        assertThat(verifier.verifier(defi.captchaToken(), " " + reponseAttendue(defi) + " ")).isTrue();
    }

    @Test
    void verifier_avecMauvaiseReponse_devraitRenvoyerFalse() {
        CaptchaVerifier.DefiCaptcha defi = verifier.genererDefi();

        assertThat(verifier.verifier(defi.captchaToken(), String.valueOf(reponseAttendue(defi) + 1))).isFalse();
    }

    @Test
    void verifier_devraitEtreAUsageUnique_memeApresUneMauvaiseReponse() {
        CaptchaVerifier.DefiCaptcha defi = verifier.genererDefi();
        int bonneReponse = reponseAttendue(defi);

        verifier.verifier(defi.captchaToken(), String.valueOf(bonneReponse + 1));

        assertThat(verifier.verifier(defi.captchaToken(), String.valueOf(bonneReponse))).isFalse();
    }

    @Test
    void verifier_avecTokenInconnu_devraitRenvoyerFalse() {
        assertThat(verifier.verifier("token-inconnu", "12")).isFalse();
    }

    @Test
    void verifier_avecTokenOuReponseNulle_devraitRenvoyerFalse() {
        assertThat(verifier.verifier(null, "12")).isFalse();
        assertThat(verifier.verifier("token-1", null)).isFalse();
    }
}
