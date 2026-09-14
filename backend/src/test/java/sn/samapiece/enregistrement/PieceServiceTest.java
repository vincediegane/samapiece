package sn.samapiece.enregistrement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import sn.samapiece.enregistrement.NumeroDocumentHasher.NumeroDocumentHache;
import sn.samapiece.enregistrement.web.CreerPieceRequest;
import sn.samapiece.iam.Agent;
import sn.samapiece.iam.AgentRepository;
import sn.samapiece.iam.Role;
import sn.samapiece.referentiel.Poste;
import sn.samapiece.referentiel.Region;
import sn.samapiece.referentiel.TypePoste;

class PieceServiceTest {

    private final PieceRepository pieceRepository = mock(PieceRepository.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final PieceNumeroFicheGenerator numeroFicheGenerator = mock(PieceNumeroFicheGenerator.class);
    private final NumeroDocumentHasher numeroDocumentHasher = mock(NumeroDocumentHasher.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private final PieceService pieceService = new PieceService(
            pieceRepository, agentRepository, numeroFicheGenerator, numeroDocumentHasher, eventPublisher);

    @AfterEach
    void nettoyerContexteSecurite() {
        SecurityContextHolder.clearContext();
    }

    private Poste poste() {
        Region region = new Region("Dakar");
        return new Poste(
                region, "Commissariat Central Dakar", TypePoste.POLICE,
                "Place de l'Indépendance, Dakar", null, "{}", null, null);
    }

    private Agent connecterCommeAppelant(Poste poste) {
        Agent appelant = new Agent(poste, "PN-2024-00123", "Diop Awa", Role.AGENT, "$2a$10$hashopaque");
        when(agentRepository.findByMatricule("PN-2024-00123")).thenReturn(Optional.of(appelant));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("PN-2024-00123", null, List.of()));
        return appelant;
    }

    private CreerPieceRequest requete() {
        return new CreerPieceRequest(
                TypeDocument.CNI,
                "Fall",
                "Moussa",
                "1234567890123",
                LocalDate.of(1990, 5, 12),
                LocalDate.of(2026, 9, 13),
                "bon état",
                "trouvée sur la voie publique");
    }

    @Test
    void creer_devraitPublierPieceDisponibleEventAvecLesChampsAttendus() {
        Poste poste = poste();
        connecterCommeAppelant(poste);
        CreerPieceRequest request = requete();
        when(numeroDocumentHasher.hacher("1234567890123"))
                .thenReturn(new NumeroDocumentHache("hash", "sel", "masque"));
        when(numeroFicheGenerator.genererNumeroFiche(poste.getId(), request.dateDepot()))
                .thenReturn("PC-3F2A9C1B-2026-00001");
        when(pieceRepository.saveAndFlush(any(Piece.class))).thenAnswer(invocation -> {
            Piece piece = invocation.getArgument(0);
            ReflectionTestUtils.setField(piece, "id", UUID.randomUUID());
            return piece;
        });

        pieceService.creer(request);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(2)).publishEvent(captor.capture());
        List<Object> evenementsPublies = captor.getAllValues();

        assertThat(evenementsPublies).hasSize(2);
        assertThat(evenementsPublies.get(0)).isInstanceOf(PieceIndexableEvent.class);
        assertThat(evenementsPublies.get(1)).isInstanceOf(PieceDisponibleEvent.class);

        PieceDisponibleEvent evenement = (PieceDisponibleEvent) evenementsPublies.get(1);
        assertThat(evenement.numeroDocumentClair()).isEqualTo(request.numeroDocument());
        assertThat(evenement.typeDocument()).isEqualTo(request.typeDocument());
        assertThat(evenement.nomTitulaire()).isEqualTo(request.nomTitulaire());
        assertThat(evenement.prenomTitulaire()).isEqualTo(request.prenomTitulaire());
        assertThat(evenement.dateNaissanceTitulaire()).isEqualTo(request.dateNaissanceTitulaire());
        assertThat(evenement.numeroFiche()).isEqualTo("PC-3F2A9C1B-2026-00001");
        assertThat(evenement.posteNom()).isEqualTo(poste.getNom());
        assertThat(evenement.pieceId()).isNotNull();
    }
}
