package sn.samapiece.notifications;

/**
 * Echec de transport lors de l'appel a la passerelle SMS. Le message ne contient que le numero
 * masque ({@link NumeroTelephone#toString()}), jamais la valeur brute.
 */
public class EnvoiSmsException extends RuntimeException {

    public EnvoiSmsException(NumeroTelephone destinataire, Throwable cause) {
        super("Echec de l'envoi du SMS vers " + destinataire, cause);
    }
}
