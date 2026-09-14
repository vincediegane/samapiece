package sn.samapiece.audit.web;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.samapiece.audit.EvenementAuditService;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final EvenementAuditService evenementAuditService;

    public AuditController(EvenementAuditService evenementAuditService) {
        this.evenementAuditService = evenementAuditService;
    }

    @GetMapping("/evenements")
    @PreAuthorize("hasAnyRole('AUDITEUR','ADMIN_NATIONAL')")
    public Page<EvenementAuditResponse> lister(
            @PageableDefault(size = 20, sort = "horodatage", direction = Sort.Direction.DESC) Pageable pageable) {
        return evenementAuditService.lister(pageable);
    }
}
