package sn.samapiece.enregistrement;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sn.samapiece.reporting.StockPosteAgrege;

public interface PieceRepository extends JpaRepository<Piece, UUID> {

    List<Piece> findByTypeDocumentAndStatutAndNomTitulaireIgnoreCase(
            TypeDocument typeDocument, StatutPiece statut, String nomTitulaire);

    List<Piece> findByTypeDocumentAndStatutIn(TypeDocument typeDocument, Collection<StatutPiece> statuts);

    @Query(value = """
            SELECT COUNT(*) AS nombrePieces,
                   AVG(CURRENT_DATE - date_depot) AS ancienneteMoyenneJours,
                   MAX(CURRENT_DATE - date_depot) AS ancienneteMaxJours
            FROM piece
            WHERE poste_id = :posteId
              AND statut IN ('disponible', 'reclamee')
            """, nativeQuery = true)
    StockPosteAgrege agregerStockParPoste(@Param("posteId") UUID posteId);

    @Query(value = """
            SELECT COUNT(*)
            FROM piece
            WHERE poste_id = :posteId
              AND statut IN ('disponible', 'reclamee')
              AND (CURRENT_DATE - date_depot) > :seuilJours
            """, nativeQuery = true)
    long compterDepassantSeuil(@Param("posteId") UUID posteId, @Param("seuilJours") int seuilJours);
}
