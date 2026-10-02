package sn.samapiece.alertes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Notification SMS a envoyer pour une correspondance alerte &lt;-&gt; piece. Sert de file d'attente
 * persistante : {@link AlerteCorrespondanceWorker} traite les lignes {@code EN_ATTENTE} dont
 * {@code prochaineTentative} est echue. Ne porte aucune donnee personnelle (identifiants seulement).
 */
@Entity
@Table(name = "notification_correspondance")
public class NotificationCorrespondance {

    public enum Statut {
        EN_ATTENTE,
        ENVOYEE,
        ANNULEE,
        ECHEC
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "alerte_id", nullable = false)
    private UUID alerteId;

    @Column(name = "piece_id", nullable = false)
    private UUID pieceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Statut statut = Statut.EN_ATTENTE;

    @Column(name = "nombre_tentatives", nullable = false)
    private int nombreTentatives;

    @Column(name = "prochaine_tentative", nullable = false)
    private OffsetDateTime prochaineTentative;

    @Column(name = "cree_le", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creeLe;

    protected NotificationCorrespondance() {
    }

    public NotificationCorrespondance(UUID alerteId, UUID pieceId, OffsetDateTime prochaineTentative) {
        this.alerteId = alerteId;
        this.pieceId = pieceId;
        this.prochaineTentative = prochaineTentative;
    }

    public void marquerEnvoyee() {
        this.statut = Statut.ENVOYEE;
    }

    public void annuler() {
        this.statut = Statut.ANNULEE;
    }

    public void marquerEchec() {
        this.nombreTentatives++;
        this.statut = Statut.ECHEC;
    }

    public void reprogrammer(OffsetDateTime prochaineTentative) {
        this.nombreTentatives++;
        this.prochaineTentative = prochaineTentative;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAlerteId() {
        return alerteId;
    }

    public UUID getPieceId() {
        return pieceId;
    }

    public Statut getStatut() {
        return statut;
    }

    public int getNombreTentatives() {
        return nombreTentatives;
    }

    public OffsetDateTime getProchaineTentative() {
        return prochaineTentative;
    }

    public OffsetDateTime getCreeLe() {
        return creeLe;
    }
}
