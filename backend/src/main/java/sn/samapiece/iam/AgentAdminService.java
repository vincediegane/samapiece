package sn.samapiece.iam;

import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.SecurityContextHolder;
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

    private final AgentRepository agentRepository;
    private final PosteRepository posteRepository;
    private final PasswordEncoder passwordEncoder;
    private final MotDePasseTemporaireGenerator motDePasseTemporaireGenerator;

    public AgentAdminService(
            AgentRepository agentRepository, PosteRepository posteRepository, PasswordEncoder passwordEncoder,
            MotDePasseTemporaireGenerator motDePasseTemporaireGenerator) {
        this.agentRepository = agentRepository;
        this.posteRepository = posteRepository;
        this.passwordEncoder = passwordEncoder;
        this.motDePasseTemporaireGenerator = motDePasseTemporaireGenerator;
    }

    @Transactional
    public CreerAgentResponse creer(CreerAgentRequest request) {
        Agent appelant = appelantCourant();
        verifierRoleAssignable(appelant.getRole(), request.role());

        Poste poste = posteRepository.findById(request.posteId())
                .orElseThrow(() -> new PosteIntrouvableException(request.posteId()));
        verifierPerimetrePoste(appelant, poste);

        if (agentRepository.findByMatricule(request.matricule()).isPresent()) {
            throw new MatriculeDejaUtiliseException(request.matricule());
        }

        String motDePasseTemporaire = motDePasseTemporaireGenerator.generer();
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
        Agent appelant = appelantCourant();
        List<Agent> agents = switch (appelant.getRole()) {
            case CHEF_POSTE -> agentRepository.findByPosteId(appelant.getPoste().getId());
            case ADMIN_REGIONAL -> agentRepository.findByPosteRegionId(appelant.getPoste().getRegion().getId());
            case ADMIN_NATIONAL -> agentRepository.findAll();
            default -> throw new AccesRefuseException("Role sans perimetre de lecture defini.");
        };
        return agents.stream().map(AgentResponse::of).toList();
    }

    @Transactional
    public AgentResponse modifier(UUID id, ModifierAgentRequest request) {
        Agent agent = agentRepository.findById(id).orElseThrow(() -> new AgentIntrouvableException(id));
        Agent appelant = appelantCourant();
        verifierPerimetrePoste(appelant, agent.getPoste());

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
            verifierPerimetrePoste(appelant, posteResolu);
        }

        agent.modifierInformations(nomResolu, posteResolu);
        agentRepository.save(agent);

        return AgentResponse.of(agent);
    }

    @Transactional
    public void desactiver(UUID id) {
        Agent agent = agentRepository.findById(id).orElseThrow(() -> new AgentIntrouvableException(id));
        Agent appelant = appelantCourant();
        verifierPerimetrePoste(appelant, agent.getPoste());

        agent.desactiver();
        agentRepository.save(agent);
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

    private void verifierRoleAssignable(Role roleAppelant, Role roleDemande) {
        boolean autorise = switch (roleAppelant) {
            case CHEF_POSTE -> roleDemande == Role.AGENT;
            case ADMIN_REGIONAL -> roleDemande == Role.AGENT || roleDemande == Role.CHEF_POSTE;
            case ADMIN_NATIONAL -> true;
            default -> false;
        };
        if (!autorise) {
            throw new AccesRefuseException("Role non assignable par l'appelant.");
        }
    }

    private void verifierPerimetrePoste(Agent appelant, Poste posteCible) {
        boolean autorise = switch (appelant.getRole()) {
            case CHEF_POSTE -> appelant.getPoste().getId().equals(posteCible.getId());
            case ADMIN_REGIONAL -> appelant.getPoste().getRegion().getId().equals(posteCible.getRegion().getId());
            case ADMIN_NATIONAL -> true;
            default -> false;
        };
        if (!autorise) {
            throw new AccesRefuseException("Poste hors perimetre de l'appelant.");
        }
    }
}
