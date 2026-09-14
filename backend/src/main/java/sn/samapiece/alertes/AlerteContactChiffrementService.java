package sn.samapiece.alertes;

import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AlerteContactChiffrementService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAILLE_TAG_BITS = 128;
    private static final int TAILLE_IV_OCTETS = 12;
    private static final int TAILLE_CLE_OCTETS = 32;

    private final SecretKeySpec cle;
    private final SecureRandom secureRandom = new SecureRandom();

    public AlerteContactChiffrementService(@Value("${samapiece.alerte.cle-chiffrement}") String cleBase64) {
        byte[] octetsCle = Base64.getDecoder().decode(cleBase64);
        if (octetsCle.length != TAILLE_CLE_OCTETS) {
            throw new IllegalStateException(
                    "La cle de chiffrement des contacts d'alerte doit faire exactement 32 octets (AES-256).");
        }
        this.cle = new SecretKeySpec(octetsCle, "AES");
    }

    public ContactChiffre chiffrer(byte[] octetsClair) {
        try {
            byte[] iv = new byte[TAILLE_IV_OCTETS];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, cle, new GCMParameterSpec(TAILLE_TAG_BITS, iv));
            byte[] octetsChiffres = cipher.doFinal(octetsClair);

            return new ContactChiffre(octetsChiffres, Base64.getEncoder().encodeToString(iv));
        } catch (Exception e) {
            throw new IllegalStateException("Echec du chiffrement du contact.", e);
        }
    }

    public byte[] dechiffrer(byte[] octetsChiffres, String ivBase64) {
        try {
            byte[] iv = Base64.getDecoder().decode(ivBase64);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, cle, new GCMParameterSpec(TAILLE_TAG_BITS, iv));
            return cipher.doFinal(octetsChiffres);
        } catch (Exception e) {
            throw new IllegalStateException("Echec du dechiffrement du contact.", e);
        }
    }

    public record ContactChiffre(byte[] octetsChiffres, String ivBase64) {}
}
