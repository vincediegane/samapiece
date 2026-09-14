package sn.samapiece.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.context.request.RequestContextHolder;
import sn.samapiece.iam.AgentRepository;

class AuditAspectTest {

    private final EvenementAuditService evenementAuditService = mock(EvenementAuditService.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AuditAspect auditAspect = new AuditAspect(evenementAuditService, agentRepository, objectMapper);

    private static final class Fixture {
        @ActionAuditee(action = "PIECE_CONSULTEE", entiteCible = "PIECE")
        void avecPathVariable(@PathVariable UUID id) {
        }

        @ActionAuditee(action = "PIECE_CONSULTEE", entiteCible = "PIECE")
        void sansPathVariable(String quelqueChose) {
        }
    }

    record FausseReponseAvecId(UUID id) {
    }

    @AfterEach
    void nettoyerContexte() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private ProceedingJoinPoint mockJoinPoint(Method method, Object[] args) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(args);
        return joinPoint;
    }

    @Test
    void extraitIdDepuisPathVariable() throws Throwable {
        Method method = Fixture.class.getDeclaredMethod("avecPathVariable", UUID.class);
        UUID id = UUID.randomUUID();
        ProceedingJoinPoint joinPoint = mockJoinPoint(method, new Object[] {id});
        when(joinPoint.proceed()).thenReturn(ResponseEntity.ok().build());

        auditAspect.autourAction(joinPoint, method.getAnnotation(ActionAuditee.class));

        ArgumentCaptor<UUID> entiteCibleIdCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(evenementAuditService).enregistrer(
                any(), any(), any(), any(), entiteCibleIdCaptor.capture(), any(), any());
        assertThat(entiteCibleIdCaptor.getValue()).isEqualTo(id);
    }

    @Test
    void extraitIdDepuisReponseQuandAucunPathVariable() throws Throwable {
        Method method = Fixture.class.getDeclaredMethod("sansPathVariable", String.class);
        ProceedingJoinPoint joinPoint = mockJoinPoint(method, new Object[] {"valeur"});
        UUID id = UUID.randomUUID();
        when(joinPoint.proceed()).thenReturn(ResponseEntity.ok(new FausseReponseAvecId(id)));

        auditAspect.autourAction(joinPoint, method.getAnnotation(ActionAuditee.class));

        ArgumentCaptor<UUID> entiteCibleIdCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(evenementAuditService).enregistrer(
                any(), any(), any(), any(), entiteCibleIdCaptor.capture(), any(), any());
        assertThat(entiteCibleIdCaptor.getValue()).isEqualTo(id);
    }

    @Test
    void quandEnregistrementAuditEchoue_succesResteRetourne() throws Throwable {
        Method method = Fixture.class.getDeclaredMethod("sansPathVariable", String.class);
        ProceedingJoinPoint joinPoint = mockJoinPoint(method, new Object[] {"valeur"});
        ResponseEntity<String> retourMetier = ResponseEntity.ok("resultat-metier");
        when(joinPoint.proceed()).thenReturn(retourMetier);
        doThrow(new RuntimeException("echec ecriture audit"))
                .when(evenementAuditService)
                .enregistrer(any(), any(), any(), any(), any(), any(), any());

        Object retour = auditAspect.autourAction(joinPoint, method.getAnnotation(ActionAuditee.class));

        assertThat(retour).isSameAs(retourMetier);
    }

    @Test
    void quandEnregistrementAuditEchoue_exceptionMetierResteInchangee() throws Throwable {
        Method method = Fixture.class.getDeclaredMethod("sansPathVariable", String.class);
        ProceedingJoinPoint joinPoint = mockJoinPoint(method, new Object[] {"valeur"});
        IllegalStateException exceptionMetier = new IllegalStateException("acces refuse");
        when(joinPoint.proceed()).thenThrow(exceptionMetier);
        doThrow(new RuntimeException("echec ecriture audit"))
                .when(evenementAuditService)
                .enregistrer(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> auditAspect.autourAction(joinPoint, method.getAnnotation(ActionAuditee.class)))
                .isSameAs(exceptionMetier);
    }
}
