package sn.samapiece.iam.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import sn.samapiece.iam.Role;

public record CreerAgentRequest(
        @NotNull UUID posteId,
        @NotBlank String matricule,
        @NotBlank String nom,
        @NotNull Role role) {
}
