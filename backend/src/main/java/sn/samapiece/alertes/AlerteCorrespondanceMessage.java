package sn.samapiece.alertes;

import java.util.UUID;

/**
 * Payload AMQP mininal du rapprochement alerte&lt;-&gt;piece, serialise en JSON via le
 * {@code Jackson2JsonMessageConverter} partage (cf. {@code SmsRabbitConfig}). Aucun champ ne
 * porte de donnee personnelle (ni numero de document, ni contact, ni nom) : uniquement des
 * identifiants a recharger en base au moment de la consommation.
 *
 * <p>{@code nombreTentatives} : le message initial, publie par {@link AlerteCorrespondanceService},
 * part directement sur la file de consommation avec la valeur {@code 0} (aucune tentative
 * encore effectuee). A chaque echec dans {@code AlerteCorrespondanceRetryListener}, la valeur
 * republiee est incrementee de 1.
 */
public record AlerteCorrespondanceMessage(UUID alerteId, UUID pieceId, int nombreTentatives) {
}
