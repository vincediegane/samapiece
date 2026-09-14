package sn.samapiece.enregistrement.web;

import jakarta.validation.constraints.NotBlank;

public record DeblocageRequest(
        @NotBlank String motif) {
}
