package sn.samapiece.referentiel.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreerRegionRequest(@NotBlank @Size(max = 255) String nom) {
}
