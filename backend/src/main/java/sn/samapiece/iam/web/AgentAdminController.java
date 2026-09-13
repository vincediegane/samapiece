package sn.samapiece.iam.web;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.samapiece.iam.AgentAdminService;

@RestController
@RequestMapping("/api/v1/agents")
public class AgentAdminController {

    private final AgentAdminService agentAdminService;

    public AgentAdminController(AgentAdminService agentAdminService) {
        this.agentAdminService = agentAdminService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')")
    public List<AgentResponse> lister() {
        return agentAdminService.lister();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')")
    public ResponseEntity<CreerAgentResponse> creer(@Valid @RequestBody CreerAgentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(agentAdminService.creer(request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')")
    public ResponseEntity<AgentResponse> modifier(
            @PathVariable UUID id, @RequestBody ModifierAgentRequest request) {
        return ResponseEntity.ok(agentAdminService.modifier(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')")
    public ResponseEntity<Void> desactiver(@PathVariable UUID id) {
        agentAdminService.desactiver(id);
        return ResponseEntity.noContent().build();
    }
}
