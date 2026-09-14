package sn.samapiece.alertes.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import sn.samapiece.alertes.AlerteCriteresInsuffisantsException;
import sn.samapiece.alertes.AlerteIntrouvableOuExpireeException;

@RestControllerAdvice
public class AlerteExceptionHandler {

    public record ErreurReponse(String code, String message) {}

    @ExceptionHandler(AlerteCriteresInsuffisantsException.class)
    public ResponseEntity<ErreurReponse> gererCriteresInsuffisants(AlerteCriteresInsuffisantsException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErreurReponse(
                "CRITERES_INSUFFISANTS",
                "Critères de recherche insuffisants : type, nom, et numéro ou date de naissance sont requis."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErreurReponse> gererContactInvalide(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErreurReponse(
                "CONTACT_INVALIDE", "Le contact fourni est invalide."));
    }

    @ExceptionHandler(AlerteIntrouvableOuExpireeException.class)
    public ResponseEntity<ErreurReponse> gererJetonIntrouvable(AlerteIntrouvableOuExpireeException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErreurReponse(
                "JETON_INTROUVABLE", "Ce jeton de désinscription est introuvable, expiré, ou déjà utilisé."));
    }
}
