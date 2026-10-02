package sn.samapiece.alertes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import sn.samapiece.enregistrement.Piece;
import sn.samapiece.enregistrement.PieceRepository;
import sn.samapiece.enregistrement.TypeDocument;
import sn.samapiece.iam.Agent;
import sn.samapiece.iam.Role;
import sn.samapiece.notifications.EnvoiSmsException;
import sn.samapiece.notifications.NumeroTelephone;
import sn.samapiece.notifications.PasserelleSms;
import sn.samapiece.referentiel.Poste;
import sn.samapiece.referentiel.Region;
import sn.samapiece.referentiel.TypePoste;

class AlerteCorrespondanceWorkerTest {

    private final NotificationCorrespondanceRepository notificationRepository =
            mock(NotificationCorrespondanceRepository.class);
    private final AlerteRepository alerteRepository = mock(AlerteRepository.class);
    private final PieceRepository pieceRepository = mock(PieceRepository.class);
    private final AlerteContactChiffrementService chiffrementService = mock(AlerteContactChiffrementService.class);
    private final PasserelleSms passerelleSms = mock(PasserelleSms.class);
    private final AlerteCorrespondanceProperties proprietes = new AlerteCorrespondanceProperties();

    private final AlerteCorrespondanceWorker worker;

    AlerteCorrespondanceWorkerTest() {
        proprietes.setMaxTentatives(3);
        proprietes.setRetryDelai30sMs(30_000);
        proprietes.setRetryDelai2mMs(120_000);
        proprietes.setRetryDelai10mMs(600_000);
        worker = new AlerteCorrespondanceWorker(
                notificationRepository,
                alerteRepository,
                pieceRepository,
                chiffrementService,
                passerelleSms,
                proprietes,
                mock(PlatformTransactionManager.class));
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
        return new Piece(
                "PC-3F2A9C1B-2026-00001", poste, agent, TypeDocument.CNI, "Fall", "Moussa",
                "hash", "sel", "masque", LocalDate.of(1990, 5, 12), LocalDate.now(), null, null);
    }

    private NotificationCorrespondance notification(UUID alerteId, UUID pieceId, int tentatives) {
        NotificationCorrespondance notification =
                new NotificationCorrespondance(alerteId, pieceId, OffsetDateTime.now());
        ReflectionTestUtils.setField(notification, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(notification, "nombreTentatives", tentatives);
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));
        return notification;
    }

    @Test
    void succes_devraitEnvoyerLeSmsEtMarquerEnvoyee() {
        Alerte alerte = alerteActive();
        NotificationCorrespondance notification = notification(alerte.getId(), UUID.randomUUID(), 0);
        when(alerteRepository.findById(alerte.getId())).thenReturn(Optional.of(alerte));
        when(pieceRepository.findById(notification.getPieceId())).thenReturn(Optional.of(piece()));
        when(chiffrementService.dechiffrer(alerte.getContactChiffre(), alerte.getContactIv()))
                .thenReturn("+221771234567".getBytes());

        worker.traiter(notification.getId());

        verify(passerelleSms).envoyer(eq(NumeroTelephone.de("+221771234567")), any(String.class));
        assertThat(notification.getStatut()).isEqualTo(NotificationCorrespondance.Statut.ENVOYEE);
    }

    @Test
    void alerteInactive_neDoitPasEnvoyerEtDoitAnnuler() {
        Alerte alerte = alerteInactive();
        NotificationCorrespondance notification = notification(alerte.getId(), UUID.randomUUID(), 0);
        when(alerteRepository.findById(alerte.getId())).thenReturn(Optional.of(alerte));

        worker.traiter(notification.getId());

        verifyNoInteractions(passerelleSms);
        assertThat(notification.getStatut()).isEqualTo(NotificationCorrespondance.Statut.ANNULEE);
    }

    @Test
    void notificationDejaTraitee_neDoitRienFaire() {
        NotificationCorrespondance notification = notification(UUID.randomUUID(), UUID.randomUUID(), 0);
        notification.marquerEnvoyee();

        worker.traiter(notification.getId());

        verifyNoInteractions(alerteRepository, passerelleSms);
    }

    @Test
    void alerteIntrouvable_devraitReprogrammerAvecLeDelaiDe30s() {
        NotificationCorrespondance notification = notification(UUID.randomUUID(), UUID.randomUUID(), 0);
        when(alerteRepository.findById(notification.getAlerteId())).thenReturn(Optional.empty());

        worker.traiter(notification.getId());

        assertReprogrammee(notification, 1, Duration.ofSeconds(30));
        verifyNoInteractions(passerelleSms);
    }

    @Test
    void pieceIntrouvable_devraitReprogrammerAvecLeDelaiDe30s() {
        Alerte alerte = alerteActive();
        NotificationCorrespondance notification = notification(alerte.getId(), UUID.randomUUID(), 0);
        when(alerteRepository.findById(alerte.getId())).thenReturn(Optional.of(alerte));
        when(pieceRepository.findById(notification.getPieceId())).thenReturn(Optional.empty());

        worker.traiter(notification.getId());

        assertReprogrammee(notification, 1, Duration.ofSeconds(30));
        verifyNoInteractions(passerelleSms);
    }

    @Test
    void dechiffrementEnEchec_devraitReprogrammerAvecLeDelaiDe2m() {
        Alerte alerte = alerteActive();
        NotificationCorrespondance notification = notification(alerte.getId(), UUID.randomUUID(), 1);
        when(alerteRepository.findById(alerte.getId())).thenReturn(Optional.of(alerte));
        when(pieceRepository.findById(notification.getPieceId())).thenReturn(Optional.of(piece()));
        when(chiffrementService.dechiffrer(alerte.getContactChiffre(), alerte.getContactIv()))
                .thenThrow(new IllegalStateException("Echec du dechiffrement du contact."));

        worker.traiter(notification.getId());

        assertReprogrammee(notification, 2, Duration.ofMinutes(2));
        verifyNoInteractions(passerelleSms);
    }

    @Test
    void passerelleSmsEnEchec_devraitReprogrammerAvecLeDelaiDe10m() {
        Alerte alerte = alerteActive();
        NotificationCorrespondance notification = notification(alerte.getId(), UUID.randomUUID(), 2);
        when(alerteRepository.findById(alerte.getId())).thenReturn(Optional.of(alerte));
        when(pieceRepository.findById(notification.getPieceId())).thenReturn(Optional.of(piece()));
        when(chiffrementService.dechiffrer(alerte.getContactChiffre(), alerte.getContactIv()))
                .thenReturn("+221771234567".getBytes());
        doThrow(new EnvoiSmsException(NumeroTelephone.de("+221771234567"), new RuntimeException("503")))
                .when(passerelleSms).envoyer(any(), any());

        worker.traiter(notification.getId());

        assertReprogrammee(notification, 3, Duration.ofMinutes(10));
    }

    @Test
    void maxTentativesAtteint_devraitPasserEnEchec() {
        NotificationCorrespondance notification =
                notification(UUID.randomUUID(), UUID.randomUUID(), proprietes.getMaxTentatives());
        when(alerteRepository.findById(notification.getAlerteId())).thenReturn(Optional.empty());

        worker.traiter(notification.getId());

        assertThat(notification.getStatut()).isEqualTo(NotificationCorrespondance.Statut.ECHEC);
        assertThat(notification.getNombreTentatives()).isEqualTo(proprietes.getMaxTentatives() + 1);
        verifyNoInteractions(passerelleSms);
    }

    private void assertReprogrammee(NotificationCorrespondance notification, int tentatives, Duration delai) {
        assertThat(notification.getStatut()).isEqualTo(NotificationCorrespondance.Statut.EN_ATTENTE);
        assertThat(notification.getNombreTentatives()).isEqualTo(tentatives);
        assertThat(Duration.between(OffsetDateTime.now(), notification.getProchaineTentative()))
                .isBetween(delai.minusSeconds(5), delai);
    }
}
