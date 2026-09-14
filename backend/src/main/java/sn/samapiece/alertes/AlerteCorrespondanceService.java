package sn.samapiece.alertes;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import sn.samapiece.enregistrement.NumeroDocumentHasher;
import sn.samapiece.enregistrement.PieceDisponibleEvent;

/**
 * Rapprochement en-process (producteur) entre les alertes actives et une {@link PieceDisponibleEvent}
 * fraichement publiee. La verification du numero de document, qui necessite le numero en clair,
 * ne peut se faire qu'ici : voir Decision tranchee 1 de la spec #23 pour la justification du
 * choix de ne pas la deporter dans le consumer AMQP.
 */
@Service
public class AlerteCorrespondanceService {

    private static final Logger LOG = LoggerFactory.getLogger(AlerteCorrespondanceService.class);

    private final AlerteRepository alerteRepository;
    private final NumeroDocumentHasher numeroDocumentHasher;
    private final RabbitTemplate rabbitTemplate;

    public AlerteCorrespondanceService(
            AlerteRepository alerteRepository,
            NumeroDocumentHasher numeroDocumentHasher,
            RabbitTemplate rabbitTemplate) {
        this.alerteRepository = alerteRepository;
        this.numeroDocumentHasher = numeroDocumentHasher;
        this.rabbitTemplate = rabbitTemplate;
    }

    public void trouverEtNotifier(PieceDisponibleEvent evenement) {
        List<Alerte> alertesCandidates = alerteRepository.findByActiveTrueAndTypeDocumentAndNomTitulaireIgnoreCase(
                evenement.typeDocument(), evenement.nomTitulaire());

        for (Alerte alerte : alertesCandidates) {
            if (!correspond(alerte, evenement)) {
                continue;
            }
            LOG.info(
                    "Correspondance trouvee entre l'alerte {} et la piece {}, publication du message.",
                    alerte.getId(),
                    evenement.pieceId());
            rabbitTemplate.convertAndSend(
                    AlerteCorrespondanceRabbitConfig.EXCHANGE,
                    AlerteCorrespondanceRabbitConfig.QUEUE_CONSUME,
                    new AlerteCorrespondanceMessage(alerte.getId(), evenement.pieceId(), 0));
        }
    }

    private boolean correspond(Alerte alerte, PieceDisponibleEvent evenement) {
        if (alerte.getPrenomTitulaire() != null && !alerte.getPrenomTitulaire().isBlank()
                && !alerte.getPrenomTitulaire().equalsIgnoreCase(evenement.prenomTitulaire())) {
            return false;
        }
        if (alerte.getDateNaissanceTitulaire() != null
                && !alerte.getDateNaissanceTitulaire().equals(evenement.dateNaissanceTitulaire())) {
            return false;
        }
        boolean numeroDocumentAlerteFourni =
                alerte.getNumeroDocumentHash() != null && alerte.getNumeroDocumentSel() != null;
        if (numeroDocumentAlerteFourni && !numeroDocumentHasher.verifier(
                evenement.numeroDocumentClair(), alerte.getNumeroDocumentSel(), alerte.getNumeroDocumentHash())) {
            return false;
        }
        return true;
    }
}
