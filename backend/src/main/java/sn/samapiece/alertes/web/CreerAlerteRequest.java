package sn.samapiece.alertes.web;

import java.time.LocalDate;
import sn.samapiece.enregistrement.TypeDocument;

public record CreerAlerteRequest(
        TypeDocument typeDocument,
        String nomTitulaire,
        String prenomTitulaire,
        String numeroDocument,
        LocalDate dateNaissanceTitulaire,
        String contact) {

    public boolean estSuffisant() {
        boolean typeEtNomPresents = typeDocument != null
                && nomTitulaire != null && !nomTitulaire.isBlank();
        boolean auMoinsUnDiscriminant =
                (numeroDocument != null && !numeroDocument.isBlank())
                        || dateNaissanceTitulaire != null;
        return typeEtNomPresents && auMoinsUnDiscriminant;
    }
}
