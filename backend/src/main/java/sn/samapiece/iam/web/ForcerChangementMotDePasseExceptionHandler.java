package sn.samapiece.iam.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import sn.samapiece.iam.MotDePasseTemporaireNonChangeException;

@RestControllerAdvice
public class ForcerChangementMotDePasseExceptionHandler {

    public record ErreurReponse(String code, String message) {}

    @ExceptionHandler(MotDePasseTemporaireNonChangeException.class)
    public ResponseEntity<ErreurReponse> gererMotDePasseTemporaireNonChange(
            MotDePasseTemporaireNonChangeException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErreurReponse(
                        "MOT_DE_PASSE_TEMPORAIRE_NON_CHANGE",
                        "Le mot de passe temporaire doit etre change avant d'acceder a cette ressource."));
    }
}
