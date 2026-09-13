package sn.samapiece.iam;

import org.springframework.security.core.GrantedAuthority;

public enum Role implements GrantedAuthority {
    AGENT,
    CHEF_POSTE,
    ADMIN_REGIONAL,
    ADMIN_NATIONAL,
    AUDITEUR;

    @Override
    public String getAuthority() {
        return "ROLE_" + name();
    }
}
