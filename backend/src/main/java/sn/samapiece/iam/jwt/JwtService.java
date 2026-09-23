package sn.samapiece.iam.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import sn.samapiece.iam.Role;

@Component
public class JwtService {

    public static final String CLAIM_MATRICULE = "matricule";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_TYPE = "typ";
    public static final String CLAIM_DOIT_CHANGER_MOT_DE_PASSE = "doitChangerMotDePasse";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);
    static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(7);

    private final SecretKey signingKey;
    private final Clock clock;

    @Autowired
    public JwtService(JwtProperties jwtProperties) {
        this(jwtProperties, Clock.systemUTC());
    }

    /** Constructeur secondaire public : permet d'injecter une horloge fixe en test
     * (ex. pour générer un token déjà expiré), sans passer par un mock du temps système. */
    public JwtService(JwtProperties jwtProperties, Clock clock) {
        this.signingKey = Keys.hmacShaKeyFor(
                jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    public String genererAccessToken(UUID agentId, String matricule, Role role, boolean doitChangerMotDePasse) {
        Instant maintenant = clock.instant();
        return Jwts.builder()
                .subject(agentId.toString())
                .claim(CLAIM_MATRICULE, matricule)
                .claim(CLAIM_ROLE, role.name())
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .claim(CLAIM_DOIT_CHANGER_MOT_DE_PASSE, doitChangerMotDePasse)
                .issuedAt(Date.from(maintenant))
                .expiration(Date.from(maintenant.plus(ACCESS_TOKEN_TTL)))
                .signWith(signingKey)
                .compact();
    }

    public String genererRefreshToken(UUID agentId, String matricule) {
        Instant maintenant = clock.instant();
        return Jwts.builder()
                .subject(agentId.toString())
                .claim(CLAIM_MATRICULE, matricule)
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuedAt(Date.from(maintenant))
                .expiration(Date.from(maintenant.plus(REFRESH_TOKEN_TTL)))
                .signWith(signingKey)
                .compact();
    }

    /** @throws io.jsonwebtoken.JwtException si signature invalide, token expiré ou malformé. */
    public Claims analyserToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long accessTokenTtlSecondes() {
        return ACCESS_TOKEN_TTL.getSeconds();
    }
}
