package sn.samapiece.iam;

public class MotDePasseTemporaireNonChangeException extends RuntimeException {
    public MotDePasseTemporaireNonChangeException() {
        super("Le mot de passe temporaire doit etre change avant d'acceder a cette ressource.");
    }
}
