package sn.samapiece.reporting;

import java.util.List;
import java.util.UUID;

public record StatistiquesConsolideesResponse(
        String portee,
        UUID regionId,
        String regionNom,
        int seuilAncienneteJours,
        Totaux totaux,
        List<LignePoste> postes) {

    public record Totaux(
            long nombrePiecesEnAttente,
            Double ancienneteMoyenneJours,
            Long ancienneteMaxJours,
            long nombrePiecesDepassantSeuil,
            int nombrePostes,
            int nombrePostesEnDepassement) {}

    public record LignePoste(
            UUID posteId,
            String posteNom,
            String regionNom,
            long nombrePiecesEnAttente,
            Double ancienneteMoyenneJours,
            Long ancienneteMaxJours,
            long nombrePiecesDepassantSeuil) {}
}
