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
import java.time.Duration;
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

    @Column(name = "tentatives_echouees", nullable = false)
    private int tentativesEchouees;

    @Column(name = "verrouille_jusqu_a")
    private OffsetDateTime verrouilleJusqua;

    @Column(name = "cree_le", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creeLe;

    @Column(name = "maj_le", nullable = false, insertable = false)
    private OffsetDateTime majLe;

    @Column(name = "doit_changer_mot_de_passe", nullable = false)
    private boolean doitChangerMotDePasse;

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
        this.tentativesEchouees = 0;
        this.verrouilleJusqua = null;
        this.doitChangerMotDePasse = false;
    }

    public Agent(
            Poste poste, String matricule, String nom, Role role, String hashMotDePasse,
            boolean doitChangerMotDePasse) {
        this(poste, matricule, nom, role, hashMotDePasse);
        this.doitChangerMotDePasse = doitChangerMotDePasse;
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

    public int getTentativesEchouees() {
        return tentativesEchouees;
    }

    public OffsetDateTime getVerrouilleJusqua() {
        return verrouilleJusqua;
    }

    public void enregistrerConnexionReussie() {
        this.tentativesEchouees = 0;
        this.verrouilleJusqua = null;
        this.derniereConnexion = OffsetDateTime.now();
    }

    /**
     * Incrémente le compteur d'échecs ; si le seuil est atteint, verrouille le compte pour la
     * durée donnée et réinitialise le compteur (le prochain cycle de comptage repart de zéro
     * après expiration du verrouillage).
     */
    public void enregistrerEchecConnexion(int seuil, Duration dureeVerrouillage) {
        this.tentativesEchouees++;
        if (this.tentativesEchouees >= seuil) {
            this.verrouilleJusqua = OffsetDateTime.now().plus(dureeVerrouillage);
            this.tentativesEchouees = 0;
        }
    }

    public boolean estVerrouille() {
        return verrouilleJusqua != null && verrouilleJusqua.isAfter(OffsetDateTime.now());
    }

    public void desactiver() {
        this.actif = false;
    }

    public void modifierInformations(String nom, Poste poste) {
        this.nom = Objects.requireNonNull(nom, "nom");
        this.poste = Objects.requireNonNull(poste, "poste");
    }

    public boolean isDoitChangerMotDePasse() {
        return doitChangerMotDePasse;
    }

    /** Met a jour le hash et leve le flag de renouvellement force. */
    public void changerMotDePasse(String nouveauHashMotDePasse) {
        this.hashMotDePasse = Objects.requireNonNull(nouveauHashMotDePasse, "nouveauHashMotDePasse");
        this.doitChangerMotDePasse = false;
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
