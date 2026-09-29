package sn.samapiece.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import sn.samapiece.audit.web.EvenementAuditResponse;

class EvenementAuditServiceTest {

    private final EvenementAuditRepository evenementAuditRepository = mock(EvenementAuditRepository.class);

    private final EvenementAuditService evenementAuditService =
            new EvenementAuditService(evenementAuditRepository);

    @Test
    void enregistrer_shouldSauvegarderEvenementAuditAvecLesChampsFournis() {
        UUID acteurId = UUID.randomUUID();
        UUID entiteCibleId = UUID.randomUUID();

        evenementAuditService.enregistrer(
                acteurId, "AGENT", "PIECE_CONSULTEE", "PIECE", entiteCibleId, "{\"resultat\":\"SUCCES\"}", "127.0.0.1");

        ArgumentCaptor<EvenementAudit> captor = ArgumentCaptor.forClass(EvenementAudit.class);
        verify(evenementAuditRepository).save(captor.capture());
        EvenementAudit evenementAudit = captor.getValue();
        assertThat(evenementAudit.getActeurId()).isEqualTo(acteurId);
        assertThat(evenementAudit.getTypeActeur()).isEqualTo("AGENT");
        assertThat(evenementAudit.getAction()).isEqualTo("PIECE_CONSULTEE");
        assertThat(evenementAudit.getEntiteCible()).isEqualTo("PIECE");
        assertThat(evenementAudit.getEntiteCibleId()).isEqualTo(entiteCibleId);
        assertThat(evenementAudit.getDetails()).isEqualTo("{\"resultat\":\"SUCCES\"}");
        assertThat(evenementAudit.getAdresseIp()).isEqualTo("127.0.0.1");
    }

    @Test
    void lister_shouldMapperPageEvenementAuditVersPageEvenementAuditResponse() {
        EvenementAudit evenementAudit = new EvenementAudit(
                UUID.randomUUID(), "AGENT", "PIECE_CREEE", "PIECE", UUID.randomUUID(),
                "{\"resultat\":\"SUCCES\"}", "127.0.0.1");
        ReflectionTestUtils.setField(evenementAudit, "id", UUID.randomUUID());
        Pageable pageable = PageRequest.of(0, 20);
        Page<EvenementAudit> page = new PageImpl<>(java.util.List.of(evenementAudit), pageable, 1);
        when(evenementAuditRepository.findAll(pageable)).thenReturn(page);

        Page<EvenementAuditResponse> reponse = evenementAuditService.lister(null, null, pageable);

        assertThat(reponse.getTotalElements()).isEqualTo(1);
        assertThat(reponse.getContent().get(0).action()).isEqualTo("PIECE_CREEE");
        assertThat(reponse.getContent().get(0).id()).isEqualTo(evenementAudit.getId());
    }

    @Test
    void lister_actionSeule_shouldUtiliserFindByAction() {
        Pageable pageable = PageRequest.of(0, 20);
        when(evenementAuditRepository.findByAction("PIECE_CREEE", pageable)).thenReturn(Page.empty(pageable));

        evenementAuditService.lister(" PIECE_CREEE ", null, pageable);

        verify(evenementAuditRepository).findByAction("PIECE_CREEE", pageable);
        verifyNoMoreInteractions(evenementAuditRepository);
    }

    @Test
    void lister_entiteSeule_shouldUtiliserFindByEntiteCible() {
        Pageable pageable = PageRequest.of(0, 20);
        when(evenementAuditRepository.findByEntiteCible("PIECE", pageable)).thenReturn(Page.empty(pageable));

        evenementAuditService.lister(null, "PIECE", pageable);

        verify(evenementAuditRepository).findByEntiteCible("PIECE", pageable);
        verifyNoMoreInteractions(evenementAuditRepository);
    }

    @Test
    void lister_actionEtEntite_shouldUtiliserFindByActionAndEntiteCible() {
        Pageable pageable = PageRequest.of(0, 20);
        when(evenementAuditRepository.findByActionAndEntiteCible("PIECE_CREEE", "PIECE", pageable))
                .thenReturn(Page.empty(pageable));

        evenementAuditService.lister("PIECE_CREEE", "PIECE", pageable);

        verify(evenementAuditRepository).findByActionAndEntiteCible("PIECE_CREEE", "PIECE", pageable);
        verifyNoMoreInteractions(evenementAuditRepository);
    }

    @Test
    void lister_valeursBlanches_shouldEtreTraiteesCommeAbsentes() {
        Pageable pageable = PageRequest.of(0, 20);
        when(evenementAuditRepository.findAll(pageable)).thenReturn(Page.empty(pageable));

        evenementAuditService.lister("  ", "", pageable);

        verify(evenementAuditRepository).findAll(pageable);
        verifyNoMoreInteractions(evenementAuditRepository);
    }
}
