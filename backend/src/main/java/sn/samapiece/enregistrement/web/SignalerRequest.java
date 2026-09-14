package sn.samapiece.enregistrement.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import sn.samapiece.enregistrement.StatutPiece;

public record SignalerRequest(
        @NotNull StatutPiece statutCible,
        @NotBlank String motif) {
}
