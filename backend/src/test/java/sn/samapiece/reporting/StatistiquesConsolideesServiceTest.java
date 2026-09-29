package sn.samapiece.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import sn.samapiece.enregistrement.PieceRepository;
import sn.samapiece.iam.Agent;
import sn.samapiece.iam.AgentRepository;

class StatistiquesConsolideesServiceTest {

    private PieceRepository pieceRepository;
    private StatistiquesConsolideesService service;

    @BeforeEach
    void preparer() {
        pieceRepository = mock(PieceRepository.class);
        AgentRepository agentRepository = mock(AgentRepository.class);
        StatistiquesProperties proprietes = new StatistiquesProperties();
        Agent agent = mock(Agent.class);
        when(agent.isActif()).thenReturn(true);
        when(agentRepository.findByMatricule("PN-1")).thenReturn(Optional.of(agent));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("PN-1", null, List.of()));
        service = new StatistiquesConsolideesService(pieceRepository, agentRepository, proprietes);
    }

    @AfterEach
    void nettoyer() {
        SecurityContextHolder.clearContext();
    }

    private record LigneStub(
            String nom, long nombre, Long total, Double moyenne, Long max, long depassant)
            implements StockParPosteAgrege {
        public java.util.UUID getPosteId() { return null; }
        public String getPosteNom() { return nom; }
        public java.util.UUID getRegionId() { return null; }
        public String getRegionNom() { return null; }
        public long getNombrePieces() { return nombre; }
        public Long getAncienneteTotaleJours() { return total; }
        public Double getAncienneteMoyenneJours() { return moyenne; }
        public Long getAncienneteMaxJours() { return max; }
        public long getNombreDepassant() { return depassant; }
    }

    private StockParPosteAgrege ligne(
            String nom, long nombre, Long total, Double moyenne, Long max, long depassant) {
        return new LigneStub(nom, nombre, total, moyenne, max, depassant);
    }

    @Test
    void consulterNationale_shouldCalculerTotauxEtMoyennePonderee() {
        when(pieceRepository.agregerStockParPosteNational(180)).thenReturn(List.of(
                ligne("A", 2, 230L, 115.0, 200L, 1),
                ligne("B", 3, 252L, 84.0, 120L, 0),
                ligne("C", 0, 0L, null, null, 0)));

        StatistiquesConsolideesResponse reponse = service.consulterNationale();

        assertThat(reponse.portee()).isEqualTo("NATIONALE");
        assertThat(reponse.regionId()).isNull();
        assertThat(reponse.regionNom()).isNull();
        assertThat(reponse.seuilAncienneteJours()).isEqualTo(180);
        assertThat(reponse.postes()).hasSize(3);
        var totaux = reponse.totaux();
        assertThat(totaux.nombrePiecesEnAttente()).isEqualTo(5);
        assertThat(totaux.ancienneteMoyenneJours()).isEqualTo(482 / 5.0);
        assertThat(totaux.ancienneteMaxJours()).isEqualTo(200L);
        assertThat(totaux.nombrePiecesDepassantSeuil()).isEqualTo(1);
        assertThat(totaux.nombrePostes()).isEqualTo(3);
        assertThat(totaux.nombrePostesEnDepassement()).isEqualTo(1);
    }

    @Test
    void consulterNationale_listeVide_shouldRetournerTotauxNuls() {
        when(pieceRepository.agregerStockParPosteNational(180)).thenReturn(List.of());

        StatistiquesConsolideesResponse reponse = service.consulterNationale();

        assertThat(reponse.postes()).isEmpty();
        assertThat(reponse.totaux().nombrePiecesEnAttente()).isZero();
        assertThat(reponse.totaux().ancienneteMoyenneJours()).isNull();
        assertThat(reponse.totaux().ancienneteMaxJours()).isNull();
        assertThat(reponse.totaux().nombrePostes()).isZero();
        assertThat(reponse.totaux().nombrePostesEnDepassement()).isZero();
    }

    @Test
    void consulterNationale_postesSansPiece_shouldLaisserMoyenneEtMaxNuls() {
        when(pieceRepository.agregerStockParPosteNational(180)).thenReturn(List.of(
                ligne("A", 0, 0L, null, null, 0), ligne("B", 0, 0L, null, null, 0)));

        var totaux = service.consulterNationale().totaux();

        assertThat(totaux.ancienneteMoyenneJours()).isNull();
        assertThat(totaux.ancienneteMaxJours()).isNull();
        assertThat(totaux.nombrePostes()).isEqualTo(2);
    }
}
