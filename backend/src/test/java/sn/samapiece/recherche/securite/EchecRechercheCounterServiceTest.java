package sn.samapiece.recherche.securite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class EchecRechercheCounterServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final CaptchaProperties proprietes = new CaptchaProperties();

    private final EchecRechercheCounterService service =
            new EchecRechercheCounterService(redisTemplate, proprietes);

    @Test
    void enregistrerEchec_devraitIncrementerEtRenouvelerLeTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        proprietes.setTtlCompteurEchecsSecondes(600);

        service.enregistrerEchec("1.2.3.4");

        verify(valueOperations).increment("recherche-publique:echecs:1.2.3.4");
        verify(redisTemplate).expire(
                eq("recherche-publique:echecs:1.2.3.4"), eq(Duration.ofSeconds(600)));
    }

    @Test
    void enregistrerSucces_devraitSupprimerLaCle() {
        service.enregistrerSucces("1.2.3.4");

        verify(redisTemplate).delete("recherche-publique:echecs:1.2.3.4");
    }

    @Test
    void captchaRequis_quandCompteurSousLeSeuil_devraitRenvoyerFalse() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("recherche-publique:echecs:1.2.3.4")).thenReturn("4");
        proprietes.setSeuilEchecsConsecutifs(5);

        assertThat(service.captchaRequis("1.2.3.4")).isFalse();
    }

    @Test
    void captchaRequis_quandCompteurAtteintLeSeuil_devraitRenvoyerTrue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("recherche-publique:echecs:1.2.3.4")).thenReturn("5");
        proprietes.setSeuilEchecsConsecutifs(5);

        assertThat(service.captchaRequis("1.2.3.4")).isTrue();
    }

    @Test
    void captchaRequis_quandAucunCompteur_devraitRenvoyerFalse() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(any())).thenReturn(null);

        assertThat(service.captchaRequis("1.2.3.4")).isFalse();
    }
}
