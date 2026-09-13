package sn.samapiece.iam;

import java.util.UUID;

public class PosteIntrouvableException extends RuntimeException {
    public PosteIntrouvableException(UUID id) {
        super("Poste introuvable : " + id);
    }
}
