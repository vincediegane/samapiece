package sn.samapiece.iam.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import sn.samapiece.iam.AuthenticationException;
import sn.samapiece.iam.CompteVerrouilleException;

@RestControllerAdvice
public class AuthExceptionHandler {

    public record ErreurReponse(String code, String message) {}

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErreurReponse> gererAuthentificationInvalide(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErreurReponse("IDENTIFIANTS_INVALIDES", "Matricule ou mot de passe invalide."));
    }

    @ExceptionHandler(CompteVerrouilleException.class)
    public ResponseEntity<ErreurReponse> gererCompteVerrouille(CompteVerrouilleException ex) {
        return ResponseEntity.status(HttpStatus.LOCKED)
                .body(new ErreurReponse("COMPTE_VERROUILLE", "Compte temporairement verrouillé."));
    }
}
