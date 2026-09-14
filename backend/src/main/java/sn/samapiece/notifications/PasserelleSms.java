package sn.samapiece.notifications;

/**
 * Contrat stable d'envoi de SMS, destine aux futurs modules #22 (Alertes) et #23
 * (worker metier). L'implementation gere deja en interne la resilience de transport
 * (timeout, erreurs 5xx, erreurs reseau) via une file RabbitMQ technique dediee
 * (sms.retry.30s / sms.retry.2m / sms.retry.10m / sms.consume / sms.dead-letter),
 * invisible aux appelants. Un appelant ne doit PAS re-implementer son propre retry
 * technique par-dessus cette interface.
 *
 * <p>envoyer(...) ne garantit pas une livraison synchrone : seulement la prise en
 * charge (tentative immediate, ou mise en file de retry si la tentative immediate
 * echoue).
 */
public interface PasserelleSms {

    /**
     * @throws IllegalArgumentException si destinataire est null, ou si message est
     *         null/vide (erreur de programmation de l'appelant — jamais pour un
     *         echec de transport, qui est gere en interne).
     */
    void envoyer(NumeroTelephone destinataire, String message);
}
