package sn.samapiece.recherche;

import java.util.List;
import org.springframework.stereotype.Service;
import sn.samapiece.enregistrement.NumeroDocumentHasher;
import sn.samapiece.enregistrement.Piece;
import sn.samapiece.enregistrement.PieceRepository;
import sn.samapiece.enregistrement.StatutPiece;
import sn.samapiece.recherche.web.RecherchePubliqueRequest;
import sn.samapiece.recherche.web.RecherchePubliqueResponse;

@Service
public class RecherchePubliqueService {

    private final PieceRepository pieceRepository;
    private final NumeroDocumentHasher hasher;

    public RecherchePubliqueService(PieceRepository pieceRepository, NumeroDocumentHasher hasher) {
        this.pieceRepository = pieceRepository;
        this.hasher = hasher;
    }

    public RecherchePubliqueResponse rechercher(RecherchePubliqueRequest requete) {
        if (!requete.estSuffisant()) {
            throw new CriteresInsuffisantsException();
        }

        return pieceRepository
                .findByTypeDocumentAndStatutAndNomTitulaireIgnoreCase(
                        requete.typeDocument(), StatutPiece.DISPONIBLE, requete.nomTitulaire())
                .stream()
                .filter(candidat -> correspond(candidat, requete))
                .findFirst()
                .map(candidat -> RecherchePubliqueResponse.trouve(
                        candidat.getTypeDocument(), candidat.getPoste(), candidat.getNumeroFiche()))
                .orElseGet(RecherchePubliqueResponse::nonTrouve);
    }

    // Verification finale, la seule qui compte : statut frais, type/nom/prenom exacts, discriminants.
    // Si numero ET date sont tous deux fournis, les deux doivent correspondre (aucun retour anticipe
    // en cas de succes partiel).
    private boolean correspond(Piece candidat, RecherchePubliqueRequest requete) {
        if (candidat.getStatut() != StatutPiece.DISPONIBLE) {
            return false;
        }
        if (candidat.getTypeDocument() != requete.typeDocument()) {
            return false;
        }
        if (!candidat.getNomTitulaire().equalsIgnoreCase(requete.nomTitulaire())) {
            return false;
        }
        if (requete.prenomTitulaire() != null && !requete.prenomTitulaire().isBlank()
                && !candidat.getPrenomTitulaire().equalsIgnoreCase(requete.prenomTitulaire())) {
            return false;
        }
        boolean numeroFourni = requete.numeroDocument() != null && !requete.numeroDocument().isBlank();
        if (numeroFourni && !hasher.verifier(
                requete.numeroDocument(), candidat.getNumeroDocumentSel(), candidat.getNumeroDocumentHash())) {
            return false;
        }
        if (requete.dateNaissanceTitulaire() != null
                && !requete.dateNaissanceTitulaire().equals(candidat.getDateNaissanceTitulaire())) {
            return false;
        }
        return true;
    }
}
