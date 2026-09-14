package sn.samapiece.alertes;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import sn.samapiece.enregistrement.Piece;
import sn.samapiece.enregistrement.PieceRepository;
import sn.samapiece.enregistrement.TypeDocument;
import sn.samapiece.iam.Agent;
import sn.samapiece.iam.Role;
import sn.samapiece.notifications.NumeroTelephone;
import sn.samapiece.notifications.PasserelleSms;
import sn.samapiece.referentiel.Poste;
import sn.samapiece.referentiel.Region;
import sn.samapiece.referentiel.TypePoste;

class AlerteCorrespondanceRetryListenerTest {

    private final AlerteRepository alerteRepository = mock(AlerteRepository.class);
    private final PieceRepository pieceRepository = mock(PieceRepository.class);
    private final AlerteContactChiffrementService chiffrementService = mock(AlerteContactChiffrementService.class);
    private final PasserelleSms passerelleSms = mock(PasserelleSms.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final AlerteCorrespondanceProperties proprietes = new AlerteCorrespondanceProperties();

    private final AlerteCorrespondanceRetryListener listener = new AlerteCorrespondanceRetryListener(
            alerteRepository, pieceRepository, chiffrementService, passerelleSms, rabbitTemplate, proprietes);

    AlerteCorrespondanceRetryListenerTest() {
        proprietes.setMaxTentatives(3);
        proprietes.setRetryTtl30sMs(30000);
        proprietes.setRetryTtl2mMs(120000);
        proprietes.setRetryTtl10mMs(600000);
    }

    private Alerte alerteActive() {
        Alerte alerte = new Alerte(
                TypeDocument.CNI, "Fall", "Moussa", null, null, null,
                null, "sms", "chiffre".getBytes(), "iv");
        ReflectionTestUtils.setField(alerte, "id", UUID.randomUUID());
        return alerte;
    }

    private Alerte alerteInactive() {
        Alerte alerte = alerteActive();
        alerte.desinscrire();
        return alerte;
    }

    private Piece piece() {
        Region region = new Region("Dakar");
        Poste poste = new Poste(
                region, "Commissariat Central Dakar", TypePoste.POLICE,
                "Place de l'Indépendance, Dakar", null, "{}", null, null);
        Agent agent = new Agent(poste, "PN-2024-00123", "Diop Awa", Role.AGENT, "$2a$10$hashopaque");
        Piece piece = new Piece(
                "PC-3F2A9C1B-2026-00001", poste, agent, TypeDocument.CNI, "Fall", "Moussa",
                "hash", "sel", "masque", LocalDate.of(1990, 5, 12), LocalDate.now(), null, null);
        return piece;
    }

    @Test
    void succes_neDoitPasRepublier() {
        Alerte alerte = alerteActive();
        Piece piece = piece();
        AlerteCorrespondanceMessage message = new AlerteCorrespondanceMessage(alerte.getId(), UUID.randomUUID(), 0);
        when(alerteRepository.findById(alerte.getId())).thenReturn(Optional.of(alerte));
        when(pieceRepository.findById(message.pieceId())).thenReturn(Optional.of(piece));
        when(chiffrementService.dechiffrer(alerte.getContactChiffre(), alerte.getContactIv()))
                .thenReturn("+221771234567".getBytes());

        listener.consommer(message);

        verify(passerelleSms).envoyer(eq(NumeroTelephone.de("+221771234567")), any(String.class));
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void alerteInactiveAuMomentDeLaConsommation_neDoitPasEnvoyerNiRepublier() {
        Alerte alerte = alerteInactive();
        AlerteCorrespondanceMessage message = new AlerteCorrespondanceMessage(alerte.getId(), UUID.randomUUID(), 0);
        when(alerteRepository.findById(alerte.getId())).thenReturn(Optional.of(alerte));

        listener.consommer(message);

        verifyNoInteractions(passerelleSms);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void alerteIntrouvable_devraitRepublierVersEtageSuivant() {
        UUID alerteId = UUID.randomUUID();
        AlerteCorrespondanceMessage message = new AlerteCorrespondanceMessage(alerteId, UUID.randomUUID(), 0);
        when(alerteRepository.findById(alerteId)).thenReturn(Optional.empty());

        listener.consommer(message);

        verify(rabbitTemplate).convertAndSend(
                eq(AlerteCorrespondanceRabbitConfig.EXCHANGE),
                eq(AlerteCorrespondanceRabbitConfig.QUEUE_RETRY_30S),
                eq(new AlerteCorrespondanceMessage(alerteId, message.pieceId(), 1)));
        verifyNoInteractions(passerelleSms);
    }

    @Test
    void pieceIntrouvable_devraitRepublierVersEtageSuivant() {
        Alerte alerte = alerteActive();
        UUID pieceId = UUID.randomUUID();
        AlerteCorrespondanceMessage message = new AlerteCorrespondanceMessage(alerte.getId(), pieceId, 0);
        when(alerteRepository.findById(alerte.getId())).thenReturn(Optional.of(alerte));
        when(pieceRepository.findById(pieceId)).thenReturn(Optional.empty());

        listener.consommer(message);

        verify(rabbitTemplate).convertAndSend(
                eq(AlerteCorrespondanceRabbitConfig.EXCHANGE),
                eq(AlerteCorrespondanceRabbitConfig.QUEUE_RETRY_30S),
                eq(new AlerteCorrespondanceMessage(alerte.getId(), pieceId, 1)));
        verifyNoInteractions(passerelleSms);
    }

    @Test
    void dechiffrementEnEchec_devraitRepublierVersEtageSuivant() {
        Alerte alerte = alerteActive();
        Piece piece = piece();
        AlerteCorrespondanceMessage message = new AlerteCorrespondanceMessage(alerte.getId(), UUID.randomUUID(), 1);
        when(alerteRepository.findById(alerte.getId())).thenReturn(Optional.of(alerte));
        when(pieceRepository.findById(message.pieceId())).thenReturn(Optional.of(piece));
        when(chiffrementService.dechiffrer(alerte.getContactChiffre(), alerte.getContactIv()))
                .thenThrow(new IllegalStateException("Echec du dechiffrement du contact."));

        listener.consommer(message);

        verify(rabbitTemplate).convertAndSend(
                eq(AlerteCorrespondanceRabbitConfig.EXCHANGE),
                eq(AlerteCorrespondanceRabbitConfig.QUEUE_RETRY_2M),
                eq(new AlerteCorrespondanceMessage(alerte.getId(), message.pieceId(), 2)));
        verifyNoInteractions(passerelleSms);
    }

    @Test
    void maxTentativesAtteint_devraitPartirEnDeadLetter() {
        UUID alerteId = UUID.randomUUID();
        AlerteCorrespondanceMessage message =
                new AlerteCorrespondanceMessage(alerteId, UUID.randomUUID(), proprietes.getMaxTentatives());
        when(alerteRepository.findById(alerteId)).thenReturn(Optional.empty());

        listener.consommer(message);

        verify(rabbitTemplate).convertAndSend(
                eq(AlerteCorrespondanceRabbitConfig.EXCHANGE),
                eq(AlerteCorrespondanceRabbitConfig.QUEUE_DEAD_LETTER),
                eq(new AlerteCorrespondanceMessage(alerteId, message.pieceId(), proprietes.getMaxTentatives() + 1)));
        verifyNoInteractions(passerelleSms);
    }
}
