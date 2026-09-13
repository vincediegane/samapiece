package sn.samapiece.enregistrement.photo;

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
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import sn.samapiece.enregistrement.Piece;

@Entity
@Table(name = "photo")
public class Photo {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "piece_id", nullable = false)
    private Piece piece;

    @Convert(converter = TypePhotoConverter.class)
    @Column(name = "type", nullable = false, length = 10)
    private TypePhoto type;

    @Column(name = "cle_objet_stockage", nullable = false, unique = true, length = 255)
    private String cleObjetStockage;

    @Column(name = "type_mime", nullable = false, length = 50)
    private String typeMime;

    @Column(name = "taille_octets", nullable = false)
    private long tailleOctets;

    @Column(name = "iv_chiffrement", nullable = false, length = 64)
    private String ivChiffrement;

    @Column(name = "cree_le", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creeLe;

    protected Photo() {
    }

    public Photo(
            Piece piece,
            TypePhoto type,
            String cleObjetStockage,
            String typeMime,
            long tailleOctets,
            String ivChiffrement) {
        this.piece = piece;
        this.type = type;
        this.cleObjetStockage = cleObjetStockage;
        this.typeMime = typeMime;
        this.tailleOctets = tailleOctets;
        this.ivChiffrement = ivChiffrement;
    }

    public UUID getId() {
        return id;
    }

    public Piece getPiece() {
        return piece;
    }

    public TypePhoto getType() {
        return type;
    }

    public String getCleObjetStockage() {
        return cleObjetStockage;
    }

    public String getTypeMime() {
        return typeMime;
    }

    public long getTailleOctets() {
        return tailleOctets;
    }

    public String getIvChiffrement() {
        return ivChiffrement;
    }

    public OffsetDateTime getCreeLe() {
        return creeLe;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Photo other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
