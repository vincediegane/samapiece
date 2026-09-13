package sn.samapiece.recherche;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.SearchRequest;
import com.meilisearch.sdk.model.Searchable;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import sn.samapiece.enregistrement.NumeroDocumentHasher;
import sn.samapiece.enregistrement.Piece;
import sn.samapiece.enregistrement.PieceRepository;
import sn.samapiece.enregistrement.StatutPiece;
import sn.samapiece.recherche.web.RecherchePubliqueRequest;
import sn.samapiece.recherche.web.RecherchePubliqueResponse;

@Service
public class RecherchePubliqueService {

    private static final Logger LOG = LoggerFactory.getLogger(RecherchePubliqueService.class);
    private static final int LIMITE_SHORTLIST = 20;

    private final Client client;
    private final MeilisearchProperties proprietes;
    private final PieceRepository pieceRepository;
    private final NumeroDocumentHasher hasher;

    public RecherchePubliqueService(
            Client client,
            MeilisearchProperties proprietes,
            PieceRepository pieceRepository,
            NumeroDocumentHasher hasher) {
        this.client = client;
        this.proprietes = proprietes;
        this.pieceRepository = pieceRepository;
        this.hasher = hasher;
    }

    public RecherchePubliqueResponse rechercher(RecherchePubliqueRequest requete) {
        if (!requete.estSuffisant()) {
            throw new CriteresInsuffisantsException();
        }

        return obtenirCandidats(requete).stream()
                .filter(candidat -> correspond(candidat, requete))
                .findFirst()
                .map(candidat -> RecherchePubliqueResponse.trouve(
                        candidat.getTypeDocument(), candidat.getPoste(), candidat.getNumeroFiche()))
                .orElseGet(RecherchePubliqueResponse::nonTrouve);
    }

    // Etape 1 : shortlist Meilisearch (typo-tolerante) -> reload par id en PostgreSQL.
    // Etape 2 (si Meilisearch leve une exception) : repli requete PostgreSQL directe bornee.
    private List<Piece> obtenirCandidats(RecherchePubliqueRequest requete) {
        try {
            return obtenirCandidatsViaMeilisearch(requete);
        } catch (Exception e) {
            LOG.warn("Meilisearch indisponible pour la recherche publique, repli sur PostgreSQL direct", e);
            return pieceRepository.findByTypeDocumentAndStatutAndNomTitulaireIgnoreCase(
                    requete.typeDocument(), StatutPiece.DISPONIBLE, requete.nomTitulaire());
        }
    }

    private List<Piece> obtenirCandidatsViaMeilisearch(RecherchePubliqueRequest requete) throws Exception {
        Index index = client.index(proprietes.getIndexPieces());
        SearchRequest searchRequest = new SearchRequest(construireTexteRequete(requete)).setLimit(LIMITE_SHORTLIST);
        Searchable resultats = index.search(searchRequest);
        List<UUID> ids = resultats.getHits().stream()
                .map(hit -> UUID.fromString((String) hit.get("id")))
                .toList();
        return ids.isEmpty() ? List.of() : pieceRepository.findAllById(ids);
    }

    // Texte libre : type + nom (+ prenom) — pas de clause filter= (voir Ecarts identifies, point 2).
    private String construireTexteRequete(RecherchePubliqueRequest requete) {
        StringBuilder texte = new StringBuilder(requete.typeDocument().name()).append(' ').append(requete.nomTitulaire());
        if (requete.prenomTitulaire() != null && !requete.prenomTitulaire().isBlank()) {
            texte.append(' ').append(requete.prenomTitulaire());
        }
        return texte.toString();
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
