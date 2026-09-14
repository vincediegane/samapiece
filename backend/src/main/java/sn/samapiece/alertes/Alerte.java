package sn.samapiece.alertes;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import sn.samapiece.enregistrement.TypeDocument;
import sn.samapiece.enregistrement.TypeDocumentConverter;

@Entity
@Table(name = "alerte")
public class Alerte {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Convert(converter = TypeDocumentConverter.class)
    @Column(name = "type_document", nullable = false, length = 30)
    private TypeDocument typeDocument;

    @Column(name = "nom_titulaire", nullable = false)
    private String nomTitulaire;

    @Column(name = "prenom_titulaire")
    private String prenomTitulaire;

    @Column(name = "numero_document_hash", length = 64)
    private String numeroDocumentHash;

    @Column(name = "numero_document_sel", length = 64)
    private String numeroDocumentSel;

    @Column(name = "numero_document_masque", length = 64)
    private String numeroDocumentMasque;

    @Column(name = "date_naissance_titulaire")
    private LocalDate dateNaissanceTitulaire;

    @Column(name = "canal", nullable = false, length = 10)
    private String canal;

    @Column(name = "contact_chiffre", columnDefinition = "bytea")
    private byte[] contactChiffre;

    @Column(name = "contact_iv", length = 64)
    private String contactIv;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "cree_le", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creeLe;

    @Column(name = "maj_le", nullable = false, insertable = false)
    private OffsetDateTime majLe;

    protected Alerte() {
    }

    public Alerte(
            TypeDocument typeDocument,
            String nomTitulaire,
            String prenomTitulaire,
            String numeroDocumentHash,
            String numeroDocumentSel,
            String numeroDocumentMasque,
            LocalDate dateNaissanceTitulaire,
            String canal,
            byte[] contactChiffre,
            String contactIv) {
        this.typeDocument = typeDocument;
        this.nomTitulaire = nomTitulaire;
        this.prenomTitulaire = prenomTitulaire;
        this.numeroDocumentHash = numeroDocumentHash;
        this.numeroDocumentSel = numeroDocumentSel;
        this.numeroDocumentMasque = numeroDocumentMasque;
        this.dateNaissanceTitulaire = dateNaissanceTitulaire;
        this.canal = canal;
        this.contactChiffre = contactChiffre;
        this.contactIv = contactIv;
        this.active = true;
    }

    public void desinscrire() {
        this.active = false;
        this.contactChiffre = null;
        this.contactIv = null;
        this.majLe = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
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

    public String getCanal() {
        return canal;
    }

    public byte[] getContactChiffre() {
        return contactChiffre;
    }

    public String getContactIv() {
        return contactIv;
    }

    public boolean isActive() {
        return active;
    }

    public OffsetDateTime getCreeLe() {
        return creeLe;
    }

    public OffsetDateTime getMajLe() {
        return majLe;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Alerte other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
