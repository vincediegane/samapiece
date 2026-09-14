package sn.samapiece.enregistrement;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Evenement Spring interne publie a la creation d'une {@link Piece}, jamais serialise sur un
 * broker (consomme uniquement en-process par {@code AlerteCorrespondancePieceMatchingListener}).
 * {@code numeroDocumentClair} ne doit **jamais** etre logue : c'est le seul point du systeme ou
 * cette valeur existe encore en memoire hors de la requete HTTP d'origine ({@link Piece} ne la
 * persiste jamais, seulement son hash).
 */
public record PieceDisponibleEvent(
        UUID pieceId,
        TypeDocument typeDocument,
        String nomTitulaire,
        String prenomTitulaire,
        String numeroDocumentClair,
        LocalDate dateNaissanceTitulaire,
        String numeroFiche,
        String posteNom) {
}
