package sn.samapiece.recherche.web;

import sn.samapiece.enregistrement.TypeDocument;
import sn.samapiece.referentiel.Poste;

public record RecherchePubliqueResponse(
        boolean trouve,
        TypeDocument typeDocument,
        PosteResume poste,
        String referenceDossier) {

    public record PosteResume(String nom, String adresse, String horaires, String telephone) {}

    public static RecherchePubliqueResponse nonTrouve() {
        return new RecherchePubliqueResponse(false, null, null, null);
    }

    public static RecherchePubliqueResponse trouve(TypeDocument typeDocument, Poste poste, String referenceDossier) {
        return new RecherchePubliqueResponse(
                true,
                typeDocument,
                new PosteResume(poste.getNom(), poste.getAdresse(), poste.getHoraires(), poste.getTelephone()),
                referenceDossier);
    }
}
