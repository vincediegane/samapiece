package sn.samapiece.reporting;

import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.samapiece.enregistrement.PieceRepository;
import sn.samapiece.iam.AccesRefuseException;
import sn.samapiece.iam.Agent;
import sn.samapiece.iam.AgentRepository;
import sn.samapiece.iam.PosteIntrouvableException;
import sn.samapiece.iam.security.PerimetrePoste;
import sn.samapiece.referentiel.Poste;
import sn.samapiece.referentiel.PosteRepository;

@Service
public class StatistiquesPosteService {

    private final PieceRepository pieceRepository;
    private final AgentRepository agentRepository;
    private final PosteRepository posteRepository;
    private final StatistiquesProperties statistiquesProperties;

    public StatistiquesPosteService(
            PieceRepository pieceRepository,
            AgentRepository agentRepository,
            PosteRepository posteRepository,
            StatistiquesProperties statistiquesProperties) {
        this.pieceRepository = pieceRepository;
        this.agentRepository = agentRepository;
        this.posteRepository = posteRepository;
        this.statistiquesProperties = statistiquesProperties;
    }

    @Transactional(readOnly = true)
    public StatistiquesPosteResponse consulter(UUID posteId) {
        Agent appelant = appelantCourant();
        Poste poste = posteRepository.findById(posteId)
                .orElseThrow(() -> new PosteIntrouvableException(posteId));

        if (!PerimetrePoste.estDansPerimetre(appelant, poste)) {
            throw new AccesRefuseException("Poste/region hors perimetre pour ces statistiques.");
        }

        StockPosteAgrege agrege = pieceRepository.agregerStockParPoste(posteId);
        int seuil = statistiquesProperties.getSeuilAncienneteJours();
        long depassant = pieceRepository.compterDepassantSeuil(posteId, seuil);

        return new StatistiquesPosteResponse(
                poste.getId(),
                poste.getNom(),
                agrege.getNombrePieces(),
                agrege.getAncienneteMoyenneJours(),
                agrege.getAncienneteMaxJours(),
                seuil,
                depassant);
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
