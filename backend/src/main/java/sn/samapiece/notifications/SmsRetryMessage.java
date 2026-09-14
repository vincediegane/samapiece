package sn.samapiece.notifications;

/**
 * Message transporte dans la file de retry technique. {@code destinataire} est la valeur
 * brute ({@link NumeroTelephone#valeurBrute()}) — necessaire pour que {@link SmsRetryListener}
 * puisse rappeler la passerelle. Le critere de masquage porte sur les logs applicatifs, pas
 * sur le corps des messages AMQP : tout consommateur doit reconstruire un
 * {@link NumeroTelephone} via {@code NumeroTelephone.de(destinataire())} avant tout log.
 *
 * <p>{@code nombreTentatives} compte les tentatives deja effectuees en file (n'inclut pas la
 * tentative directe initiale) ; la valeur 1 correspond a la premiere republication (etage
 * {@code sms.retry.30s}).
 */
public record SmsRetryMessage(String destinataire, String message, int nombreTentatives) {}
