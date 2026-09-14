package sn.samapiece.enregistrement.web;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import sn.samapiece.enregistrement.DoublonPotentielException;
import sn.samapiece.enregistrement.TransitionStatutInterditeException;

@RestControllerAdvice
public class PieceExceptionHandler {

    public record ErreurReponse(String code, String message) {}

    public record ErreurDoublonReponse(String code, String message, List<String> numerosFicheCandidats) {}

    @ExceptionHandler(TransitionStatutInterditeException.class)
    public ResponseEntity<ErreurReponse> gererTransitionInterdite(TransitionStatutInterditeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErreurReponse("TRANSITION_STATUT_INTERDITE", ex.getMessage()));
    }

    @ExceptionHandler(DoublonPotentielException.class)
    public ResponseEntity<ErreurDoublonReponse> gererDoublonPotentiel(DoublonPotentielException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErreurDoublonReponse(
                        "DOUBLON_POTENTIEL", ex.getMessage(), ex.getNumerosFicheCandidats()));
    }
}
