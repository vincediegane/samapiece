package sn.samapiece.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import sn.samapiece.iam.Agent;
import sn.samapiece.iam.AgentRepository;

/**
 * Ecrit l'evenement d'audit apres la fin (commit ou rollback) de la transaction metier de la
 * methode de controleur annotee -- voir spec #25 pour la justification de l'absence de
 * mecanisme d'evenement @TransactionalEventListener ici. Un echec d'ecriture d'audit (ex.
 * contrainte DB) est logue en ERROR et avale : il ne doit jamais faire echouer ni masquer
 * l'action metier interceptee -- la garantie d'exhaustivite de evenement_audit n'est donc pas
 * transactionnellement stricte (compromis assume, voir spec).
 */
@Aspect
@Component
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);
    private static final String TYPE_ACTEUR_AGENT = "AGENT";
    private static final String RESULTAT_SUCCES = "SUCCES";
    private static final String RESULTAT_ECHEC = "ECHEC";

    private final EvenementAuditService evenementAuditService;
    private final AgentRepository agentRepository;
    private final ObjectMapper objectMapper;

    public AuditAspect(
            EvenementAuditService evenementAuditService,
            AgentRepository agentRepository,
            ObjectMapper objectMapper) {
        this.evenementAuditService = evenementAuditService;
        this.agentRepository = agentRepository;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(actionAuditee)")
    public Object autourAction(ProceedingJoinPoint joinPoint, ActionAuditee actionAuditee) throws Throwable {
        UUID entiteCibleId = extraireIdDepuisPathVariable(joinPoint);
        try {
            Object retour = joinPoint.proceed();
            UUID idFinal = entiteCibleId != null ? entiteCibleId : extraireIdDepuisReponse(retour);
            auditerSansPropagerErreur(actionAuditee, idFinal, RESULTAT_SUCCES, null);
            return retour;
        } catch (Throwable ex) {
            auditerSansPropagerErreur(actionAuditee, entiteCibleId, RESULTAT_ECHEC, ex);
            throw ex;
        }
    }

    private UUID extraireIdDepuisPathVariable(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Parameter[] parametres = signature.getMethod().getParameters();
        Object[] arguments = joinPoint.getArgs();
        for (int i = 0; i < parametres.length; i++) {
            if (parametres[i].isAnnotationPresent(PathVariable.class) && parametres[i].getType().equals(UUID.class)) {
                return (UUID) arguments[i];
            }
        }
        return null;
    }

    private UUID extraireIdDepuisReponse(Object retour) {
        try {
            if (retour instanceof ResponseEntity<?> reponse && reponse.getBody() != null) {
                Object corps = reponse.getBody();
                Method accesseurId = corps.getClass().getMethod("id");
                if (accesseurId.getReturnType().equals(UUID.class)) {
                    return (UUID) accesseurId.invoke(corps);
                }
            }
        } catch (Exception ex) {
            log.debug("Impossible d'extraire entiteCibleId depuis la reponse de l'action auditee.", ex);
        }
        return null;
    }

    private void auditerSansPropagerErreur(
            ActionAuditee actionAuditee, UUID entiteCibleId, String resultat, Throwable exception) {
        String matricule = null;
        try {
            UUID acteurId = null;
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null) {
                matricule = authentication.getName();
                acteurId = agentRepository.findByMatricule(matricule).map(Agent::getId).orElse(null);
            }
            String adresseIp = resoudreAdresseIp();
            String details = construireDetails(resultat, exception);
            evenementAuditService.enregistrer(
                    acteurId,
                    TYPE_ACTEUR_AGENT,
                    actionAuditee.action(),
                    actionAuditee.entiteCible(),
                    entiteCibleId,
                    details,
                    adresseIp);
        } catch (Exception ex) {
            log.error(
                    "Echec de l'ecriture de l'evenement d'audit action={} resultat={} agent={}",
                    actionAuditee.action(), resultat, matricule, ex);
        }
    }

    private String resoudreAdresseIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servletRequestAttributes) {
            return servletRequestAttributes.getRequest().getRemoteAddr();
        }
        return null;
    }

    private String construireDetails(String resultat, Throwable exception) throws JsonProcessingException {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("resultat", resultat);
        if (exception != null) {
            details.put("exception", exception.getClass().getSimpleName());
            details.put("message", exception.getMessage());
        }
        return objectMapper.writeValueAsString(details);
    }
}
