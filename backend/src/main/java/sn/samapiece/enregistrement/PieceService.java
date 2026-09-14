package sn.samapiece.enregistrement;

import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.samapiece.enregistrement.NumeroDocumentHasher.NumeroDocumentHache;
import sn.samapiece.enregistrement.web.CreerPieceRequest;
import sn.samapiece.enregistrement.web.DeblocageRequest;
import sn.samapiece.enregistrement.web.PieceResponse;
import sn.samapiece.enregistrement.web.RetraitRequest;
import sn.samapiece.enregistrement.web.SignalerRequest;
import sn.samapiece.iam.Agent;
import sn.samapiece.iam.AgentRepository;
import sn.samapiece.iam.AccesRefuseException;
import sn.samapiece.iam.security.PerimetrePoste;
import sn.samapiece.recherche.PieceRechercheDocument;
import sn.samapiece.referentiel.Poste;

@Service
public class PieceService {

    private final PieceRepository pieceRepository;
    private final AgentRepository agentRepository;
    private final RetraitRepository retraitRepository;
    private final PieceNumeroFicheGenerator numeroFicheGenerator;
    private final NumeroDocumentHasher numeroDocumentHasher;
    private final ApplicationEventPublisher eventPublisher;
    private final PieceRecuPdfGenerator pieceRecuPdfGenerator;

    public PieceService(
            PieceRepository pieceRepository,
            AgentRepository agentRepository,
            RetraitRepository retraitRepository,
            PieceNumeroFicheGenerator numeroFicheGenerator,
            NumeroDocumentHasher numeroDocumentHasher,
            ApplicationEventPublisher eventPublisher,
            PieceRecuPdfGenerator pieceRecuPdfGenerator) {
        this.pieceRepository = pieceRepository;
        this.agentRepository = agentRepository;
        this.retraitRepository = retraitRepository;
        this.numeroFicheGenerator = numeroFicheGenerator;
        this.numeroDocumentHasher = numeroDocumentHasher;
        this.eventPublisher = eventPublisher;
        this.pieceRecuPdfGenerator = pieceRecuPdfGenerator;
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

        eventPublisher.publishEvent(new PieceDisponibleEvent(
                piece.getId(),
                piece.getTypeDocument(),
                piece.getNomTitulaire(),
                piece.getPrenomTitulaire(),
                request.numeroDocument(),
                piece.getDateNaissanceTitulaire(),
                piece.getNumeroFiche(),
                piece.getPoste().getNom()));

        return PieceResponse.of(piece);
    }

    @Transactional(readOnly = true)
    public PieceResponse consulter(UUID pieceId) {
        Agent appelant = appelantCourant();
        Piece piece = pieceRepository.findById(pieceId)
                .orElseThrow(() -> new PieceIntrouvableException(pieceId));

        if (!appelant.getPoste().getId().equals(piece.getPoste().getId())) {
            throw new AccesRefuseException("Poste hors perimetre pour cette piece.");
        }

        return PieceResponse.of(piece);
    }

    @Transactional
    public PieceResponse retirer(UUID pieceId, RetraitRequest request) {
        Agent appelant = appelantCourant();
        Piece piece = pieceRepository.findById(pieceId)
                .orElseThrow(() -> new PieceIntrouvableException(pieceId));

        if (!appelant.getPoste().getId().equals(piece.getPoste().getId())) {
            throw new AccesRefuseException("Poste hors perimetre pour cette piece.");
        }

        piece.retirer();

        retraitRepository.saveAndFlush(new Retrait(
                piece, appelant, request.nomReclamant(), request.pieceJustificativePresentee()));

        republierIndexation(piece);

        return PieceResponse.of(piece);
    }

    @Transactional
    public PieceResponse signaler(UUID pieceId, SignalerRequest request) {
        Agent appelant = appelantCourant();
        Piece piece = pieceRepository.findById(pieceId)
                .orElseThrow(() -> new PieceIntrouvableException(pieceId));

        if (!appelant.getPoste().getId().equals(piece.getPoste().getId())) {
            throw new AccesRefuseException("Poste hors perimetre pour cette piece.");
        }

        piece.signaler(request.statutCible(), request.motif(), appelant);

        republierIndexation(piece);

        return PieceResponse.of(piece);
    }

    @Transactional(readOnly = true)
    public RecuPdf genererRecu(UUID pieceId) {
        Agent appelant = appelantCourant();
        Piece piece = pieceRepository.findById(pieceId)
                .orElseThrow(() -> new PieceIntrouvableException(pieceId));

        if (!appelant.getPoste().getId().equals(piece.getPoste().getId())) {
            throw new AccesRefuseException("Poste hors perimetre pour cette piece.");
        }

        byte[] contenu = pieceRecuPdfGenerator.genererPdf(piece);
        return new RecuPdf(contenu, piece.getNumeroFiche());
    }

    public record RecuPdf(byte[] contenu, String numeroFiche) {
    }

    @Transactional
    public PieceResponse debloquer(UUID pieceId, DeblocageRequest request) {
        Agent appelant = appelantCourant();
        Piece piece = pieceRepository.findById(pieceId)
                .orElseThrow(() -> new PieceIntrouvableException(pieceId));

        if (!PerimetrePoste.estDansPerimetre(appelant, piece.getPoste())) {
            throw new AccesRefuseException("Poste/region hors perimetre pour cette piece.");
        }

        piece.debloquer(request.motif(), appelant);

        republierIndexation(piece);

        return PieceResponse.of(piece);
    }

    private void republierIndexation(Piece piece) {
        PieceRechercheDocument document = new PieceRechercheDocument(
                piece.getId(),
                piece.getTypeDocument().name(),
                piece.getNomTitulaire(),
                piece.getPrenomTitulaire(),
                piece.getPoste().getNom(),
                piece.getStatut().name());
        eventPublisher.publishEvent(new PieceIndexableEvent(document));
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
