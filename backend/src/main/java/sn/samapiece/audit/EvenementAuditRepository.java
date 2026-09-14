package sn.samapiece.audit;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

public interface EvenementAuditRepository extends Repository<EvenementAudit, UUID> {

    EvenementAudit save(EvenementAudit evenementAudit);

    Page<EvenementAudit> findAll(Pageable pageable);

    Optional<EvenementAudit> findById(UUID id);
}
