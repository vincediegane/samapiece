package sn.samapiece.iam;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class MotDePasseTemporaireGenerator {

    private static final int LONGUEUR_MOT_DE_PASSE = 12;
    private static final String ALPHABET_MOT_DE_PASSE =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$%&*";

    private final SecureRandom secureRandom = new SecureRandom();

    public String generer() {
        StringBuilder motDePasse = new StringBuilder(LONGUEUR_MOT_DE_PASSE);
        for (int i = 0; i < LONGUEUR_MOT_DE_PASSE; i++) {
            int index = secureRandom.nextInt(ALPHABET_MOT_DE_PASSE.length());
            motDePasse.append(ALPHABET_MOT_DE_PASSE.charAt(index));
        }
        return motDePasse.toString();
    }
}
