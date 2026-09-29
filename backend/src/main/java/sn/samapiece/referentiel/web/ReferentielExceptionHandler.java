package sn.samapiece.referentiel.web;

import java.util.Comparator;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import sn.samapiece.iam.web.AgentAdminExceptionHandler.ErreurReponse;
import sn.samapiece.referentiel.HorairesInvalidesException;
import sn.samapiece.referentiel.PosteDejaExistantException;
import sn.samapiece.referentiel.RegionDejaExistanteException;
import sn.samapiece.referentiel.RegionIntrouvableException;

@RestControllerAdvice(basePackages = "sn.samapiece.referentiel.web")
public class ReferentielExceptionHandler {

    private static final String REQUETE_INVALIDE = "REQUETE_INVALIDE";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErreurReponse> gererValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(erreur -> erreur.getField()))
                .map(erreur -> erreur.getField() + ": " + erreur.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErreurReponse(REQUETE_INVALIDE, message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErreurReponse> gererCorpsIllisible(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErreurReponse(REQUETE_INVALIDE, "Corps de requete invalide."));
    }

    @ExceptionHandler(HorairesInvalidesException.class)
    public ResponseEntity<ErreurReponse> gererHorairesInvalides(HorairesInvalidesException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErreurReponse(REQUETE_INVALIDE, ex.getMessage()));
    }

    @ExceptionHandler(RegionDejaExistanteException.class)
    public ResponseEntity<ErreurReponse> gererRegionDejaExistante(RegionDejaExistanteException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErreurReponse("REGION_DEJA_EXISTANTE", "Cette region existe deja."));
    }

    @ExceptionHandler(PosteDejaExistantException.class)
    public ResponseEntity<ErreurReponse> gererPosteDejaExistant(PosteDejaExistantException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErreurReponse("POSTE_DEJA_EXISTANT", "Un poste de ce nom existe deja dans cette region."));
    }

    @ExceptionHandler(RegionIntrouvableException.class)
    public ResponseEntity<ErreurReponse> gererRegionIntrouvable(RegionIntrouvableException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErreurReponse("REGION_INTROUVABLE", "Region introuvable."));
    }
}
