package sn.samapiece.recherche;

import java.util.UUID;

public record PieceRechercheDocument(
        UUID id,
        String typeDocument,
        String nomTitulaire,
        String prenomTitulaire,
        String poste,
        String statut) {
}
