package sn.samapiece.iam.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import sn.samapiece.iam.MotDePasseActuelInvalideException;

@RestControllerAdvice
public class AgentSelfExceptionHandler {

    public record ErreurReponse(String code, String message) {}

    @ExceptionHandler(MotDePasseActuelInvalideException.class)
    public ResponseEntity<ErreurReponse> gererMotDePasseActuelInvalide(MotDePasseActuelInvalideException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErreurReponse("MOT_DE_PASSE_ACTUEL_INVALIDE", "Le mot de passe actuel est invalide."));
    }
}
