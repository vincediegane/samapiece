package sn.samapiece.referentiel.web;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.samapiece.audit.ActionAuditee;
import sn.samapiece.referentiel.ReferentielAdminService;

@RestController
@RequestMapping("/api/v1/regions")
public class RegionAdminController {

    private final ReferentielAdminService referentielAdminService;

    public RegionAdminController(ReferentielAdminService referentielAdminService) {
        this.referentielAdminService = referentielAdminService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN_NATIONAL')")
    @ActionAuditee(action = "REGION_CREEE", entiteCible = "REGION")
    public ResponseEntity<RegionResponse> creer(@Valid @RequestBody CreerRegionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(referentielAdminService.creerRegion(request));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN_NATIONAL')")
    public List<RegionResponse> lister() {
        return referentielAdminService.listerRegions();
    }
}
