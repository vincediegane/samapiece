package sn.samapiece.enregistrement;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PieceRepository extends JpaRepository<Piece, UUID> {

    List<Piece> findByTypeDocumentAndStatutAndNomTitulaireIgnoreCase(
            TypeDocument typeDocument, StatutPiece statut, String nomTitulaire);
}
