package sn.samapiece.iam.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sn.samapiece.iam.Role;

class JwtServiceTest {

    private static final String SECRET = "test-secret-uniquement-pour-les-tests-automatises-1234";

    private JwtProperties jwtProperties() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        return properties;
    }

    @Test
    void genererAccessToken_shouldContenirClaimsAttendus() {
        JwtService jwtService = new JwtService(jwtProperties());
        UUID agentId = UUID.randomUUID();

        String token = jwtService.genererAccessToken(agentId, "PN-2024-00123", Role.CHEF_POSTE);
        Claims claims = jwtService.analyserToken(token);

        assertThat(claims.getSubject()).isEqualTo(agentId.toString());
        assertThat(claims.get(JwtService.CLAIM_MATRICULE, String.class)).isEqualTo("PN-2024-00123");
        assertThat(claims.get(JwtService.CLAIM_ROLE, String.class)).isEqualTo("CHEF_POSTE");
        assertThat(claims.get(JwtService.CLAIM_TYPE, String.class)).isEqualTo(JwtService.TYPE_ACCESS);
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }

    @Test
    void genererRefreshToken_shouldPasContenirClaimRole() {
        JwtService jwtService = new JwtService(jwtProperties());
        UUID agentId = UUID.randomUUID();

        String token = jwtService.genererRefreshToken(agentId, "PN-2024-00123");
        Claims claims = jwtService.analyserToken(token);

        assertThat(claims.getSubject()).isEqualTo(agentId.toString());
        assertThat(claims.get(JwtService.CLAIM_MATRICULE, String.class)).isEqualTo("PN-2024-00123");
        assertThat(claims.get(JwtService.CLAIM_TYPE, String.class)).isEqualTo(JwtService.TYPE_REFRESH);
        assertThat(claims.get(JwtService.CLAIM_ROLE, String.class)).isNull();
    }

    @Test
    void analyserToken_avecTokenExpire_shouldLeverJwtException() {
        Clock horlogeDansLePasse = Clock.fixed(Instant.now().minus(Duration.ofDays(8)), ZoneOffset.UTC);
        JwtService jwtServiceExpire = new JwtService(jwtProperties(), horlogeDansLePasse);
        String token = jwtServiceExpire.genererRefreshToken(UUID.randomUUID(), "PN-2024-00123");

        JwtService jwtServiceActuel = new JwtService(jwtProperties());

        assertThatThrownBy(() -> jwtServiceActuel.analyserToken(token)).isInstanceOf(JwtException.class);
    }
}
