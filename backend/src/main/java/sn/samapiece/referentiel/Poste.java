package sn.samapiece.referentiel;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "poste")
public class Poste {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "region_id", nullable = false)
    private Region region;

    @Column(name = "nom", nullable = false)
    private String nom;

    @Convert(converter = TypePosteConverter.class)
    @Column(name = "type", nullable = false, length = 20)
    private TypePoste type;

    @Column(name = "adresse", nullable = false, length = 500)
    private String adresse;

    @Column(name = "telephone", length = 30)
    private String telephone;

    /**
     * Chaîne JSON brute, un objet par jour de semaine, ex. :
     * {"lundi": {"ouvert": true, "debut": "08:00", "fin": "18:00"}, ...}
     * Aucune validation de schéma dans ce ticket.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "horaires", nullable = false, columnDefinition = "jsonb")
    private String horaires;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "cree_le", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creeLe;

    @Column(name = "maj_le", nullable = false, insertable = false)
    private OffsetDateTime majLe;

    protected Poste() {
    }

    public Poste(
            Region region,
            String nom,
            TypePoste type,
            String adresse,
            String telephone,
            String horaires,
            Double latitude,
            Double longitude) {
        this.region = region;
        this.nom = nom;
        this.type = type;
        this.adresse = adresse;
        this.telephone = telephone;
        this.horaires = horaires;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public UUID getId() {
        return id;
    }

    public Region getRegion() {
        return region;
    }

    public String getNom() {
        return nom;
    }

    public TypePoste getType() {
        return type;
    }

    public String getAdresse() {
        return adresse;
    }

    public String getTelephone() {
        return telephone;
    }

    public String getHoraires() {
        return horaires;
    }

    public Double getLatitude() {
        return latitude;
    }

    public Double getLongitude() {
        return longitude;
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
        if (!(o instanceof Poste other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
