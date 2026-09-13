package sn.samapiece.iam.security;

import java.util.UUID;
import sn.samapiece.iam.Agent;

public final class PerimetreRegional {

    private PerimetreRegional() {
    }

    /**
     * Vrai si l'agent appelant peut agir sur la region donnee : ADMIN_NATIONAL toujours,
     * ADMIN_REGIONAL uniquement si sa propre region correspond, faux pour tout autre role
     * (y compris CHEF_POSTE : le perimetre "region" ne le concerne pas). Reutilisable tel
     * quel par le futur ticket #26 (stats regionales).
     */
    public static boolean estDansPerimetreRegion(Agent appelant, UUID regionId) {
        return switch (appelant.getRole()) {
            case ADMIN_NATIONAL -> true;
            case ADMIN_REGIONAL -> appelant.getPoste().getRegion().getId().equals(regionId);
            default -> false;
        };
    }
}
