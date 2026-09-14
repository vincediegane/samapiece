package sn.samapiece.reporting.web;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.samapiece.reporting.StatistiquesPosteResponse;
import sn.samapiece.reporting.StatistiquesPosteService;

@RestController
@RequestMapping("/api/v1/statistiques")
public class StatistiquesController {

    private final StatistiquesPosteService statistiquesPosteService;

    public StatistiquesController(StatistiquesPosteService statistiquesPosteService) {
        this.statistiquesPosteService = statistiquesPosteService;
    }

    @GetMapping("/poste/{id}")
    @PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')")
    public ResponseEntity<StatistiquesPosteResponse> consulterPoste(@PathVariable UUID id) {
        return ResponseEntity.ok(statistiquesPosteService.consulter(id));
    }
}
