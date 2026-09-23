package sn.samapiece.iam;

public class MotDePasseActuelInvalideException extends RuntimeException {
    public MotDePasseActuelInvalideException() {
        super("Le mot de passe actuel est invalide.");
    }
}
