package sn.samapiece.notifications;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Value object encapsulant un numero de telephone. Le masquage systematique via
 * {@link #toString()} garantit qu'un numero n'apparait jamais en clair dans les logs :
 * tout appel de log doit passer un {@code NumeroTelephone} directement (jamais
 * {@link #valeurBrute()}, jamais une concatenation de {@code String}).
 */
public final class NumeroTelephone {

    private static final Pattern LOCAL_NEUF_CHIFFRES = Pattern.compile("^\\d{9}$");

    private final String valeur;

    private NumeroTelephone(String valeur) {
        this.valeur = valeur;
    }

    /** Normalise (trim, ajout du prefixe +221 si 9 chiffres locaux sans indicatif). */
    public static NumeroTelephone de(String saisie) {
        if (saisie == null || saisie.isBlank()) {
            throw new IllegalArgumentException("Le numero de telephone ne peut pas etre vide.");
        }
        String nettoye = saisie.trim();
        if (LOCAL_NEUF_CHIFFRES.matcher(nettoye).matches()) {
            nettoye = "+221" + nettoye;
        }
        return new NumeroTelephone(nettoye);
    }

    /** Acces explicite a la valeur brute — n'appeler qu'au point d'appel HTTP reel. */
    public String valeurBrute() {
        return valeur;
    }

    @Override
    public String toString() {
        if (valeur.length() <= 6) {
            return "***";
        }
        String prefixe = valeur.substring(0, 4);
        String suffixe = valeur.substring(valeur.length() - 2);
        String masque = "X".repeat(valeur.length() - 4 - 2);
        return prefixe + masque + suffixe;
    }

    @Override
    public boolean equals(Object autre) {
        if (this == autre) {
            return true;
        }
        if (!(autre instanceof NumeroTelephone numeroTelephone)) {
            return false;
        }
        return Objects.equals(valeur, numeroTelephone.valeur);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(valeur);
    }
}
