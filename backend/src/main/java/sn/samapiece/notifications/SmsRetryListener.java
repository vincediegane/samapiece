package sn.samapiece.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Consomme la file {@code sms.consume} : retente l'envoi, republie vers l'etage de backoff
 * suivant en cas de nouvel echec, ou route vers la dead-letter apres le nombre maximal de
 * tentatives configure.
 */
@Component
public class SmsRetryListener {

    private static final Logger LOG = LoggerFactory.getLogger(SmsRetryListener.class);

    private final PasserelleSmsHttpClient passerelleSmsHttpClient;
    private final RabbitTemplate rabbitTemplate;
    private final SmsProperties proprietes;

    public SmsRetryListener(
            PasserelleSmsHttpClient passerelleSmsHttpClient, RabbitTemplate rabbitTemplate, SmsProperties proprietes) {
        this.passerelleSmsHttpClient = passerelleSmsHttpClient;
        this.rabbitTemplate = rabbitTemplate;
        this.proprietes = proprietes;
    }

    @RabbitListener(queues = SmsRabbitConfig.QUEUE_CONSUME)
    public void consommer(SmsRetryMessage messageRetry) {
        NumeroTelephone destinataire = NumeroTelephone.de(messageRetry.destinataire());
        boolean succes = passerelleSmsHttpClient.tenterEnvoiDirect(destinataire, messageRetry.message());
        if (succes) {
            LOG.info(
                    "SMS envoye avec succes apres {} tentative(s) en file pour {}",
                    messageRetry.nombreTentatives(),
                    destinataire);
            return;
        }
        int tentativeSuivante = messageRetry.nombreTentatives() + 1;
        if (tentativeSuivante > proprietes.getMaxTentatives()) {
            LOG.error(
                    "Nombre maximal de tentatives ({}) atteint pour {}, envoi vers sms.dead-letter",
                    proprietes.getMaxTentatives(),
                    destinataire);
            rabbitTemplate.convertAndSend(
                    SmsRabbitConfig.EXCHANGE,
                    SmsRabbitConfig.QUEUE_DEAD_LETTER,
                    new SmsRetryMessage(messageRetry.destinataire(), messageRetry.message(), tentativeSuivante));
            return;
        }
        String routingKeySuivant = routingKeyPourTentative(tentativeSuivante);
        LOG.warn(
                "Nouvel echec pour {}, republication vers {} (tentative {})",
                destinataire,
                routingKeySuivant,
                tentativeSuivante);
        rabbitTemplate.convertAndSend(
                SmsRabbitConfig.EXCHANGE,
                routingKeySuivant,
                new SmsRetryMessage(messageRetry.destinataire(), messageRetry.message(), tentativeSuivante));
    }

    private String routingKeyPourTentative(int tentative) {
        return switch (tentative) {
            case 1 -> SmsRabbitConfig.QUEUE_RETRY_30S;
            case 2 -> SmsRabbitConfig.QUEUE_RETRY_2M;
            default -> SmsRabbitConfig.QUEUE_RETRY_10M;
        };
    }
}
