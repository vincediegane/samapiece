package sn.samapiece.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "evenement_audit")
public class EvenementAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "acteur_id")
    private UUID acteurId;

    @Column(name = "type_acteur", nullable = false)
    private String typeActeur;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "entite_cible", nullable = false)
    private String entiteCible;

    @Column(name = "entite_cible_id")
    private UUID entiteCibleId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", nullable = false, columnDefinition = "jsonb")
    private String details;

    @Column(name = "adresse_ip")
    private String adresseIp;

    @Column(name = "horodatage", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime horodatage;

    protected EvenementAudit() {
    }

    public EvenementAudit(
            UUID acteurId,
            String typeActeur,
            String action,
            String entiteCible,
            UUID entiteCibleId,
            String details,
            String adresseIp) {
        this.acteurId = acteurId;
        this.typeActeur = typeActeur;
        this.action = action;
        this.entiteCible = entiteCible;
        this.entiteCibleId = entiteCibleId;
        this.details = details;
        this.adresseIp = adresseIp;
    }

    public UUID getId() {
        return id;
    }

    public UUID getActeurId() {
        return acteurId;
    }

    public String getTypeActeur() {
        return typeActeur;
    }

    public String getAction() {
        return action;
    }

    public String getEntiteCible() {
        return entiteCible;
    }

    public UUID getEntiteCibleId() {
        return entiteCibleId;
    }

    public String getDetails() {
        return details;
    }

    public String getAdresseIp() {
        return adresseIp;
    }

    public OffsetDateTime getHorodatage() {
        return horodatage;
    }
}
