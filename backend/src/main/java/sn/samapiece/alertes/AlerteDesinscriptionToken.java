package sn.samapiece.alertes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "alerte_desinscription_token")
public class AlerteDesinscriptionToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "alerte_id", nullable = false)
    private UUID alerteId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expire_le", nullable = false)
    private OffsetDateTime expireLe;

    @Column(name = "consomme_le")
    private OffsetDateTime consommeLe;

    @Column(name = "cree_le", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creeLe;

    protected AlerteDesinscriptionToken() {
    }

    public AlerteDesinscriptionToken(UUID alerteId, String tokenHash, OffsetDateTime expireLe) {
        this.alerteId = alerteId;
        this.tokenHash = tokenHash;
        this.expireLe = expireLe;
    }

    public void consommer() {
        this.consommeLe = OffsetDateTime.now();
    }

    public boolean estValide(OffsetDateTime maintenant) {
        return consommeLe == null && expireLe.isAfter(maintenant);
    }

    public UUID getId() {
        return id;
    }

    public UUID getAlerteId() {
        return alerteId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public OffsetDateTime getExpireLe() {
        return expireLe;
    }

    public OffsetDateTime getConsommeLe() {
        return consommeLe;
    }

    public OffsetDateTime getCreeLe() {
        return creeLe;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AlerteDesinscriptionToken other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
