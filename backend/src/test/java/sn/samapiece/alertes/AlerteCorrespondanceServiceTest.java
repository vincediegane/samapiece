package sn.samapiece.alertes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import sn.samapiece.enregistrement.NumeroDocumentHasher;
import sn.samapiece.enregistrement.PieceDisponibleEvent;
import sn.samapiece.enregistrement.TypeDocument;

class AlerteCorrespondanceServiceTest {

    private final AlerteRepository alerteRepository = mock(AlerteRepository.class);
    private final NumeroDocumentHasher numeroDocumentHasher = mock(NumeroDocumentHasher.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);

    private final AlerteCorrespondanceService service =
            new AlerteCorrespondanceService(alerteRepository, numeroDocumentHasher, rabbitTemplate);

    private PieceDisponibleEvent evenement() {
        return new PieceDisponibleEvent(
                UUID.randomUUID(),
                TypeDocument.CNI,
                "Fall",
                "Moussa",
                "1234567890123",
                LocalDate.of(1990, 5, 12),
                "PC-3F2A9C1B-2026-00001",
                "Commissariat Central Dakar");
    }

    private Alerte alerteAvec(String prenom, LocalDate dateNaissance, String hash, String sel) {
        Alerte alerte = new Alerte(
                TypeDocument.CNI, "Fall", prenom, hash, sel, hash == null ? null : "masque",
                dateNaissance, "sms", "chiffre".getBytes(), "iv");
        ReflectionTestUtils.setField(alerte, "id", UUID.randomUUID());
        return alerte;
    }

    @Test
    void trouverEtNotifier_avecAlerteCorrespondante_devraitPublierUnMessage() {
        Alerte alerte = alerteAvec("Moussa", null, null, null);
        PieceDisponibleEvent evenement = evenement();
        when(alerteRepository.findByActiveTrueAndTypeDocumentAndNomTitulaireIgnoreCase(
                evenement.typeDocument(), evenement.nomTitulaire()))
                .thenReturn(List.of(alerte));

        service.trouverEtNotifier(evenement);

        verify(rabbitTemplate).convertAndSend(
                AlerteCorrespondanceRabbitConfig.EXCHANGE,
                AlerteCorrespondanceRabbitConfig.QUEUE_CONSUME,
                new AlerteCorrespondanceMessage(alerte.getId(), evenement.pieceId(), 0));
    }

    @Test
    void trouverEtNotifier_sansAlerteActive_neDevraitRienPublier() {
        PieceDisponibleEvent evenement = evenement();
        when(alerteRepository.findByActiveTrueAndTypeDocumentAndNomTitulaireIgnoreCase(
                evenement.typeDocument(), evenement.nomTitulaire()))
                .thenReturn(List.of());

        service.trouverEtNotifier(evenement);

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void trouverEtNotifier_avecNumeroDocumentNonCorrespondant_neDevraitPasPublierPourCetteAlerte() {
        Alerte alerte = alerteAvec("Moussa", null, "hash-different", "sel");
        PieceDisponibleEvent evenement = evenement();
        when(numeroDocumentHasher.verifier(evenement.numeroDocumentClair(), "sel", "hash-different"))
                .thenReturn(false);
        when(alerteRepository.findByActiveTrueAndTypeDocumentAndNomTitulaireIgnoreCase(
                evenement.typeDocument(), evenement.nomTitulaire()))
                .thenReturn(List.of(alerte));

        service.trouverEtNotifier(evenement);

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void trouverEtNotifier_sansDiscriminantNumeroDocumentMaisDateNaissanceCorrespondante_devraitPublier() {
        Alerte alerte = alerteAvec("Moussa", LocalDate.of(1990, 5, 12), null, null);
        PieceDisponibleEvent evenement = evenement();
        when(alerteRepository.findByActiveTrueAndTypeDocumentAndNomTitulaireIgnoreCase(
                evenement.typeDocument(), evenement.nomTitulaire()))
                .thenReturn(List.of(alerte));

        service.trouverEtNotifier(evenement);

        verify(rabbitTemplate).convertAndSend(
                eq(AlerteCorrespondanceRabbitConfig.EXCHANGE),
                eq(AlerteCorrespondanceRabbitConfig.QUEUE_CONSUME),
                eq(new AlerteCorrespondanceMessage(alerte.getId(), evenement.pieceId(), 0)));
    }

    @Test
    void trouverEtNotifier_avecPrenomNonCorrespondant_neDevraitPasPublier() {
        Alerte alerte = alerteAvec("Ibrahima", null, null, null);
        PieceDisponibleEvent evenement = evenement();
        when(alerteRepository.findByActiveTrueAndTypeDocumentAndNomTitulaireIgnoreCase(
                evenement.typeDocument(), evenement.nomTitulaire()))
                .thenReturn(List.of(alerte));

        service.trouverEtNotifier(evenement);

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void trouverEtNotifier_plusieursAlertesCorrespondantes_devraitPublierUnMessageParAlerte() {
        Alerte alerte1 = alerteAvec("Moussa", null, null, null);
        Alerte alerte2 = alerteAvec(null, null, null, null);
        PieceDisponibleEvent evenement = evenement();
        when(alerteRepository.findByActiveTrueAndTypeDocumentAndNomTitulaireIgnoreCase(
                evenement.typeDocument(), evenement.nomTitulaire()))
                .thenReturn(List.of(alerte1, alerte2));

        service.trouverEtNotifier(evenement);

        ArgumentCaptor<AlerteCorrespondanceMessage> captor = ArgumentCaptor.forClass(AlerteCorrespondanceMessage.class);
        verify(rabbitTemplate, times(2)).convertAndSend(
                eq(AlerteCorrespondanceRabbitConfig.EXCHANGE),
                eq(AlerteCorrespondanceRabbitConfig.QUEUE_CONSUME),
                captor.capture());
        assertThat(captor.getAllValues())
                .extracting(AlerteCorrespondanceMessage::alerteId)
                .containsExactlyInAnyOrder(alerte1.getId(), alerte2.getId());
        assertThat(captor.getAllValues())
                .allSatisfy(message -> {
                    assertThat(message.pieceId()).isEqualTo(evenement.pieceId());
                    assertThat(message.nombreTentatives()).isZero();
                });
    }
}
