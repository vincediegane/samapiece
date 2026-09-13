package sn.samapiece.enregistrement.photo;

public class PhotoDejaExistanteException extends RuntimeException {
    public PhotoDejaExistanteException(String message) {
        super(message);
    }
}
