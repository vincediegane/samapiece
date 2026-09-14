package sn.samapiece.enregistrement;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import sn.samapiece.iam.Agent;
import sn.samapiece.referentiel.Poste;

@Entity
@Table(name = "piece")
public class Piece {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "numero_fiche", nullable = false, unique = true, length = 50)
    private String numeroFiche;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "poste_id", nullable = false)
    private Poste poste;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_createur_id", nullable = false)
    private Agent agentCreateur;

    @Convert(converter = TypeDocumentConverter.class)
    @Column(name = "type_document", nullable = false, length = 30)
    private TypeDocument typeDocument;

    @Column(name = "nom_titulaire", nullable = false)
    private String nomTitulaire;

    @Column(name = "prenom_titulaire", nullable = false)
    private String prenomTitulaire;

    @Column(name = "numero_document_hash", nullable = false, length = 64)
    private String numeroDocumentHash;

    @Column(name = "numero_document_sel", nullable = false, length = 64)
    private String numeroDocumentSel;

    @Column(name = "numero_document_masque", nullable = false, length = 64)
    private String numeroDocumentMasque;

    @Column(name = "date_naissance_titulaire")
    private LocalDate dateNaissanceTitulaire;

    @Column(name = "date_depot", nullable = false)
    private LocalDate dateDepot;

    @Column(name = "etat_document")
    private String etatDocument;

    @Convert(converter = StatutPieceConverter.class)
    @Column(name = "statut", nullable = false, length = 20)
    private StatutPiece statut;

    @Column(name = "remarques")
    private String remarques;

    @Column(name = "cree_le", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creeLe;

    @Column(name = "maj_le", nullable = false, insertable = false)
    private OffsetDateTime majLe;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "signale_par_id")
    private Agent signalePar;

    @Column(name = "signale_le")
    private OffsetDateTime signaleLe;

    @Column(name = "motif_signalement")
    private String motifSignalement;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "debloque_par_id")
    private Agent debloquePar;

    @Column(name = "debloque_le")
    private OffsetDateTime debloqueLe;

    @Column(name = "motif_deblocage")
    private String motifDeblocage;

    @Column(name = "cree_malgre_doublon", nullable = false)
    private boolean creeMalgreDoublon;

    protected Piece() {
    }

    public Piece(
            String numeroFiche,
            Poste poste,
            Agent agentCreateur,
            TypeDocument typeDocument,
            String nomTitulaire,
            String prenomTitulaire,
            String numeroDocumentHash,
            String numeroDocumentSel,
            String numeroDocumentMasque,
            LocalDate dateNaissanceTitulaire,
            LocalDate dateDepot,
            String etatDocument,
            String remarques) {
        this(numeroFiche, poste, agentCreateur, typeDocument, nomTitulaire, prenomTitulaire,
                numeroDocumentHash, numeroDocumentSel, numeroDocumentMasque, dateNaissanceTitulaire,
                dateDepot, etatDocument, remarques, false);
    }

    public Piece(
            String numeroFiche,
            Poste poste,
            Agent agentCreateur,
            TypeDocument typeDocument,
            String nomTitulaire,
            String prenomTitulaire,
            String numeroDocumentHash,
            String numeroDocumentSel,
            String numeroDocumentMasque,
            LocalDate dateNaissanceTitulaire,
            LocalDate dateDepot,
            String etatDocument,
            String remarques,
            boolean creeMalgreDoublon) {
        this.numeroFiche = numeroFiche;
        this.poste = poste;
        this.agentCreateur = agentCreateur;
        this.typeDocument = typeDocument;
        this.nomTitulaire = nomTitulaire;
        this.prenomTitulaire = prenomTitulaire;
        this.numeroDocumentHash = numeroDocumentHash;
        this.numeroDocumentSel = numeroDocumentSel;
        this.numeroDocumentMasque = numeroDocumentMasque;
        this.dateNaissanceTitulaire = dateNaissanceTitulaire;
        this.dateDepot = dateDepot;
        this.etatDocument = etatDocument;
        this.statut = StatutPiece.DISPONIBLE;
        this.remarques = remarques;
        this.creeMalgreDoublon = creeMalgreDoublon;
    }

    public UUID getId() {
        return id;
    }

    public String getNumeroFiche() {
        return numeroFiche;
    }

    public Poste getPoste() {
        return poste;
    }

    public Agent getAgentCreateur() {
        return agentCreateur;
    }

    public TypeDocument getTypeDocument() {
        return typeDocument;
    }

    public String getNomTitulaire() {
        return nomTitulaire;
    }

    public String getPrenomTitulaire() {
        return prenomTitulaire;
    }

    public String getNumeroDocumentHash() {
        return numeroDocumentHash;
    }

    public String getNumeroDocumentSel() {
        return numeroDocumentSel;
    }

    public String getNumeroDocumentMasque() {
        return numeroDocumentMasque;
    }

    public LocalDate getDateNaissanceTitulaire() {
        return dateNaissanceTitulaire;
    }

    public LocalDate getDateDepot() {
        return dateDepot;
    }

    public String getEtatDocument() {
        return etatDocument;
    }

    public StatutPiece getStatut() {
        return statut;
    }

    public String getRemarques() {
        return remarques;
    }

    public OffsetDateTime getCreeLe() {
        return creeLe;
    }

    public OffsetDateTime getMajLe() {
        return majLe;
    }

    public Agent getSignalePar() {
        return signalePar;
    }

    public OffsetDateTime getSignaleLe() {
        return signaleLe;
    }

    public String getMotifSignalement() {
        return motifSignalement;
    }

    public Agent getDebloquePar() {
        return debloquePar;
    }

    public OffsetDateTime getDebloqueLe() {
        return debloqueLe;
    }

    public String getMotifDeblocage() {
        return motifDeblocage;
    }

    public boolean isCreeMalgreDoublon() {
        return creeMalgreDoublon;
    }

    public void retirer() {
        if (statut != StatutPiece.DISPONIBLE && statut != StatutPiece.RECLAMEE) {
            throw new TransitionStatutInterditeException(id, statut, "retrait");
        }
        this.statut = StatutPiece.RETIREE;
    }

    public void signaler(StatutPiece statutCible, String motif, Agent signalePar) {
        if (statutCible != StatutPiece.LITIGE && statutCible != StatutPiece.SIGNALEE) {
            throw new IllegalArgumentException("statutCible doit etre LITIGE ou SIGNALEE.");
        }
        if (statut != StatutPiece.DISPONIBLE && statut != StatutPiece.RECLAMEE) {
            throw new TransitionStatutInterditeException(id, statut, "signalement");
        }
        this.statut = statutCible;
        this.signalePar = signalePar;
        this.signaleLe = OffsetDateTime.now();
        this.motifSignalement = motif;
    }

    public void debloquer(String motif, Agent debloquePar) {
        if (statut != StatutPiece.RETIREE && statut != StatutPiece.ARCHIVEE
                && statut != StatutPiece.LITIGE && statut != StatutPiece.SIGNALEE) {
            throw new TransitionStatutInterditeException(id, statut, "deblocage");
        }
        this.statut = StatutPiece.DISPONIBLE;
        this.debloquePar = debloquePar;
        this.debloqueLe = OffsetDateTime.now();
        this.motifDeblocage = motif;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Piece other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
