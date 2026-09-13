package sn.samapiece.enregistrement.photo.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import sn.samapiece.enregistrement.PieceIntrouvableException;
import sn.samapiece.enregistrement.photo.FichierTropVolumineuxException;
import sn.samapiece.enregistrement.photo.PhotoDejaExistanteException;
import sn.samapiece.enregistrement.photo.PhotoIntrouvableException;
import sn.samapiece.enregistrement.photo.PhotoStockageException;
import sn.samapiece.enregistrement.photo.TypeFichierNonAutoriseException;

@RestControllerAdvice
public class PhotoExceptionHandler {

    public record ErreurReponse(String code, String message) {}

    @ExceptionHandler(PieceIntrouvableException.class)
    public ResponseEntity<ErreurReponse> gererPieceIntrouvable(PieceIntrouvableException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErreurReponse("PIECE_INTROUVABLE", "Piece introuvable."));
    }

    @ExceptionHandler(PhotoIntrouvableException.class)
    public ResponseEntity<ErreurReponse> gererPhotoIntrouvable(PhotoIntrouvableException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErreurReponse("PHOTO_INTROUVABLE", "Photo introuvable."));
    }

    @ExceptionHandler(PhotoDejaExistanteException.class)
    public ResponseEntity<ErreurReponse> gererPhotoDejaExistante(PhotoDejaExistanteException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErreurReponse("PHOTO_DEJA_EXISTANTE", ex.getMessage()));
    }

    @ExceptionHandler(TypeFichierNonAutoriseException.class)
    public ResponseEntity<ErreurReponse> gererTypeFichierNonAutorise(TypeFichierNonAutoriseException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ErreurReponse("TYPE_FICHIER_NON_AUTORISE", ex.getMessage()));
    }

    @ExceptionHandler(FichierTropVolumineuxException.class)
    public ResponseEntity<ErreurReponse> gererFichierTropVolumineux(FichierTropVolumineuxException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErreurReponse("FICHIER_TROP_VOLUMINEUX", ex.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErreurReponse> gererMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErreurReponse("FICHIER_TROP_VOLUMINEUX", "Le fichier depasse la taille maximale autorisee."));
    }

    @ExceptionHandler(PhotoStockageException.class)
    public ResponseEntity<ErreurReponse> gererStockageIndisponible(PhotoStockageException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErreurReponse("STOCKAGE_INDISPONIBLE", "Le stockage des photos est indisponible."));
    }
}
