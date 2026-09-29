package sn.samapiece.reporting;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.samapiece.enregistrement.PieceRepository;
import sn.samapiece.iam.AccesRefuseException;
import sn.samapiece.iam.Agent;
import sn.samapiece.iam.AgentRepository;
import sn.samapiece.referentiel.Region;

@Service
public class StatistiquesConsolideesService {

    private final PieceRepository pieceRepository;
    private final AgentRepository agentRepository;
    private final StatistiquesProperties statistiquesProperties;

    public StatistiquesConsolideesService(
            PieceRepository pieceRepository,
            AgentRepository agentRepository,
            StatistiquesProperties statistiquesProperties) {
        this.pieceRepository = pieceRepository;
        this.agentRepository = agentRepository;
        this.statistiquesProperties = statistiquesProperties;
    }

    @Transactional(readOnly = true)
    public StatistiquesConsolideesResponse consulterRegionale() {
        Agent appelant = appelantCourant();
        Region region = appelant.getPoste().getRegion();
        int seuil = statistiquesProperties.getSeuilAncienneteJours();
        List<StockParPosteAgrege> lignes = pieceRepository.agregerStockParPosteRegion(region.getId(), seuil);
        return construire("REGIONALE", region.getId(), region.getNom(), seuil, lignes);
    }

    @Transactional(readOnly = true)
    public StatistiquesConsolideesResponse consulterNationale() {
        appelantCourant();
        int seuil = statistiquesProperties.getSeuilAncienneteJours();
        List<StockParPosteAgrege> lignes = pieceRepository.agregerStockParPosteNational(seuil);
        return construire("NATIONALE", null, null, seuil, lignes);
    }

    private StatistiquesConsolideesResponse construire(
            String portee, UUID regionId, String regionNom, int seuil, List<StockParPosteAgrege> lignes) {
        long totalPieces = 0;
        long totalJours = 0;
        long totalDepassant = 0;
        Long max = null;
        int enDepassement = 0;
        for (StockParPosteAgrege l : lignes) {
            totalPieces += l.getNombrePieces();
            totalJours += l.getAncienneteTotaleJours() == null ? 0 : l.getAncienneteTotaleJours();
            totalDepassant += l.getNombreDepassant();
            if (l.getAncienneteMaxJours() != null && (max == null || l.getAncienneteMaxJours() > max)) {
                max = l.getAncienneteMaxJours();
            }
            if (l.getNombreDepassant() > 0) {
                enDepassement++;
            }
        }
        Double moyenne = totalPieces == 0 ? null : (double) totalJours / totalPieces;
        var totaux = new StatistiquesConsolideesResponse.Totaux(
                totalPieces, moyenne, max, totalDepassant, lignes.size(), enDepassement);
        var postes = lignes.stream()
                .map(l -> new StatistiquesConsolideesResponse.LignePoste(
                        l.getPosteId(),
                        l.getPosteNom(),
                        l.getRegionNom(),
                        l.getNombrePieces(),
                        l.getAncienneteMoyenneJours(),
                        l.getAncienneteMaxJours(),
                        l.getNombreDepassant()))
                .toList();
        return new StatistiquesConsolideesResponse(portee, regionId, regionNom, seuil, totaux, postes);
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
