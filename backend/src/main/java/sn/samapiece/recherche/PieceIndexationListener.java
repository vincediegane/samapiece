package sn.samapiece.recherche;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import sn.samapiece.enregistrement.PieceIndexableEvent;

@Component
public class PieceIndexationListener {

    private final PieceRechercheIndexService indexService;

    public PieceIndexationListener(PieceRechercheIndexService indexService) {
        this.indexService = indexService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void surPieceIndexable(PieceIndexableEvent evenement) {
        indexService.indexer(evenement.document());
    }
}
