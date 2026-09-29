package sn.samapiece.enregistrement;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sn.samapiece.reporting.StockParPosteAgrege;
import sn.samapiece.reporting.StockPosteAgrege;

public interface PieceRepository extends JpaRepository<Piece, UUID> {

    List<Piece> findByTypeDocumentAndStatutAndNomTitulaireIgnoreCase(
            TypeDocument typeDocument, StatutPiece statut, String nomTitulaire);

    List<Piece> findByTypeDocumentAndStatutIn(TypeDocument typeDocument, Collection<StatutPiece> statuts);

    @Query(value = """
            SELECT p FROM Piece p
            WHERE p.poste.id = :posteId AND p.statut IN :statuts
            ORDER BY p.dateDepot ASC, p.numeroFiche ASC
            """,
            countQuery = """
            SELECT COUNT(p) FROM Piece p WHERE p.poste.id = :posteId AND p.statut IN :statuts
            """)
    Page<Piece> findByPosteEtStatuts(
            @Param("posteId") UUID posteId,
            @Param("statuts") Collection<StatutPiece> statuts,
            Pageable pageable);

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

    @Query(value = """
            SELECT po.id AS "posteId", po.nom AS "posteNom", r.id AS "regionId", r.nom AS "regionNom",
                   COUNT(p.id) AS "nombrePieces",
                   COALESCE(SUM(CURRENT_DATE - p.date_depot), 0) AS "ancienneteTotaleJours",
                   AVG(CURRENT_DATE - p.date_depot) AS "ancienneteMoyenneJours",
                   MAX(CURRENT_DATE - p.date_depot) AS "ancienneteMaxJours",
                   COUNT(p.id) FILTER (WHERE (CURRENT_DATE - p.date_depot) > :seuilJours) AS "nombreDepassant"
            FROM poste po
            JOIN region r ON r.id = po.region_id
            LEFT JOIN piece p ON p.poste_id = po.id AND p.statut IN ('disponible', 'reclamee')
            GROUP BY po.id, po.nom, r.id, r.nom
            ORDER BY COUNT(p.id) FILTER (WHERE (CURRENT_DATE - p.date_depot) > :seuilJours) DESC, po.nom ASC, po.id ASC
            """, nativeQuery = true)
    List<StockParPosteAgrege> agregerStockParPosteNational(@Param("seuilJours") int seuilJours);

    @Query(value = """
            SELECT po.id AS "posteId", po.nom AS "posteNom", r.id AS "regionId", r.nom AS "regionNom",
                   COUNT(p.id) AS "nombrePieces",
                   COALESCE(SUM(CURRENT_DATE - p.date_depot), 0) AS "ancienneteTotaleJours",
                   AVG(CURRENT_DATE - p.date_depot) AS "ancienneteMoyenneJours",
                   MAX(CURRENT_DATE - p.date_depot) AS "ancienneteMaxJours",
                   COUNT(p.id) FILTER (WHERE (CURRENT_DATE - p.date_depot) > :seuilJours) AS "nombreDepassant"
            FROM poste po
            JOIN region r ON r.id = po.region_id
            LEFT JOIN piece p ON p.poste_id = po.id AND p.statut IN ('disponible', 'reclamee')
            WHERE po.region_id = :regionId
            GROUP BY po.id, po.nom, r.id, r.nom
            ORDER BY COUNT(p.id) FILTER (WHERE (CURRENT_DATE - p.date_depot) > :seuilJours) DESC, po.nom ASC, po.id ASC
            """, nativeQuery = true)
    List<StockParPosteAgrege> agregerStockParPosteRegion(
            @Param("regionId") UUID regionId, @Param("seuilJours") int seuilJours);
}
