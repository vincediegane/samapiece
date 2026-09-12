package sn.samapiece.referentiel.web;

import java.util.UUID;
import sn.samapiece.referentiel.Poste;

public record PosteResponse(
        UUID id,
        String nom,
        String type,
        String adresse,
        String telephone,
        String horaires,
        Double latitude,
        Double longitude,
        RegionResume region) {

    public record RegionResume(UUID id, String nom) {
    }

    public static PosteResponse from(Poste poste) {
        return new PosteResponse(
                poste.getId(),
                poste.getNom(),
                poste.getType().name(),
                poste.getAdresse(),
                poste.getTelephone(),
                poste.getHoraires(),
                poste.getLatitude(),
                poste.getLongitude(),
                new RegionResume(poste.getRegion().getId(), poste.getRegion().getNom()));
    }
}
