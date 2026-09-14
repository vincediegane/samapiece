package sn.samapiece.alertes;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import sn.samapiece.enregistrement.PieceDisponibleEvent;

@Component
public class AlerteCorrespondancePieceMatchingListener {

    private final AlerteCorrespondanceService alerteCorrespondanceService;

    public AlerteCorrespondancePieceMatchingListener(AlerteCorrespondanceService alerteCorrespondanceService) {
        this.alerteCorrespondanceService = alerteCorrespondanceService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void surPieceDisponible(PieceDisponibleEvent evenement) {
        alerteCorrespondanceService.trouverEtNotifier(evenement);
    }
}
