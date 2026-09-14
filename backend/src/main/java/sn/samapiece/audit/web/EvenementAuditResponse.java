package sn.samapiece.audit.web;

import java.time.OffsetDateTime;
import java.util.UUID;
import sn.samapiece.audit.EvenementAudit;

public record EvenementAuditResponse(
        UUID id,
        UUID acteurId,
        String typeActeur,
        String action,
        String entiteCible,
        UUID entiteCibleId,
        String details,
        String adresseIp,
        OffsetDateTime horodatage) {

    public static EvenementAuditResponse of(EvenementAudit evenementAudit) {
        return new EvenementAuditResponse(
                evenementAudit.getId(),
                evenementAudit.getActeurId(),
                evenementAudit.getTypeActeur(),
                evenementAudit.getAction(),
                evenementAudit.getEntiteCible(),
                evenementAudit.getEntiteCibleId(),
                evenementAudit.getDetails(),
                evenementAudit.getAdresseIp(),
                evenementAudit.getHorodatage());
    }
}
