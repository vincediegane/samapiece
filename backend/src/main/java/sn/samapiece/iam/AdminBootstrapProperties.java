package sn.samapiece.iam;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "samapiece.bootstrap-admin")
public class AdminBootstrapProperties {

    private boolean enabled = false;
    private String matricule;
    private String nom;
    private String posteId;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMatricule() {
        return matricule;
    }

    public void setMatricule(String matricule) {
        this.matricule = matricule;
    }

    public String getNom() {
        return nom;
    }

    public void setNom(String nom) {
        this.nom = nom;
    }

    public String getPosteId() {
        return posteId;
    }

    public void setPosteId(String posteId) {
        this.posteId = posteId;
    }
}
