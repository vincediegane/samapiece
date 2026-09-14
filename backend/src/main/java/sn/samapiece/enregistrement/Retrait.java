package sn.samapiece.enregistrement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import sn.samapiece.iam.Agent;

@Entity
@Table(name = "retrait")
public class Retrait {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "piece_id", nullable = false)
    private Piece piece;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_validateur_id", nullable = false)
    private Agent agentValidateur;

    @Column(name = "nom_reclamant", nullable = false)
    private String nomReclamant;

    @Column(name = "piece_justificative_presentee", nullable = false)
    private String pieceJustificativePresentee;

    @Column(name = "cree_le", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creeLe;

    protected Retrait() {
    }

    public Retrait(Piece piece, Agent agentValidateur, String nomReclamant, String pieceJustificativePresentee) {
        this.piece = piece;
        this.agentValidateur = agentValidateur;
        this.nomReclamant = nomReclamant;
        this.pieceJustificativePresentee = pieceJustificativePresentee;
    }

    public UUID getId() {
        return id;
    }

    public Piece getPiece() {
        return piece;
    }

    public Agent getAgentValidateur() {
        return agentValidateur;
    }

    public String getNomReclamant() {
        return nomReclamant;
    }

    public String getPieceJustificativePresentee() {
        return pieceJustificativePresentee;
    }

    public OffsetDateTime getCreeLe() {
        return creeLe;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Retrait other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
