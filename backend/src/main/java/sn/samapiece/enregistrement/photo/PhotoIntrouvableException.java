package sn.samapiece.enregistrement.photo;

import java.util.UUID;

public class PhotoIntrouvableException extends RuntimeException {
    public PhotoIntrouvableException(UUID id) {
        super("Photo introuvable : " + id);
    }
}
