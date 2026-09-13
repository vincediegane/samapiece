package sn.samapiece.recherche.web;

import java.time.LocalDate;
import sn.samapiece.enregistrement.TypeDocument;

public record RecherchePubliqueRequest(
        TypeDocument typeDocument,
        String nomTitulaire,
        String prenomTitulaire,
        String numeroDocument,
        LocalDate dateNaissanceTitulaire) {

    public boolean estSuffisant() {
        boolean typeEtNomPresents = typeDocument != null
                && nomTitulaire != null
                && !nomTitulaire.isBlank();
        boolean auMoinsUnDiscriminant =
                (numeroDocument != null && !numeroDocument.isBlank())
                        || dateNaissanceTitulaire != null;
        return typeEtNomPresents && auMoinsUnDiscriminant;
    }
}
