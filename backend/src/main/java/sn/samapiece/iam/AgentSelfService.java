package sn.samapiece.iam;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.samapiece.iam.jwt.JwtService;
import sn.samapiece.iam.web.AgentResponse;
import sn.samapiece.iam.web.ChangerMotDePasseRequest;
import sn.samapiece.iam.web.ChangerMotDePasseResponse;

@Service
public class AgentSelfService {

    private final AgentRepository agentRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AgentSelfService(
            AgentRepository agentRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.agentRepository = agentRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional(readOnly = true)
    public AgentResponse moi() {
        return AgentResponse.of(appelantCourant());
    }

    @Transactional
    public ChangerMotDePasseResponse changerMotDePasse(ChangerMotDePasseRequest request) {
        Agent appelant = appelantCourant();

        if (!passwordEncoder.matches(request.motDePasseActuel(), appelant.getHashMotDePasse())) {
            throw new MotDePasseActuelInvalideException();
        }

        appelant.changerMotDePasse(passwordEncoder.encode(request.nouveauMotDePasse()));
        agentRepository.save(appelant);

        String accessToken = jwtService.genererAccessToken(
                appelant.getId(), appelant.getMatricule(), appelant.getRole(), appelant.isDoitChangerMotDePasse());
        String refreshToken = jwtService.genererRefreshToken(appelant.getId(), appelant.getMatricule());
        return new ChangerMotDePasseResponse(accessToken, refreshToken, jwtService.accessTokenTtlSecondes());
    }

    private Agent appelantCourant() {
        String matricule = SecurityContextHolder.getContext().getAuthentication().getName();
        Agent appelant = agentRepository.findByMatricule(matricule)
                .orElseThrow(() -> new AccesRefuseException("Agent appelant introuvable."));
        if (!appelant.isActif()) {
            throw new AccesRefuseException("Agent appelant inactif.");
        }
        return appelant;
    }
}
