package sn.samapiece.iam;

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
import sn.samapiece.referentiel.Poste;

@Entity
@Table(name = "agent")
public class Agent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "poste_id", nullable = false)
    private Poste poste;

    @Column(name = "matricule", nullable = false, unique = true, length = 50)
    private String matricule;

    @Column(name = "nom", nullable = false)
    private String nom;

    @Convert(converter = RoleConverter.class)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Column(name = "hash_mot_de_passe", nullable = false)
    private String hashMotDePasse;

    @Column(name = "actif", nullable = false)
    private boolean actif;

    @Column(name = "derniere_connexion")
    private OffsetDateTime derniereConnexion;

    @Column(name = "cree_le", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creeLe;

    @Column(name = "maj_le", nullable = false, insertable = false)
    private OffsetDateTime majLe;

    protected Agent() {
    }

    public Agent(Poste poste, String matricule, String nom, Role role, String hashMotDePasse) {
        this.poste = poste;
        this.matricule = matricule;
        this.nom = nom;
        this.role = role;
        this.hashMotDePasse = hashMotDePasse;
        this.actif = true;
        this.derniereConnexion = null;
    }

    public UUID getId() {
        return id;
    }

    public Poste getPoste() {
        return poste;
    }

    public String getMatricule() {
        return matricule;
    }

    public String getNom() {
        return nom;
    }

    public Role getRole() {
        return role;
    }

    public String getHashMotDePasse() {
        return hashMotDePasse;
    }

    public boolean isActif() {
        return actif;
    }

    public OffsetDateTime getDerniereConnexion() {
        return derniereConnexion;
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
        if (!(o instanceof Agent other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
