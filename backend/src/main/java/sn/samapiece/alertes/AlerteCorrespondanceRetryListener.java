package sn.samapiece.alertes;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sn.samapiece.enregistrement.Piece;
import sn.samapiece.enregistrement.PieceRepository;
import sn.samapiece.notifications.NumeroTelephone;
import sn.samapiece.notifications.PasserelleSms;

/**
 * Consomme la file {@code alerte.correspondance.consume} : recharge l'alerte et la piece
 * fraiches, re-verifie l'etat de l'alerte, declenche l'envoi SMS via {@link PasserelleSms},
 * et gere le retry/backoff/dead-letter applicatif en cas d'exception synchrone (voir Decision
 * tranchee 3 de la spec #23 pour le perimetre exact de "l'echec" couvert ici).
 */
@Component
public class AlerteCorrespondanceRetryListener {

    private static final Logger LOG = LoggerFactory.getLogger(AlerteCorrespondanceRetryListener.class);

    private final AlerteRepository alerteRepository;
    private final PieceRepository pieceRepository;
    private final AlerteContactChiffrementService chiffrementService;
    private final PasserelleSms passerelleSms;
    private final RabbitTemplate rabbitTemplate;
    private final AlerteCorrespondanceProperties proprietes;

    public AlerteCorrespondanceRetryListener(
            AlerteRepository alerteRepository,
            PieceRepository pieceRepository,
            AlerteContactChiffrementService chiffrementService,
            PasserelleSms passerelleSms,
            RabbitTemplate rabbitTemplate,
            AlerteCorrespondanceProperties proprietes) {
        this.alerteRepository = alerteRepository;
        this.pieceRepository = pieceRepository;
        this.chiffrementService = chiffrementService;
        this.passerelleSms = passerelleSms;
        this.rabbitTemplate = rabbitTemplate;
        this.proprietes = proprietes;
    }

    @Transactional
    @RabbitListener(queues = AlerteCorrespondanceRabbitConfig.QUEUE_CONSUME)
    public void consommer(AlerteCorrespondanceMessage message) {
        try {
            traiter(message);
        } catch (Exception exception) {
            gererEchec(message, exception);
        }
    }

    private void traiter(AlerteCorrespondanceMessage message) {
        Alerte alerte = alerteRepository.findById(message.alerteId())
                .orElseThrow(() -> new IllegalStateException("Alerte introuvable pour la notification de correspondance."));
        if (!alerte.isActive()) {
            LOG.info("Alerte {} desinscrite avant l'envoi, notification annulee.", message.alerteId());
            return;
        }
        Piece piece = pieceRepository.findById(message.pieceId())
                .orElseThrow(() -> new IllegalStateException("Piece introuvable pour la notification de correspondance."));
        byte[] contactClair = chiffrementService.dechiffrer(alerte.getContactChiffre(), alerte.getContactIv());
        NumeroTelephone destinataire = NumeroTelephone.de(new String(contactClair, StandardCharsets.UTF_8));
        passerelleSms.envoyer(destinataire, construireMessage(piece));
    }

    private void gererEchec(AlerteCorrespondanceMessage message, Exception exception) {
        int tentativeSuivante = message.nombreTentatives() + 1;
        if (tentativeSuivante > proprietes.getMaxTentatives()) {
            LOG.error(
                    "Nombre maximal de tentatives ({}) atteint pour l'alerte {} / piece {}, envoi vers {}",
                    proprietes.getMaxTentatives(),
                    message.alerteId(),
                    message.pieceId(),
                    AlerteCorrespondanceRabbitConfig.QUEUE_DEAD_LETTER,
                    exception);
            rabbitTemplate.convertAndSend(
                    AlerteCorrespondanceRabbitConfig.EXCHANGE,
                    AlerteCorrespondanceRabbitConfig.QUEUE_DEAD_LETTER,
                    new AlerteCorrespondanceMessage(message.alerteId(), message.pieceId(), tentativeSuivante));
            return;
        }
        String routingKeySuivant = routingKeyPourTentative(tentativeSuivante);
        LOG.warn(
                "Nouvel echec pour l'alerte {} / piece {}, republication vers {} (tentative {})",
                message.alerteId(),
                message.pieceId(),
                routingKeySuivant,
                tentativeSuivante,
                exception);
        rabbitTemplate.convertAndSend(
                AlerteCorrespondanceRabbitConfig.EXCHANGE,
                routingKeySuivant,
                new AlerteCorrespondanceMessage(message.alerteId(), message.pieceId(), tentativeSuivante));
    }

    private String routingKeyPourTentative(int tentative) {
        return switch (tentative) {
            case 1 -> AlerteCorrespondanceRabbitConfig.QUEUE_RETRY_30S;
            case 2 -> AlerteCorrespondanceRabbitConfig.QUEUE_RETRY_2M;
            default -> AlerteCorrespondanceRabbitConfig.QUEUE_RETRY_10M;
        };
    }

    private String construireMessage(Piece piece) {
        return "Une piece correspondant a votre alerte est disponible (fiche " + piece.getNumeroFiche()
                + ") au poste " + piece.getPoste().getNom() + ". Presentez-vous avec une piece d'identite.";
    }
}
