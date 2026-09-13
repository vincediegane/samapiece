package sn.samapiece.enregistrement;

import java.nio.charset.StandardCharsets;
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

    public boolean verifier(String numeroClair, String sel, String hashAttendu) {
        Objects.requireNonNull(numeroClair, "numeroClair");
        Objects.requireNonNull(sel, "sel");
        Objects.requireNonNull(hashAttendu, "hashAttendu");
        return calculerHash(sel, numeroClair).equals(hashAttendu);
    }

    private String genererSel() {
        byte[] octets = new byte[TAILLE_SEL_OCTETS];
        RANDOM.nextBytes(octets);
        return versHex(octets);
    }

    private String calculerHash(String sel, String numeroClair) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(sel.getBytes(StandardCharsets.UTF_8));
            digest.update(numeroClair.getBytes(StandardCharsets.UTF_8));
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
