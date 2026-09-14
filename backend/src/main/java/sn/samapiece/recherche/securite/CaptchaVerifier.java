package sn.samapiece.recherche.securite;

public interface CaptchaVerifier {

    record DefiCaptcha(String captchaToken, String question) {}

    DefiCaptcha genererDefi();

    boolean verifier(String captchaToken, String reponseFournie);
}
