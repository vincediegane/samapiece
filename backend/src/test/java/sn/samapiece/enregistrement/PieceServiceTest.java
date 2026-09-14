package sn.samapiece.enregistrement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import sn.samapiece.enregistrement.web.DeblocageRequest;
import sn.samapiece.enregistrement.web.RetraitRequest;
import sn.samapiece.enregistrement.web.SignalerRequest;
import sn.samapiece.iam.AccesRefuseException;
import sn.samapiece.iam.Agent;
import sn.samapiece.iam.AgentRepository;
import sn.samapiece.iam.Role;
import sn.samapiece.recherche.PieceRechercheDocument;
import sn.samapiece.referentiel.Poste;
import sn.samapiece.referentiel.Region;
import sn.samapiece.referentiel.TypePoste;

class PieceServiceTest {

    private final PieceRepository pieceRepository = mock(PieceRepository.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final RetraitRepository retraitRepository = mock(RetraitRepository.class);
    private final PieceNumeroFicheGenerator numeroFicheGenerator = mock(PieceNumeroFicheGenerator.class);
    private final NumeroDocumentHasher numeroDocumentHasher = mock(NumeroDocumentHasher.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final PieceRecuPdfGenerator pieceRecuPdfGenerator = mock(PieceRecuPdfGenerator.class);

    private final PieceService pieceService = new PieceService(
            pieceRepository, agentRepository, retraitRepository, numeroFicheGenerator,
            numeroDocumentHasher, eventPublisher, pieceRecuPdfGenerator);

    @AfterEach
    void nettoyerContexteSecurite() {
        SecurityContextHolder.clearContext();
    }

    private Poste poste() {
        Region region = new Region("Dakar");
        ReflectionTestUtils.setField(region, "id", UUID.randomUUID());
        Poste poste = new Poste(
                region, "Commissariat Central Dakar", TypePoste.POLICE,
                "Place de l'Indépendance, Dakar", null, "{}", null, null);
        ReflectionTestUtils.setField(poste, "id", UUID.randomUUID());
        return poste;
    }

    private Poste autrePoste() {
        Region region = new Region("Thies");
        ReflectionTestUtils.setField(region, "id", UUID.randomUUID());
        Poste poste = new Poste(
                region, "Commissariat Thies", TypePoste.POLICE,
                "Avenue Lat Dior, Thies", null, "{}", null, null);
        ReflectionTestUtils.setField(poste, "id", UUID.randomUUID());
        return poste;
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

    private Piece pieceExistante(Poste poste, Agent agentCreateur) {
        NumeroDocumentHache hache = new NumeroDocumentHasher().hacher("1234567890123");
        Piece piece = new Piece(
                "PC-3F2A9C1B-2026-00001",
                poste,
                agentCreateur,
                TypeDocument.CNI,
                "Fall",
                "Moussa",
                hache.hash(),
                hache.sel(),
                hache.masque(),
                LocalDate.of(1990, 5, 12),
                LocalDate.of(2026, 9, 13),
                "bon état",
                "trouvée sur la voie publique");
        ReflectionTestUtils.setField(piece, "id", UUID.randomUUID());
        return piece;
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

    @Test
    void retirer_avecStatutDisponible_shouldPersisterRetraitEtPasserStatutARetiree() {
        Poste poste = poste();
        Agent appelant = connecterCommeAppelant(poste);
        Piece piece = pieceExistante(poste, appelant);
        when(pieceRepository.findById(piece.getId())).thenReturn(Optional.of(piece));
        RetraitRequest request = new RetraitRequest("Ndiaye Fatou", "Carte d'electeur presentee");

        pieceService.retirer(piece.getId(), request);

        assertThat(piece.getStatut()).isEqualTo(StatutPiece.RETIREE);

        ArgumentCaptor<Retrait> captor = ArgumentCaptor.forClass(Retrait.class);
        verify(retraitRepository).saveAndFlush(captor.capture());
        Retrait retrait = captor.getValue();
        assertThat(retrait.getPiece()).isEqualTo(piece);
        assertThat(retrait.getAgentValidateur()).isEqualTo(appelant);
        assertThat(retrait.getNomReclamant()).isEqualTo("Ndiaye Fatou");
        assertThat(retrait.getPieceJustificativePresentee()).isEqualTo("Carte d'electeur presentee");
    }

    @Test
    void retirer_avecNomReclamantDifferentDuNomTitulaire_shouldAboutirSansRapprochement() {
        Poste poste = poste();
        Agent appelant = connecterCommeAppelant(poste);
        Piece piece = pieceExistante(poste, appelant);
        when(pieceRepository.findById(piece.getId())).thenReturn(Optional.of(piece));
        RetraitRequest request = new RetraitRequest("Un nom totalement different", "piece presentee");

        pieceService.retirer(piece.getId(), request);

        assertThat(piece.getStatut()).isEqualTo(StatutPiece.RETIREE);
        verify(retraitRepository).saveAndFlush(any(Retrait.class));
    }

    @Test
    void retirer_survienneSurPieceDejaRetiree_shouldLeverTransitionStatutInterdite() {
        Poste poste = poste();
        Agent appelant = connecterCommeAppelant(poste);
        Piece piece = pieceExistante(poste, appelant);
        ReflectionTestUtils.setField(piece, "statut", StatutPiece.RETIREE);
        when(pieceRepository.findById(piece.getId())).thenReturn(Optional.of(piece));
        RetraitRequest request = new RetraitRequest("Ndiaye Fatou", "Carte d'electeur presentee");

        assertThatThrownBy(() -> pieceService.retirer(piece.getId(), request))
                .isInstanceOf(TransitionStatutInterditeException.class);
    }

    @Test
    void retirer_commeAgentDunAutrePoste_shouldLeverAccesRefuseException() {
        Poste posteAppelant = poste();
        Agent appelant = connecterCommeAppelant(posteAppelant);
        Piece piece = pieceExistante(autrePoste(), appelant);
        when(pieceRepository.findById(piece.getId())).thenReturn(Optional.of(piece));
        RetraitRequest request = new RetraitRequest("Ndiaye Fatou", "Carte d'electeur presentee");

        assertThatThrownBy(() -> pieceService.retirer(piece.getId(), request))
                .isInstanceOf(AccesRefuseException.class);
    }

    @Test
    void retirer_shouldRepublierPieceIndexableEventAvecStatutRetiree() {
        Poste poste = poste();
        Agent appelant = connecterCommeAppelant(poste);
        Piece piece = pieceExistante(poste, appelant);
        when(pieceRepository.findById(piece.getId())).thenReturn(Optional.of(piece));
        RetraitRequest request = new RetraitRequest("Ndiaye Fatou", "Carte d'electeur presentee");

        pieceService.retirer(piece.getId(), request);

        ArgumentCaptor<PieceIndexableEvent> captor = ArgumentCaptor.forClass(PieceIndexableEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        PieceRechercheDocument document = captor.getValue().document();
        assertThat(document.statut()).isEqualTo(StatutPiece.RETIREE.name());
        assertThat(document.id()).isEqualTo(piece.getId());
    }

    @Test
    void signaler_avecStatutDisponible_shouldPasserStatutCibleEtRenseignerLesChampsDeSignalement() {
        Poste poste = poste();
        Agent appelant = connecterCommeAppelant(poste);
        Piece piece = pieceExistante(poste, appelant);
        when(pieceRepository.findById(piece.getId())).thenReturn(Optional.of(piece));
        SignalerRequest request = new SignalerRequest(StatutPiece.LITIGE, "Deux reclamants pour la meme piece");

        pieceService.signaler(piece.getId(), request);

        assertThat(piece.getStatut()).isEqualTo(StatutPiece.LITIGE);
        assertThat(piece.getSignalePar()).isEqualTo(appelant);
        assertThat(piece.getMotifSignalement()).isEqualTo("Deux reclamants pour la meme piece");
    }

    @Test
    void signaler_survienneSurPieceDejaRetiree_shouldLeverTransitionStatutInterdite() {
        Poste poste = poste();
        Agent appelant = connecterCommeAppelant(poste);
        Piece piece = pieceExistante(poste, appelant);
        ReflectionTestUtils.setField(piece, "statut", StatutPiece.RETIREE);
        when(pieceRepository.findById(piece.getId())).thenReturn(Optional.of(piece));
        SignalerRequest request = new SignalerRequest(StatutPiece.LITIGE, "motif");

        assertThatThrownBy(() -> pieceService.signaler(piece.getId(), request))
                .isInstanceOf(TransitionStatutInterditeException.class);
    }

    @Test
    void signaler_commeAgentDunAutrePoste_shouldLeverAccesRefuseException() {
        Poste posteAppelant = poste();
        Agent appelant = connecterCommeAppelant(posteAppelant);
        Piece piece = pieceExistante(autrePoste(), appelant);
        when(pieceRepository.findById(piece.getId())).thenReturn(Optional.of(piece));
        SignalerRequest request = new SignalerRequest(StatutPiece.SIGNALEE, "motif");

        assertThatThrownBy(() -> pieceService.signaler(piece.getId(), request))
                .isInstanceOf(AccesRefuseException.class);
    }

    @Test
    void debloquer_avecStatutRetiree_shouldPasserDisponibleEtRenseignerLesChampsDeDeblocage() {
        Poste poste = poste();
        Agent appelant = new Agent(poste, "PN-2024-00999", "Sarr Ibra", Role.CHEF_POSTE, "$2a$10$hashopaque");
        when(agentRepository.findByMatricule("PN-2024-00999")).thenReturn(Optional.of(appelant));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("PN-2024-00999", null, List.of()));
        Piece piece = pieceExistante(poste, appelant);
        ReflectionTestUtils.setField(piece, "statut", StatutPiece.RETIREE);
        when(pieceRepository.findById(piece.getId())).thenReturn(Optional.of(piece));
        DeblocageRequest request = new DeblocageRequest("Erreur d'identification du reclamant");

        pieceService.debloquer(piece.getId(), request);

        assertThat(piece.getStatut()).isEqualTo(StatutPiece.DISPONIBLE);
        assertThat(piece.getDebloquePar()).isEqualTo(appelant);
        assertThat(piece.getMotifDeblocage()).isEqualTo("Erreur d'identification du reclamant");
    }

    @Test
    void debloquer_avecStatutDisponible_shouldLeverTransitionStatutInterdite() {
        Poste poste = poste();
        Agent appelant = new Agent(poste, "PN-2024-00999", "Sarr Ibra", Role.CHEF_POSTE, "$2a$10$hashopaque");
        when(agentRepository.findByMatricule("PN-2024-00999")).thenReturn(Optional.of(appelant));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("PN-2024-00999", null, List.of()));
        Piece piece = pieceExistante(poste, appelant);
        when(pieceRepository.findById(piece.getId())).thenReturn(Optional.of(piece));
        DeblocageRequest request = new DeblocageRequest("motif");

        assertThatThrownBy(() -> pieceService.debloquer(piece.getId(), request))
                .isInstanceOf(TransitionStatutInterditeException.class);
    }

    @Test
    void genererRecu_commeAgentDuMemePosteNonCreateur_shouldRetournerPdfNonVideAvecNumeroFiche() {
        Poste poste = poste();
        Agent agentCreateur = new Agent(poste, "PN-2024-00001", "Ndoye Fatou", Role.AGENT, "$2a$10$hashopaque");
        Piece piece = pieceExistante(poste, agentCreateur);
        connecterCommeAppelant(poste);
        when(pieceRepository.findById(piece.getId())).thenReturn(Optional.of(piece));
        byte[] pdfSimule = {1, 2, 3};
        when(pieceRecuPdfGenerator.genererPdf(piece)).thenReturn(pdfSimule);

        PieceService.RecuPdf recu = pieceService.genererRecu(piece.getId());

        assertThat(recu.contenu()).isEqualTo(pdfSimule);
        assertThat(recu.numeroFiche()).isEqualTo(piece.getNumeroFiche());
    }

    @Test
    void genererRecu_commeAgentDunAutrePoste_shouldLeverAccesRefuseException() {
        Poste posteAppelant = poste();
        Agent appelant = connecterCommeAppelant(posteAppelant);
        Piece piece = pieceExistante(autrePoste(), appelant);
        when(pieceRepository.findById(piece.getId())).thenReturn(Optional.of(piece));

        assertThatThrownBy(() -> pieceService.genererRecu(piece.getId()))
                .isInstanceOf(AccesRefuseException.class);
    }

    @Test
    void genererRecu_avecPieceInexistante_shouldLeverPieceIntrouvableException() {
        Poste poste = poste();
        connecterCommeAppelant(poste);
        UUID idInexistant = UUID.randomUUID();
        when(pieceRepository.findById(idInexistant)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pieceService.genererRecu(idInexistant))
                .isInstanceOf(PieceIntrouvableException.class);
    }
}
