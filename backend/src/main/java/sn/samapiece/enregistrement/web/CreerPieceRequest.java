package sn.samapiece.enregistrement.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import sn.samapiece.enregistrement.TypeDocument;

public record CreerPieceRequest(
        @NotNull TypeDocument typeDocument,
        @NotBlank String nomTitulaire,
        @NotBlank String prenomTitulaire,
        @NotBlank String numeroDocument,
        LocalDate dateNaissanceTitulaire,
        @NotNull LocalDate dateDepot,
        String etatDocument,
        String remarques,
        boolean confirmerMalgreDoublon) {
}
