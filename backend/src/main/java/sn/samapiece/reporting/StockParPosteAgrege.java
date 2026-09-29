package sn.samapiece.reporting;

import java.util.UUID;

/** Stock agrege par poste (une ligne par poste), distinct de {@link StockPosteAgrege} (un seul poste). */
public interface StockParPosteAgrege {
    UUID getPosteId();
    String getPosteNom();
    UUID getRegionId();
    String getRegionNom();
    long getNombrePieces();
    Long getAncienneteTotaleJours();
    Double getAncienneteMoyenneJours();
    Long getAncienneteMaxJours();
    long getNombreDepassant();
}
