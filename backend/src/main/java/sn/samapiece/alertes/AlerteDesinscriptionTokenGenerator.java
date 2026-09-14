package sn.samapiece.alertes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class AlerteDesinscriptionTokenGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TAILLE_TOKEN_OCTETS = 32;

    public String genererBrut() {
        byte[] octets = new byte[TAILLE_TOKEN_OCTETS];
        RANDOM.nextBytes(octets);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(octets);
    }

    /**
     * Sans sel, contrairement a {@code NumeroDocumentHasher.calculerHash} : le jeton brut est
     * deja a haute entropie (32 octets SecureRandom), un sel n'apporterait aucune protection
     * supplementaire contre une attaque par dictionnaire/rainbow table.
     */
    public String hacher(String tokenBrut) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] octets = digest.digest(tokenBrut.getBytes(StandardCharsets.UTF_8));
            return versHex(octets);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 non disponible", e);
        }
    }

    private String versHex(byte[] octets) {
        StringBuilder sb = new StringBuilder(octets.length * 2);
        for (byte b : octets) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
