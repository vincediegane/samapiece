package sn.samapiece.referentiel.web;

import java.util.UUID;
import sn.samapiece.referentiel.Region;

public record RegionResponse(UUID id, String nom) {

    public static RegionResponse from(Region region) {
        return new RegionResponse(region.getId(), region.getNom());
    }
}
