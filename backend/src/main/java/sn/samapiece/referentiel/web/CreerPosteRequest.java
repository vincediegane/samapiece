package sn.samapiece.referentiel.web;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import sn.samapiece.referentiel.TypePoste;

public record CreerPosteRequest(
        @NotNull UUID regionId,
        @NotBlank @Size(max = 255) String nom,
        @NotNull TypePoste type,
        @NotBlank @Size(max = 500) String adresse,
        @NotBlank @Size(max = 30) String telephone,
        @NotNull JsonNode horaires,
        @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @DecimalMin("-180") @DecimalMax("180") Double longitude) {
}
