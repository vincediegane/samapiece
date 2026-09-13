package sn.samapiece.iam.web;

import java.util.UUID;
import sn.samapiece.iam.Agent;

public record CreerAgentResponse(
        UUID id, String matricule, String nom, String role, UUID posteId,
        boolean actif, String motDePasseTemporaire) {

    public static CreerAgentResponse of(Agent agent, String motDePasseTemporaire) {
        return new CreerAgentResponse(
                agent.getId(), agent.getMatricule(), agent.getNom(), agent.getRole().name(),
                agent.getPoste().getId(), agent.isActif(), motDePasseTemporaire);
    }
}
