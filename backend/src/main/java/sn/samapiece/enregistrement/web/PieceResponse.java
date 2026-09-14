package sn.samapiece.enregistrement.web;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import sn.samapiece.enregistrement.Piece;

public record PieceResponse(
        UUID id,
        String numeroFiche,
        UUID posteId,
        UUID agentCreateurId,
        String typeDocument,
        String nomTitulaire,
        String prenomTitulaire,
        String numeroDocumentMasque,
        LocalDate dateNaissanceTitulaire,
        LocalDate dateDepot,
        String etatDocument,
        String statut,
        String remarques,
        OffsetDateTime creeLe,
        boolean creeMalgreDoublon) {

    public static PieceResponse of(Piece piece) {
        return new PieceResponse(
                piece.getId(),
                piece.getNumeroFiche(),
                piece.getPoste().getId(),
                piece.getAgentCreateur().getId(),
                piece.getTypeDocument().name(),
                piece.getNomTitulaire(),
                piece.getPrenomTitulaire(),
                piece.getNumeroDocumentMasque(),
                piece.getDateNaissanceTitulaire(),
                piece.getDateDepot(),
                piece.getEtatDocument(),
                piece.getStatut().name(),
                piece.getRemarques(),
                piece.getCreeLe(),
                piece.isCreeMalgreDoublon());
    }
}
