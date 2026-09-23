package sn.samapiece.iam.web;

import jakarta.validation.constraints.NotBlank;

public record ChangerMotDePasseRequest(
        @NotBlank String motDePasseActuel,
        @NotBlank String nouveauMotDePasse) {
}
