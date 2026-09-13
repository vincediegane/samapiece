package sn.samapiece.iam.web;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String matricule, @NotBlank String motDePasse) {
}
