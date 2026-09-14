package sn.samapiece.enregistrement.web;

import jakarta.validation.constraints.NotBlank;

public record RetraitRequest(
        @NotBlank String nomReclamant,
        @NotBlank String pieceJustificativePresentee) {
}
