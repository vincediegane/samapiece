package sn.samapiece.enregistrement;

import java.util.UUID;

public class TransitionStatutInterditeException extends RuntimeException {
    public TransitionStatutInterditeException(UUID pieceId, StatutPiece statutActuel, String action) {
        super("Transition refusee pour la piece " + pieceId + " : action=" + action
                + ", statut actuel=" + statutActuel);
    }
}
