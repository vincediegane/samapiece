package sn.samapiece.alertes.web;

public record CreerAlerteResponse(String message) {

    public static CreerAlerteResponse confirmee() {
        return new CreerAlerteResponse("Alerte enregistrée. Un lien de désinscription a été envoyé par SMS.");
    }
}
