package sn.samapiece.iam.web;

import java.time.OffsetDateTime;
import java.util.UUID;
import sn.samapiece.iam.Agent;

public record AgentResponse(
        UUID id, String matricule, String nom, String role, UUID posteId, String posteNom,
        boolean actif, OffsetDateTime creeLe) {

    public static AgentResponse of(Agent agent) {
        return new AgentResponse(
                agent.getId(), agent.getMatricule(), agent.getNom(), agent.getRole().name(),
                agent.getPoste().getId(), agent.getPoste().getNom(), agent.isActif(), agent.getCreeLe());
    }
}
