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
    public Page<EvenementAuditResponse> lister(String action, String entiteCible, Pageable pageable) {
        String actionFiltre = normaliser(action);
        String entiteFiltre = normaliser(entiteCible);
        Page<EvenementAudit> page;
        if (actionFiltre != null && entiteFiltre != null) {
            page = evenementAuditRepository.findByActionAndEntiteCible(actionFiltre, entiteFiltre, pageable);
        } else if (actionFiltre != null) {
            page = evenementAuditRepository.findByAction(actionFiltre, pageable);
        } else if (entiteFiltre != null) {
            page = evenementAuditRepository.findByEntiteCible(entiteFiltre, pageable);
        } else {
            page = evenementAuditRepository.findAll(pageable);
        }
        return page.map(EvenementAuditResponse::of);
    }

    private static String normaliser(String valeur) {
        return valeur == null || valeur.isBlank() ? null : valeur.trim();
    }
}
