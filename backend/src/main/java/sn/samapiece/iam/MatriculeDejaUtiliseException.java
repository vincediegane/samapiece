package sn.samapiece.iam;

public class MatriculeDejaUtiliseException extends RuntimeException {
    public MatriculeDejaUtiliseException(String matricule) {
        super("Matricule déjà utilisé : " + matricule);
    }
}
