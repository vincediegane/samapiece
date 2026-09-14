package sn.samapiece.audit;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sn.samapiece.audit.web.EvenementAuditResponse;

@Service
public class EvenementAuditService {

    private final EvenementAuditRepository evenementAuditRepository;

    public EvenementAuditService(EvenementAuditRepository evenementAuditRepository) {
        this.evenementAuditRepository = evenementAuditRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enregistrer(
            UUID acteurId,
            String typeActeur,
            String action,
            String entiteCible,
            UUID entiteCibleId,
            String details,
            String adresseIp) {
        evenementAuditRepository.save(new EvenementAudit(
                acteurId, typeActeur, action, entiteCible, entiteCibleId, details, adresseIp));
    }

    @Transactional(readOnly = true)
    public Page<EvenementAuditResponse> lister(Pageable pageable) {
        return evenementAuditRepository.findAll(pageable).map(EvenementAuditResponse::of);
    }
}
