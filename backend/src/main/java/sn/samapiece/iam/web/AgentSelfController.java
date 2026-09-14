package sn.samapiece.iam.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.samapiece.iam.AgentSelfService;

@RestController
@RequestMapping("/api/v1/agents")
public class AgentSelfController {

    private final AgentSelfService agentSelfService;

    public AgentSelfController(AgentSelfService agentSelfService) {
        this.agentSelfService = agentSelfService;
    }

    @GetMapping("/moi")
    public ResponseEntity<AgentResponse> moi() {
        return ResponseEntity.ok(agentSelfService.moi());
    }
}
