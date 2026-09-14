package sn.samapiece.reporting;

import java.util.UUID;

public record StatistiquesPosteResponse(
        UUID posteId,
        String posteNom,
        long nombrePiecesEnAttente,
        Double ancienneteMoyenneJours,
        Long ancienneteMaxJours,
        int seuilAncienneteJours,
        long nombrePiecesDepassantSeuil) {
}
