package sn.samapiece.iam;

import java.util.UUID;

public class AgentIntrouvableException extends RuntimeException {
    public AgentIntrouvableException(UUID id) {
        super("Agent introuvable : " + id);
    }
}
