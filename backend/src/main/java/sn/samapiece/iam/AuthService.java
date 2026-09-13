package sn.samapiece.iam;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.time.Duration;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.samapiece.iam.jwt.JwtService;
import sn.samapiece.iam.web.LoginRequest;
import sn.samapiece.iam.web.LoginResponse;
import sn.samapiece.iam.web.RefreshRequest;
import sn.samapiece.iam.web.RefreshResponse;

@Service
public class AuthService {

    static final int SEUIL_ECHECS = 5;
    static final Duration DUREE_VERROUILLAGE = Duration.ofMinutes(15);

    private final AgentRepository agentRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            AgentRepository agentRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.agentRepository = agentRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        Agent agent = agentRepository.findByMatricule(request.matricule())
                .orElseThrow(() -> new AuthenticationException("Identifiants invalides"));

        // Compte inactif rejeté avant toute vérification de mot de passe, sans incrémenter
        // le compteur d'échecs (désactivation != mot de passe erroné).
        if (!agent.isActif()) {
            throw new AuthenticationException("Identifiants invalides");
        }
        if (agent.estVerrouille()) {
            throw new CompteVerrouilleException("Compte verrouille");
        }
        if (!passwordEncoder.matches(request.motDePasse(), agent.getHashMotDePasse())) {
            agent.enregistrerEchecConnexion(SEUIL_ECHECS, DUREE_VERROUILLAGE);
            agentRepository.save(agent);
            // Le 5e échec consécutif déclenche le verrouillage : la réponse de cette 5e
            // tentative est déjà 423, pas 401.
            if (agent.estVerrouille()) {
                throw new CompteVerrouilleException("Compte verrouille");
            }
            throw new AuthenticationException("Identifiants invalides");
        }

        agent.enregistrerConnexionReussie();
        agentRepository.save(agent);

        String accessToken = jwtService.genererAccessToken(agent.getId(), agent.getMatricule(), agent.getRole());
        String refreshToken = jwtService.genererRefreshToken(agent.getId(), agent.getMatricule());
        return new LoginResponse(
                accessToken, refreshToken, jwtService.accessTokenTtlSecondes(),
                agent.getRole().name(), agent.getNom());
    }

    @Transactional
    public RefreshResponse refresh(RefreshRequest request) {
        Claims claims;
        try {
            claims = jwtService.analyserToken(request.refreshToken());
        } catch (JwtException | IllegalArgumentException e) {
            throw new AuthenticationException("Token invalide ou expire");
        }

        if (!JwtService.TYPE_REFRESH.equals(claims.get(JwtService.CLAIM_TYPE, String.class))) {
            throw new AuthenticationException("Token invalide ou expire");
        }

        UUID agentId = UUID.fromString(claims.getSubject());
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new AuthenticationException("Token invalide ou expire"));

        if (!agent.isActif()) {
            throw new AuthenticationException("Token invalide ou expire");
        }
        if (agent.estVerrouille()) {
            throw new CompteVerrouilleException("Compte verrouille");
        }

        String accessToken = jwtService.genererAccessToken(agent.getId(), agent.getMatricule(), agent.getRole());
        return new RefreshResponse(accessToken, jwtService.accessTokenTtlSecondes());
    }
}
