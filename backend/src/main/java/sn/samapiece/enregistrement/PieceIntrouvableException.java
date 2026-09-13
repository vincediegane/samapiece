package sn.samapiece.enregistrement;

import java.util.UUID;

public class PieceIntrouvableException extends RuntimeException {
    public PieceIntrouvableException(UUID id) {
        super("Piece introuvable : " + id);
    }
}
