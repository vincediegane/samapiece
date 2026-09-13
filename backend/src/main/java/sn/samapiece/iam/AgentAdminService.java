package sn.samapiece.iam;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.samapiece.iam.web.AgentResponse;
import sn.samapiece.iam.web.CreerAgentRequest;
import sn.samapiece.iam.web.CreerAgentResponse;
import sn.samapiece.iam.web.ModifierAgentRequest;
import sn.samapiece.referentiel.Poste;
import sn.samapiece.referentiel.PosteRepository;

@Service
public class AgentAdminService {

    private static final int LONGUEUR_MOT_DE_PASSE = 12;
    private static final String ALPHABET_MOT_DE_PASSE =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$%&*";

    private final AgentRepository agentRepository;
    private final PosteRepository posteRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public AgentAdminService(
            AgentRepository agentRepository, PosteRepository posteRepository, PasswordEncoder passwordEncoder) {
        this.agentRepository = agentRepository;
        this.posteRepository = posteRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public CreerAgentResponse creer(CreerAgentRequest request) {
        Poste poste = posteRepository.findById(request.posteId())
                .orElseThrow(() -> new PosteIntrouvableException(request.posteId()));

        if (agentRepository.findByMatricule(request.matricule()).isPresent()) {
            throw new MatriculeDejaUtiliseException(request.matricule());
        }

        String motDePasseTemporaire = genererMotDePasseTemporaire();
        Agent agent = new Agent(
                poste, request.matricule(), request.nom(), request.role(),
                passwordEncoder.encode(motDePasseTemporaire));

        try {
            agentRepository.saveAndFlush(agent);
        } catch (DataIntegrityViolationException e) {
            throw new MatriculeDejaUtiliseException(request.matricule());
        }

        return CreerAgentResponse.of(agent, motDePasseTemporaire);
    }

    @Transactional(readOnly = true)
    public List<AgentResponse> lister() {
        return agentRepository.findAll().stream().map(AgentResponse::of).toList();
    }

    @Transactional
    public AgentResponse modifier(UUID id, ModifierAgentRequest request) {
        Agent agent = agentRepository.findById(id).orElseThrow(() -> new AgentIntrouvableException(id));

        String nomResolu = agent.getNom();
        if (request.nom() != null) {
            if (request.nom().isBlank()) {
                throw new IllegalArgumentException("nom ne peut pas être vide");
            }
            nomResolu = request.nom();
        }

        Poste posteResolu = agent.getPoste();
        if (request.posteId() != null) {
            posteResolu = posteRepository.findById(request.posteId())
                    .orElseThrow(() -> new PosteIntrouvableException(request.posteId()));
        }

        agent.modifierInformations(nomResolu, posteResolu);
        agentRepository.save(agent);

        return AgentResponse.of(agent);
    }

    @Transactional
    public void desactiver(UUID id) {
        Agent agent = agentRepository.findById(id).orElseThrow(() -> new AgentIntrouvableException(id));
        agent.desactiver();
        agentRepository.save(agent);
    }

    private String genererMotDePasseTemporaire() {
        StringBuilder motDePasse = new StringBuilder(LONGUEUR_MOT_DE_PASSE);
        for (int i = 0; i < LONGUEUR_MOT_DE_PASSE; i++) {
            int index = secureRandom.nextInt(ALPHABET_MOT_DE_PASSE.length());
            motDePasse.append(ALPHABET_MOT_DE_PASSE.charAt(index));
        }
        return motDePasse.toString();
    }
}
