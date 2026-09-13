package sn.samapiece.enregistrement;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.samapiece.enregistrement.NumeroDocumentHasher.NumeroDocumentHache;
import sn.samapiece.enregistrement.web.CreerPieceRequest;
import sn.samapiece.enregistrement.web.PieceResponse;
import sn.samapiece.iam.Agent;
import sn.samapiece.iam.AgentRepository;
import sn.samapiece.iam.AccesRefuseException;
import sn.samapiece.recherche.PieceRechercheDocument;
import sn.samapiece.referentiel.Poste;

@Service
public class PieceService {

    private final PieceRepository pieceRepository;
    private final AgentRepository agentRepository;
    private final PieceNumeroFicheGenerator numeroFicheGenerator;
    private final NumeroDocumentHasher numeroDocumentHasher;
    private final ApplicationEventPublisher eventPublisher;

    public PieceService(
            PieceRepository pieceRepository,
            AgentRepository agentRepository,
            PieceNumeroFicheGenerator numeroFicheGenerator,
            NumeroDocumentHasher numeroDocumentHasher,
            ApplicationEventPublisher eventPublisher) {
        this.pieceRepository = pieceRepository;
        this.agentRepository = agentRepository;
        this.numeroFicheGenerator = numeroFicheGenerator;
        this.numeroDocumentHasher = numeroDocumentHasher;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public PieceResponse creer(CreerPieceRequest request) {
        Agent appelant = appelantCourant();
        Poste poste = appelant.getPoste();

        NumeroDocumentHache hache = numeroDocumentHasher.hacher(request.numeroDocument());
        String numeroFiche = numeroFicheGenerator.genererNumeroFiche(poste.getId(), request.dateDepot());

        Piece piece = new Piece(
                numeroFiche,
                poste,
                appelant,
                request.typeDocument(),
                request.nomTitulaire(),
                request.prenomTitulaire(),
                hache.hash(),
                hache.sel(),
                hache.masque(),
                request.dateNaissanceTitulaire(),
                request.dateDepot(),
                request.etatDocument(),
                request.remarques());

        pieceRepository.saveAndFlush(piece);

        PieceRechercheDocument document = new PieceRechercheDocument(
                piece.getId(),
                piece.getTypeDocument().name(),
                piece.getNomTitulaire(),
                piece.getPrenomTitulaire(),
                piece.getPoste().getNom(),
                piece.getStatut().name());
        eventPublisher.publishEvent(new PieceIndexableEvent(document));

        return PieceResponse.of(piece);
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
