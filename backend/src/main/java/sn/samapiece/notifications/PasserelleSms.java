package sn.samapiece.notifications;

/**
 * Contrat d'envoi de SMS. L'envoi est synchrone et sans retry : un echec de transport (timeout,
 * erreur reseau, reponse non-2xx) leve {@link EnvoiSmsException}, et c'est a l'appelant de decider
 * quoi en faire (ex. {@code AlerteCorrespondanceWorker} reprogramme l'envoi avec backoff).
 */
public interface PasserelleSms {

    /**
     * @throws IllegalArgumentException si destinataire est null, ou si message est
     *         null/vide (erreur de programmation de l'appelant).
     * @throws EnvoiSmsException si la passerelle est injoignable ou refuse l'envoi.
     */
    void envoyer(NumeroTelephone destinataire, String message);
}
