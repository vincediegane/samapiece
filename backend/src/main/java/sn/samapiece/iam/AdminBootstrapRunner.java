package sn.samapiece.iam;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sn.samapiece.referentiel.Poste;
import sn.samapiece.referentiel.PosteRepository;

@Component
public class AdminBootstrapRunner implements CommandLineRunner {

    private static final Logger LOG = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final AgentRepository agentRepository;
    private final PosteRepository posteRepository;
    private final PasswordEncoder passwordEncoder;
    private final MotDePasseTemporaireGenerator motDePasseTemporaireGenerator;
    private final AdminBootstrapProperties properties;

    public AdminBootstrapRunner(
            AgentRepository agentRepository, PosteRepository posteRepository, PasswordEncoder passwordEncoder,
            MotDePasseTemporaireGenerator motDePasseTemporaireGenerator, AdminBootstrapProperties properties) {
        this.agentRepository = agentRepository;
        this.posteRepository = posteRepository;
        this.passwordEncoder = passwordEncoder;
        this.motDePasseTemporaireGenerator = motDePasseTemporaireGenerator;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!properties.isEnabled()) {
            LOG.info("Bootstrap du premier compte administrateur desactive (BOOTSTRAP_ADMIN_ENABLED != true).");
            return;
        }
        if (agentRepository.count() != 0) {
            LOG.info("Bootstrap du premier compte administrateur ignore : au moins un agent existe deja.");
            return;
        }

        UUID posteId = resoudrePosteId();
        String matricule = resoudreChaineObligatoire(properties.getMatricule(), "BOOTSTRAP_ADMIN_MATRICULE");
        String nom = resoudreChaineObligatoire(properties.getNom(), "BOOTSTRAP_ADMIN_NOM");

        Poste poste = posteRepository.findById(posteId)
                .orElseThrow(() -> new IllegalStateException(
                        "BOOTSTRAP_ADMIN_POSTE_ID reference un poste introuvable : " + posteId));

        String motDePasseTemporaire = motDePasseTemporaireGenerator.generer();
        Agent admin = new Agent(
                poste, matricule, nom, Role.ADMIN_NATIONAL,
                passwordEncoder.encode(motDePasseTemporaire), true);
        agentRepository.saveAndFlush(admin);

        LOG.warn(
                "Premier compte administrateur cree - matricule={} mot de passe temporaire={} "
                        + "(a changer immediatement via PUT /api/v1/agents/moi/mot-de-passe)",
                matricule, motDePasseTemporaire);
    }

    private UUID resoudrePosteId() {
        String posteId = properties.getPosteId();
        if (posteId == null || posteId.isBlank()) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_POSTE_ID est obligatoire quand le bootstrap est actif.");
        }
        try {
            return UUID.fromString(posteId);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_POSTE_ID n'est pas un UUID valide : " + posteId, e);
        }
    }

    private String resoudreChaineObligatoire(String valeur, String nomVariable) {
        if (valeur == null || valeur.isBlank()) {
            throw new IllegalStateException(nomVariable + " est obligatoire quand le bootstrap est actif.");
        }
        return valeur;
    }
}
