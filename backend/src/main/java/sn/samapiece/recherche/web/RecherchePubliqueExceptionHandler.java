package sn.samapiece.recherche.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import sn.samapiece.recherche.CriteresInsuffisantsException;

@RestControllerAdvice
public class RecherchePubliqueExceptionHandler {

    public record ErreurReponse(String code, String message) {}

    @ExceptionHandler(CriteresInsuffisantsException.class)
    public ResponseEntity<ErreurReponse> gererCriteresInsuffisants(CriteresInsuffisantsException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErreurReponse(
                "CRITERES_INSUFFISANTS",
                "Critères de recherche insuffisants : type, nom, et numéro ou date de naissance sont requis."));
    }
}
