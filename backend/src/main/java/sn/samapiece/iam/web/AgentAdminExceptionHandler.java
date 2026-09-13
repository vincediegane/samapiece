package sn.samapiece.iam.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import sn.samapiece.iam.AccesRefuseException;
import sn.samapiece.iam.AgentIntrouvableException;
import sn.samapiece.iam.MatriculeDejaUtiliseException;
import sn.samapiece.iam.PosteIntrouvableException;

@RestControllerAdvice
public class AgentAdminExceptionHandler {

    public record ErreurReponse(String code, String message) {}

    @ExceptionHandler(AccesRefuseException.class)
    public ResponseEntity<ErreurReponse> gererAccesRefuse(AccesRefuseException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErreurReponse("ACCES_REFUSE", "Acces refuse."));
    }

    @ExceptionHandler(AgentIntrouvableException.class)
    public ResponseEntity<ErreurReponse> gererAgentIntrouvable(AgentIntrouvableException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErreurReponse("AGENT_INTROUVABLE", "Agent introuvable."));
    }

    @ExceptionHandler(PosteIntrouvableException.class)
    public ResponseEntity<ErreurReponse> gererPosteIntrouvable(PosteIntrouvableException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErreurReponse("POSTE_INTROUVABLE", "Poste introuvable."));
    }

    @ExceptionHandler(MatriculeDejaUtiliseException.class)
    public ResponseEntity<ErreurReponse> gererMatriculeDejaUtilise(MatriculeDejaUtiliseException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErreurReponse("MATRICULE_DEJA_UTILISE", "Ce matricule est deja utilise."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErreurReponse> gererValidationInvalide(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErreurReponse("REQUETE_INVALIDE", ex.getMessage()));
    }
}
