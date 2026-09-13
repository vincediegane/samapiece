# Spec — #10 Modèle Piece (numéro hashé + masqué) + migration Flyway

## Résumé

Ajout de la table `piece` (migration `V4`), des enums `StatutPiece`/`TypeDocument` (+ converters), du
composant `NumeroDocumentHasher` et de l'entité JPA `Piece`, garantissant par construction qu'aucun
numéro de document en clair n'est persistable.

## Décision tranchée : `TypeDocument` reste un enum Java (+ converter)

Le design laisse le choix ouvert. Décision : **on garde l'enum `TypeDocument` + `TypeDocumentConverter`**,
au même patron que `TypePoste`/`Role`. Justification :
- Cohérence stricte avec l'existant (`TypePoste`, `Role`) : le codebase n'a pas de précédent de colonne
  catégorielle en `VARCHAR` libre sans enum côté Java quand une liste fermée est documentée.
- Le §5 de PROJET-SAMAPIECE.md définit une liste fermée explicite — un `VARCHAR` libre côté Java
  autoriserait des valeurs invalides à la compilation, détectées seulement au `INSERT` par le `CHECK` SQL.
- Coût d'implémentation marginal (un enum + un converter, déjà entièrement spécifiés ci-dessous) par
  rapport au bénéfice de sécurité de type pour le futur contrôleur (#11).
- Rien dans les critères d'acceptation n'interdit d'aller au-delà de l'enum de statut explicitement
  demandé ; ce n'est pas une extension de périmètre fonctionnel (aucun comportement métier nouveau),
  juste un choix de représentation Java d'une colonne de toute façon nécessaire.

Ne pas réduire à un simple `VARCHAR` + `CHECK` sans enum Java.

## Tâches

- [ ] `backend/src/main/resources/db/migration/V4__create_piece.sql` — créer la table `piece` avec le
  schéma SQL exact du contrat technique ci-dessous (colonnes, `CHECK`, index).
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/TypeDocument.java` — enum à 8 valeurs.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/TypeDocumentConverter.java` — `AttributeConverter`
  minuscules, même patron que `TypePosteConverter`.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/StatutPiece.java` — enum à 7 valeurs (AC).
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/StatutPieceConverter.java` — `AttributeConverter`
  minuscules, même patron que `RoleConverter`.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/NumeroDocumentHasher.java` — composant Spring
  sans dépendance JPA, calcule le triplet (hash, sel, masqué) à partir du numéro en clair. Contient le
  record `NumeroDocumentHache`.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/Piece.java` — entité JPA, constructeur unique
  prenant le triplet déjà calculé (aucun champ/setter/constructeur acceptant un numéro brut).
- [ ] `backend/src/test/java/sn/samapiece/enregistrement/NumeroDocumentHasherTest.java` — tests unitaires
  purs (pas de Spring context) du hasher.
- [ ] `backend/src/test/java/sn/samapiece/enregistrement/PieceTest.java` — test unitaire pur, y compris
  le test par réflexion garantissant l'absence de champ en clair.

Ordre d'implémentation recommandé : migration → `TypeDocument`/`TypeDocumentConverter` →
`StatutPiece`/`StatutPieceConverter` → `NumeroDocumentHasher` (+ son test) → `Piece` (+ son test).

## Contrat technique

### Migration `V4__create_piece.sql`

```sql
CREATE TABLE piece (
    id                       UUID PRIMARY KEY,
    poste_id                 UUID NOT NULL REFERENCES poste(id),
    agent_createur_id        UUID NOT NULL REFERENCES agent(id),
    type_document            VARCHAR(30) NOT NULL CHECK (type_document IN
        ('cni', 'passeport', 'permis_conduire', 'carte_electeur',
         'extrait_naissance', 'carte_grise', 'carte_consulaire', 'autre')),
    nom_titulaire            VARCHAR(255) NOT NULL,
    prenom_titulaire         VARCHAR(255) NOT NULL,
    numero_document_hash     VARCHAR(64) NOT NULL,
    numero_document_sel      VARCHAR(64) NOT NULL,
    numero_document_masque   VARCHAR(64) NOT NULL,
    date_naissance_titulaire DATE,
    date_depot               DATE NOT NULL,
    etat_document            VARCHAR(255),
    statut                   VARCHAR(20) NOT NULL DEFAULT 'disponible' CHECK (statut IN
        ('disponible', 'reclamee', 'retiree', 'litige', 'archivee', 'detruite', 'signalee')),
    remarques                TEXT,
    cree_le                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    maj_le                   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_piece_poste_id ON piece(poste_id);
CREATE INDEX idx_piece_agent_createur_id ON piece(agent_createur_id);
CREATE INDEX idx_piece_statut ON piece(statut);
```

Pas de contrainte `UNIQUE` sur `numero_document_hash` (sel par ligne la rendrait sans effet — ne pas
l'ajouter). Aucun trigger SQL de mise à jour de `maj_le` (cohérent avec `agent`/`poste`).

### `TypeDocument.java`

```java
package sn.samapiece.enregistrement;

public enum TypeDocument {
    CNI,
    PASSEPORT,
    PERMIS_CONDUIRE,
    CARTE_ELECTEUR,
    EXTRAIT_NAISSANCE,
    CARTE_GRISE,
    CARTE_CONSULAIRE,
    AUTRE
}
```

### `TypeDocumentConverter.java`

Même patron exact que `TypePosteConverter` : `@Converter`, `AttributeConverter<TypeDocument, String>`,
`name().toLowerCase()` / `TypeDocument.valueOf(dbData.toUpperCase())`.

### `StatutPiece.java`

```java
package sn.samapiece.enregistrement;

public enum StatutPiece {
    DISPONIBLE,
    RECLAMEE,
    RETIREE,
    LITIGE,
    ARCHIVEE,
    DETRUITE,
    SIGNALEE
}
```

### `StatutPieceConverter.java`

Même patron exact que `RoleConverter` : `@Converter`, `AttributeConverter<StatutPiece, String>`,
`name().toLowerCase()` / `StatutPiece.valueOf(dbData.toUpperCase())`.

### `NumeroDocumentHasher.java`

Composant `@Component`, **sans aucune dépendance à `Piece` ni à JPA**. Algorithme imposé :

- **Sel** : `SecureRandom` (instance statique partagée, thread-safe), 16 octets, encodés en hexadécimal
  minuscule (32 caractères).
- **Hash** : `SHA-256` appliqué à la concaténation `sel + numeroClair` (sel en hex, préfixé avant le
  numéro clair), résultat encodé en hexadécimal minuscule (64 caractères).
- **Masqué** : si `numeroClair.length() <= 4`, masquage complet (autant de `●` que de caractères) ;
  sinon, 2 premiers caractères en clair + `●` répété `length - 4` fois + 2 derniers caractères en clair
  (longueur totale identique au numéro d'origine).
- Le numéro clair n'est jamais retourné ni loggé par ce composant ; il n'existe que le temps de l'appel
  de méthode.

```java
package sn.samapiece.enregistrement;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class NumeroDocumentHasher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TAILLE_SEL_OCTETS = 16;
    private static final int CARACTERES_VISIBLES_DEBUT = 2;
    private static final int CARACTERES_VISIBLES_FIN = 2;
    private static final int LONGUEUR_MIN_POUR_MASQUAGE_PARTIEL =
            CARACTERES_VISIBLES_DEBUT + CARACTERES_VISIBLES_FIN;

    public NumeroDocumentHache hacher(String numeroClair) {
        Objects.requireNonNull(numeroClair, "numeroClair");
        if (numeroClair.isEmpty()) {
            throw new IllegalArgumentException("numeroClair ne peut pas être vide");
        }

        String sel = genererSel();
        String hash = calculerHash(sel, numeroClair);
        String masque = masquer(numeroClair);
        return new NumeroDocumentHache(hash, sel, masque);
    }

    private String genererSel() {
        byte[] octets = new byte[TAILLE_SEL_OCTETS];
        RANDOM.nextBytes(octets);
        return versHex(octets);
    }

    private String calculerHash(String sel, String numeroClair) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(sel.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(numeroClair.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return versHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 non disponible", e);
        }
    }

    private String masquer(String numeroClair) {
        int longueur = numeroClair.length();
        if (longueur <= LONGUEUR_MIN_POUR_MASQUAGE_PARTIEL) {
            return "●".repeat(longueur);
        }
        String debut = numeroClair.substring(0, CARACTERES_VISIBLES_DEBUT);
        String fin = numeroClair.substring(longueur - CARACTERES_VISIBLES_FIN);
        String milieu = "●".repeat(longueur - LONGUEUR_MIN_POUR_MASQUAGE_PARTIEL);
        return debut + milieu + fin;
    }

    private String versHex(byte[] octets) {
        StringBuilder sb = new StringBuilder(octets.length * 2);
        for (byte b : octets) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public record NumeroDocumentHache(String hash, String sel, String masque) {
        public NumeroDocumentHache {
            Objects.requireNonNull(hash, "hash");
            Objects.requireNonNull(sel, "sel");
            Objects.requireNonNull(masque, "masque");
        }
    }
}
```

Note d'implémentation : préférer les imports classiques (`import java.nio.charset.StandardCharsets;`)
en en-tête de fichier plutôt que le nom qualifié inline utilisé ci-dessus par souci de concision dans
cette spec — le codeur doit produire un fichier avec des imports propres, pas une copie littérale de ce
bloc si le style diffère sur ce point de détail.

### `Piece.java`

Entité JPA, patron identique à `Agent`/`Poste` (id `UUID` auto-généré, `equals`/`hashCode` sur `id`,
`creeLe`/`majLe` gérés par la base via `insertable = false` / `updatable = false` et
`insertable = false`).

```java
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

    protected Piece() {
    }

    public Piece(
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
    }

    public UUID getId() {
        return id;
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
```

Points contractuels non négociables (repris du design, à ne pas dévier au codage) :
- `Piece` n'a **aucun** champ, setter, ni constructeur acceptant un numéro brut. Le seul constructeur
  public prend les trois valeurs déjà calculées (`numeroDocumentHash`, `numeroDocumentSel`,
  `numeroDocumentMasque`).
- Aucun setter n'est ajouté sur `Piece` dans ce ticket (pas de méthode de mutation demandée par les AC ;
  une éventuelle méthode `changerStatut(StatutPiece)` est hors périmètre de #10, à introduire par #11 ou
  un ticket ultérieur si besoin).
- `statut` est fixé à `StatutPiece.DISPONIBLE` dans le constructeur, jamais paramétrable à la création.

## Plan de tests

| Critère d'acceptation | Test |
|---|---|
| Migration Flyway créant la table `piece` avec toutes les colonnes listées | Vérification manuelle au démarrage de l'application (Flyway échoue si le SQL est invalide) + `SamaPieceApplicationTests` (contexte Spring déjà existant, doit continuer à démarrer sans erreur de migration une fois `V4` ajoutée) — aucun test dédié supplémentaire n'est nécessaire pour la seule création de table, cohérent avec l'absence de test dédié pour V1/V2/V3. |
| Le hash utilise un sel unique par enregistrement | `NumeroDocumentHasherTest.hacher_shouldGenererUnSelDifferentAChaqueAppel_memePourLeMemeNumero` : appeler `hacher("123456789")` deux fois, asserter `sel1 != sel2` (`isNotEqualTo`) et donc `hash1 != hash2` malgré un numéro identique. |
| Le champ masqué n'affiche jamais plus de 4 caractères en clair | `NumeroDocumentHasherTest.masquer_shouldConserverAuMaximumQuatreCaracteresEnClair` (paramétré ou plusieurs `@Test`) couvrant : numéro long (ex. `"1234567890"` → `"12" + "●".repeat(6) + "90"`, soit exactement 4 caractères clairs), numéro de longueur 5 (1 caractère masqué, 4 clairs), numéro de longueur exactement 4 (masquage complet, 0 clair), numéro de longueur 3 et 1 (masquage complet). Assertion sur la valeur exacte retournée, pas seulement sur le compte de caractères clairs. |
| Enum de statut à 7 valeurs (DISPONIBLE, RECLAMEE, RETIREE, LITIGE, ARCHIVEE, DETRUITE, SIGNALEE) | `PieceTest.nouvellePiece_shouldAvoirStatutDisponibleParDefaut` : construit une `Piece` et vérifie `getStatut() == StatutPiece.DISPONIBLE`. La complétude des 7 valeurs de l'enum et la cohérence avec le `CHECK` SQL sont garanties par lecture du fichier `StatutPiece.java`/migration au moment de la revue de code (pas de test automatisé pertinent pour "l'enum contient exactement ces 7 valeurs" au-delà de la compilation — un test qui itère `StatutPiece.values()` et compare à un tableau attendu peut être ajouté en confort, optionnel). |
| Aucun numéro en clair n'est persistable directement (le champ clair n'existe pas en base) | `PieceTest.piece_shouldNeJamaisExposerLeNumeroEnClair` : test par réflexion — construit une `Piece` à partir d'un `NumeroDocumentHache` connu (numéro clair de test conservé uniquement en local dans le test, jamais passé à `Piece`), puis itère sur `Piece.class.getDeclaredFields()` et assert que (a) aucun nom de champ ne vaut `numeroDocument` (sans suffixe `Hash`/`Sel`/`Masque`), et (b) pour chaque champ de type `String`, la valeur obtenue par réflexion (`field.setAccessible(true); field.get(piece)`) n'est jamais égale au numéro clair de test. Complément statique : vérifier qu'aucun constructeur public de `Piece` n'a une arité/signature permettant de passer une chaîne unique candidate au numéro (assertion sur `Piece.class.getConstructors().length == 1` et sur les noms de paramètres du constructeur trouvé, via `getParameters()` avec `-parameters` déjà activé côté build s'il l'est, sinon assertion sur le nombre et les types de paramètres uniquement). |
| (Complément design, non-réversibilité triviale) | `NumeroDocumentHasherTest.hacher_shouldProduireUnHashNonReversibleTrivialement` : vérifie que `hash` ne contient pas le numéro clair en sous-chaîne et que `hash.length() == 64` (SHA-256 hex), `sel.length() == 32` (16 octets hex). |
| (Complément design, format masqué longueur ≤4) | Couvert par la même méthode paramétrée que la ligne "champ masqué" ci-dessus (cas longueur 4, 3, 1). |

Tous les tests sont unitaires purs (JUnit 5 + AssertJ, sans `@SpringBootTest`, sans Testcontainers) —
cohérent avec la nature du composant (`NumeroDocumentHasher` n'a pas de dépendance Spring nécessitant un
contexte ; `Piece` est un POJO JPA testable sans base). Aucun test d'intégration Testcontainers n'est
nécessaire pour ce ticket : il n'y a ni repository Spring Data ni endpoint exposés ici (confirmé hors
périmètre par le design).

## Écarts identifiés

Aucun écart bloquant entre le design et le ticket. Un point mérite d'être noté (déjà signalé par le
design, repris ici pour visibilité) : le cycle de vie décrit ailleurs dans PROJET-SAMAPIECE.md mentionne
un état transitoire "déposée" qui n'a pas de valeur dédiée dans les 7 valeurs de `StatutPiece` imposées
par ce ticket ; le dépôt et la mise à disposition sont donc confondus en un seul statut initial
`DISPONIBLE`. Ce n'est pas un écart avec les critères d'acceptation du ticket #10 (qui ne demandent que
les 7 valeurs listées), mais à garder en tête si un ticket futur veut réintroduire un statut
intermédiaire (migration `ALTER TYPE`/`CHECK` à prévoir alors).
