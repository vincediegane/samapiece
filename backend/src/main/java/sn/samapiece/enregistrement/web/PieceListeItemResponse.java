package sn.samapiece.enregistrement.web;

import java.time.LocalDate;
import java.util.UUID;
import sn.samapiece.enregistrement.Piece;
import sn.samapiece.enregistrement.StatutPiece;
import sn.samapiece.enregistrement.TypeDocument;

public record PieceListeItemResponse(
        UUID id,
        String numeroFiche,
        TypeDocument typeDocument,
        StatutPiece statut,
        LocalDate dateDepot,
        long ancienneteJours,
        boolean depasseSeuil) {

    public static PieceListeItemResponse of(Piece piece, long ancienneteJours, boolean depasseSeuil) {
        return new PieceListeItemResponse(
                piece.getId(),
                piece.getNumeroFiche(),
                piece.getTypeDocument(),
                piece.getStatut(),
                piece.getDateDepot(),
                ancienneteJours,
                depasseSeuil);
    }
}
