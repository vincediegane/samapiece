package sn.samapiece.iam.security;

import sn.samapiece.iam.Agent;
import sn.samapiece.referentiel.Poste;

public final class PerimetrePoste {

    private PerimetrePoste() {
    }

    public static boolean estDansPerimetre(Agent appelant, Poste posteCible) {
        return switch (appelant.getRole()) {
            case AGENT, CHEF_POSTE -> appelant.getPoste().getId().equals(posteCible.getId());
            case ADMIN_REGIONAL, ADMIN_NATIONAL ->
                    PerimetreRegional.estDansPerimetreRegion(appelant, posteCible.getRegion().getId());
            default -> false;
        };
    }
}
