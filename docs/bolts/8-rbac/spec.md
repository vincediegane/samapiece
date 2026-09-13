# Spec -- Ticket #8 : RBAC -- roles Agent / Chef de poste / Admin regional / Admin national / Auditeur

## Resume

Ajouter le scoping perimetre (poste/region) et le verrou d'assignation de role dans `AgentAdminService`, sur les 4 endpoints existants de `/api/v1/agents`, sans toucher aux `@PreAuthorize` deja en place sur `AgentAdminController`, et couvrir chaque role par au moins un test d'integration positif et un test 403.

## Matrice de roles (reprise du design, non modifiee)

| Endpoint | AGENT | CHEF_POSTE | ADMIN_REGIONAL | ADMIN_NATIONAL | AUDITEUR |
|---|---|---|---|---|---|
| GET /api/v1/agents | 403 | agents de son poste uniquement | agents des postes de sa region | tous | 403 |
| POST /api/v1/agents | 403 | posteId = son poste ; role assignable = AGENT | posteId dans sa region ; role assignable = AGENT/CHEF_POSTE | tout poste, tout role | 403 |
| PATCH /api/v1/agents/{id} | 403 | agent cible dans son poste ; nouveau posteId (si fourni) reste son poste | agent cible dans sa region ; nouveau posteId (si fourni) reste dans sa region | sans restriction | 403 |
| DELETE /api/v1/agents/{id} | 403 | agent cible dans son poste | agent cible dans sa region | sans restriction | 403 |

Regles d'assignation de role a la creation (`verifierRoleAssignable`) :
- CHEF_POSTE -> peut assigner AGENT uniquement.
- ADMIN_REGIONAL -> peut assigner AGENT ou CHEF_POSTE.
- ADMIN_NATIONAL -> peut assigner n'importe quel role (y compris ADMIN_NATIONAL, ADMIN_REGIONAL, AUDITEUR).

Ordre de verification impose (decision cle #4 du design, a respecter tel quel) :
- POST : `appelantCourant()` -> `verifierRoleAssignable` (403) -> resolution du poste cible (404 si inconnu) -> `verifierPerimetrePoste` (403) -> unicite matricule (409) -> creation.
- PATCH/DELETE : resolution de l'agent cible par id (404 `AGENT_INTROUVABLE` si inconnu) -> `appelantCourant()` -> `verifierPerimetrePoste` sur le poste ACTUEL de l'agent cible (403) -> (PATCH seulement, si `posteId` fourni) resolution du nouveau poste (404 `POSTE_INTROUVABLE` si inconnu) -> `verifierPerimetrePoste` sur le nouveau poste (403) -> mutation.

## Taches

- [ ] `backend/src/main/java/sn/samapiece/iam/AgentRepository.java` -- ajouter les deux methodes derivees `findByPosteId(UUID posteId)` et `findByPosteRegionId(UUID regionId)` (navigation `Agent.poste.region.id`, deja mappee en JPA, aucune migration requise).
- [ ] `backend/src/main/java/sn/samapiece/iam/AccesRefuseException.java` (nouveau) -- `RuntimeException` avec constructeur `(String message)`, meme style que `AgentIntrouvableException`/`PosteIntrouvableException`.
- [ ] `backend/src/main/java/sn/samapiece/iam/web/AgentAdminExceptionHandler.java` -- ajouter `@ExceptionHandler(AccesRefuseException.class)` -> HTTP 403, corps `ErreurReponse("ACCES_REFUSE", "Acces refuse.")` (message fixe, ne pas exposer `ex.getMessage()`, coherent avec le traitement existant de `AgentIntrouvableException`/`PosteIntrouvableException`/`MatriculeDejaUtiliseException`).
- [ ] `backend/src/main/java/sn/samapiece/iam/security/PerimetreRegional.java` (nouveau) -- classe utilitaire statique, methode `estDansPerimetreRegion(Agent appelant, UUID regionId)` (voir contrat technique). Ne depend pas d'`AgentAdminService`, reutilisable telle quelle par le futur ticket #26.
- [ ] `backend/src/main/java/sn/samapiece/iam/security/PerimetreRegionalTest.java` (nouveau, test unitaire pur, sans Spring/Testcontainers) -- couvre les 5 roles sur `estDansPerimetreRegion` (voir plan de tests).
- [ ] `backend/src/main/java/sn/samapiece/iam/AgentAdminService.java` (modifie) -- ajouter les methodes privees `appelantCourant()`, `verifierRoleAssignable(Role, Role)`, `verifierPerimetrePoste(Agent, Poste)` ; modifier `creer()`, `lister()`, `modifier()`, `desactiver()` selon le contrat technique et l'ordre de verification ci-dessus.
- [ ] `backend/src/test/java/sn/samapiece/iam/AgentAdminIntegrationTest.java` (modifie) -- ajouter les cas positifs/403 par role et par endpoint listes dans le plan de tests ; ne pas modifier les tests existants (voir section "Non-regression").

Aucune migration Flyway, aucun changement de `AgentAdminController.java` ni de `SecurityConfig.java`.

## Contrat technique

### `AgentRepository`

```java
public interface AgentRepository extends JpaRepository<Agent, UUID> {
    Optional<Agent> findByMatricule(String matricule);
    List<Agent> findByPosteId(UUID posteId);
    List<Agent> findByPosteRegionId(UUID regionId);
}
```

### `AccesRefuseException`

```java
package sn.samapiece.iam;

public class AccesRefuseException extends RuntimeException {
    public AccesRefuseException(String message) {
        super(message);
    }
}
```

### `AgentAdminExceptionHandler`

```java
@ExceptionHandler(AccesRefuseException.class)
public ResponseEntity<ErreurReponse> gererAccesRefuse(AccesRefuseException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(new ErreurReponse("ACCES_REFUSE", "Acces refuse."));
}
```

### `sn.samapiece.iam.security.PerimetreRegional`

```java
package sn.samapiece.iam.security;

import java.util.UUID;
import sn.samapiece.iam.Agent;

public final class PerimetreRegional {

    private PerimetreRegional() {
    }

    /**
     * Vrai si l'agent appelant peut agir sur la region donnee : ADMIN_NATIONAL toujours,
     * ADMIN_REGIONAL uniquement si sa propre region correspond, faux pour tout autre role
     * (y compris CHEF_POSTE : le perimetre "region" ne le concerne pas). Reutilisable tel
     * quel par le futur ticket #26 (stats regionales).
     */
    public static boolean estDansPerimetreRegion(Agent appelant, UUID regionId) {
        return switch (appelant.getRole()) {
            case ADMIN_NATIONAL -> true;
            case ADMIN_REGIONAL -> appelant.getPoste().getRegion().getId().equals(regionId);
            default -> false;
        };
    }
}
```

Note : `AgentAdminService.verifierPerimetrePoste` (ci-dessous) n'appelle PAS `PerimetreRegional` -- elle a une signature differente (compare a un `Poste`, pas a un `regionId`, et gere aussi le cas CHEF_POSTE). Les deux coexistent volontairement (design decision #5) : `PerimetreRegional` est le contrat reutilisable pour #26, `verifierPerimetrePoste` est specifique aux 4 endpoints agents de ce ticket. Ne pas fusionner.

### `AgentAdminService` -- methodes privees ajoutees

```java
private Agent appelantCourant() {
    String matricule = SecurityContextHolder.getContext().getAuthentication().getName();
    Agent appelant = agentRepository.findByMatricule(matricule)
            .orElseThrow(() -> new AccesRefuseException("Agent appelant introuvable."));
    if (!appelant.isActif()) {
        throw new AccesRefuseException("Agent appelant inactif.");
    }
    return appelant;
}

private void verifierRoleAssignable(Role roleAppelant, Role roleDemande) {
    boolean autorise = switch (roleAppelant) {
        case CHEF_POSTE -> roleDemande == Role.AGENT;
        case ADMIN_REGIONAL -> roleDemande == Role.AGENT || roleDemande == Role.CHEF_POSTE;
        case ADMIN_NATIONAL -> true;
        default -> false;
    };
    if (!autorise) {
        throw new AccesRefuseException("Role non assignable par l'appelant.");
    }
}

private void verifierPerimetrePoste(Agent appelant, Poste posteCible) {
    boolean autorise = switch (appelant.getRole()) {
        case CHEF_POSTE -> appelant.getPoste().getId().equals(posteCible.getId());
        case ADMIN_REGIONAL -> appelant.getPoste().getRegion().getId().equals(posteCible.getRegion().getId());
        case ADMIN_NATIONAL -> true;
        default -> false;
    };
    if (!autorise) {
        throw new AccesRefuseException("Poste hors perimetre de l'appelant.");
    }
}
```

`default -> false` dans les deux switchs est une garde defensive (AGENT/AUDITEUR n'atteignent jamais ce code grace au `@PreAuthorize` du controleur, mais si jamais atteints, refuser plutot que planter).

### `AgentAdminService.creer()` -- nouvelle sequence

```java
@Transactional
public CreerAgentResponse creer(CreerAgentRequest request) {
    Agent appelant = appelantCourant();
    verifierRoleAssignable(appelant.getRole(), request.role());

    Poste poste = posteRepository.findById(request.posteId())
            .orElseThrow(() -> new PosteIntrouvableException(request.posteId()));
    verifierPerimetrePoste(appelant, poste);

    if (agentRepository.findByMatricule(request.matricule()).isPresent()) {
        throw new MatriculeDejaUtiliseException(request.matricule());
    }
    // ... suite inchangee (generation mot de passe, save, reponse)
}
```

### `AgentAdminService.lister()` -- nouvelle sequence

```java
@Transactional(readOnly = true)
public List<AgentResponse> lister() {
    Agent appelant = appelantCourant();
    List<Agent> agents = switch (appelant.getRole()) {
        case CHEF_POSTE -> agentRepository.findByPosteId(appelant.getPoste().getId());
        case ADMIN_REGIONAL -> agentRepository.findByPosteRegionId(appelant.getPoste().getRegion().getId());
        case ADMIN_NATIONAL -> agentRepository.findAll();
        default -> throw new AccesRefuseException("Role sans perimetre de lecture defini.");
    };
    return agents.stream().map(AgentResponse::of).toList();
}
```

### `AgentAdminService.modifier()` -- nouvelle sequence

```java
@Transactional
public AgentResponse modifier(UUID id, ModifierAgentRequest request) {
    Agent agent = agentRepository.findById(id).orElseThrow(() -> new AgentIntrouvableException(id));
    Agent appelant = appelantCourant();
    verifierPerimetrePoste(appelant, agent.getPoste());

    String nomResolu = agent.getNom();
    if (request.nom() != null) {
        if (request.nom().isBlank()) {
            throw new IllegalArgumentException("nom ne peut pas être vide");
        }
        nomResolu = request.nom();
    }

    Poste posteResolu = agent.getPoste();
    if (request.posteId() != null) {
        posteResolu = posteRepository.findById(request.posteId())
                .orElseThrow(() -> new PosteIntrouvableException(request.posteId()));
        verifierPerimetrePoste(appelant, posteResolu);
    }

    agent.modifierInformations(nomResolu, posteResolu);
    agentRepository.save(agent);
    return AgentResponse.of(agent);
}
```

### `AgentAdminService.desactiver()` -- nouvelle sequence

```java
@Transactional
public void desactiver(UUID id) {
    Agent agent = agentRepository.findById(id).orElseThrow(() -> new AgentIntrouvableException(id));
    Agent appelant = appelantCourant();
    verifierPerimetrePoste(appelant, agent.getPoste());

    agent.desactiver();
    agentRepository.save(agent);
}
```

Points d'implementation a respecter :
- Ne pas utiliser `((Agent) authentication.getPrincipal())` : le principal pose par `JwtAuthenticationFilter` est le `String` matricule (`UsernamePasswordAuthenticationToken(matricule, null, List.of(role))`), pas l'entite `Agent`. `appelantCourant()` doit lire `Authentication#getName()`.
- `desactiver_appeleDeuxFois` (test existant) : le deuxieme appel doit rester 204 -- `verifierPerimetrePoste` s'applique sur le poste courant de l'agent cible (inchange par la desactivation elle-meme), donc aucun risque de regression.
- Ne pas court-circuiter `appelantCourant()` avant la resolution 404 sur PATCH/DELETE : l'ordre impose est resolution-agent-cible d'abord (comme aujourd'hui), puis chargement de l'appelant, puis perimetre. Sur POST, c'est l'inverse (role d'abord, poste ensuite) -- suivre exactement l'ordre donne plus haut, ne pas le permuter par souci de "coherence" entre endpoints : c'est une decision assumee du design (#4).

## Plan de tests

### Non-regression (ne pas modifier, doivent rester verts sans changement)

Tous appellent avec `ADMIN_NATIONAL`, dont le poste est aussi le poste cible ou dans la meme region -- `verifierPerimetrePoste`/`verifierRoleAssignable` n'y ajoutent aucune restriction :
`creer_avecRoleAgent_shouldRetourner403`, `patch_avecRoleAuditeur_shouldRetourner403`, `desactiver_avecRoleAgent_shouldRetourner403`, `creer_avecDonneesValides_shouldRetourner201EtMotDePasseTemporaire`, `creer_avecMatriculeDejaUtilise_shouldRetourner409`, `creer_avecPosteInconnu_shouldRetourner404`, `modifier_avecPosteInconnu_shouldRetourner404`, `modifier_avecIdInconnu_shouldRetourner404`, `desactiver_avecIdInconnu_shouldRetourner404`, `modifier_avecNomEtPoste_shouldMettreAJourEtConserverHashEtRole`, `desactiver_appeleDeuxFois_shouldRetourner204LesDeuxFois`, `desactiver_puisLogin_shouldRetourner401`, `desactiver_puisRefresh_shouldRetourner401Immediatement`, `desactiver_accessTokenDejaEmis_shouldResterValideJusquaExpiration`.

A verifier explicitement en codant (executer la suite complete) : ces 14 tests restent verts sans aucune modification de leur code.

### Nouveaux tests d'integration -- `AgentAdminIntegrationTest.java`

Helper a ajouter dans le test : `creerPoste(Region region, String nom)` (variante de `creerPoste()` acceptant une region et un nom, pour construire des scenarios multi-postes/multi-regions) et `creerRegion(String nom)` (`regionRepository.save(new Region(nom))`).

Correspondance critere d'acceptation -> tests :

**AC1 (`@PreAuthorize` selon la matrice)** -- deja couvert par le controleur existant + tests de non-regression ci-dessus ; pas de nouveau test requis pour ce critere seul (aucune annotation ne change).

**AC2 (agent hors perimetre -> 403) et matrice complete par role/endpoint** :

GET /api/v1/agents :
- `lister_commeChefPoste_shouldRetournerAgentsDeSonPosteUniquement` (positif) -- deux postes dans une meme region, un agent dans chacun, appel avec un token CHEF_POSTE du premier poste, verifier que la reponse ne contient que l'agent (+ l'appelant) du premier poste.
- `lister_commeAdminRegional_shouldRetournerAgentsDeSaRegionUniquement` (positif) -- deux regions, un poste par region, un agent par poste, verifier que l'ADMIN_REGIONAL de la region A ne voit que les agents des postes de la region A.
- `lister_commeAdminNational_shouldRetournerTousLesAgents` (positif) -- deux postes/regions distincts, verifier que la liste contient les agents des deux.
- `lister_commeAgent_shouldRetourner403` (403 par role -- absent des tests actuels, seul le controleur `hasAnyRole` le couvrait deja indirectement, ajouter le test explicite).
- `lister_commeAuditeur_shouldRetourner403` (403 par role, idem).

POST /api/v1/agents :
- `creer_commeChefPoste_versSonPropresPoste_avecRoleAgent_shouldRetourner201` (positif).
- `creer_commeChefPoste_versAutrePoste_shouldRetourner403` (perimetre, meme role AGENT demande).
- `creer_commeChefPoste_avecRoleChefPoste_shouldRetourner403` (assignation de role refusee, poste correct).
- `creer_commeAdminRegional_versPosteDeSaRegion_avecRoleChefPoste_shouldRetourner201` (positif).
- `creer_commeAdminRegional_versPosteHorsRegion_shouldRetourner403` (perimetre).
- `creer_commeAdminRegional_avecRoleAdminNational_shouldRetourner403` (assignation de role refusee, poste correct).
- `creer_commeAdminNational_avecRoleAdminNational_shouldRetourner201` (positif, prouve l'absence de restriction de role -- complementaire au test existant `creer_avecDonneesValides...` qui n'utilise que le role AGENT).
- `creer_commeAuditeur_shouldRetourner403` (403 par role, absent des tests actuels pour cet endpoint).
- (`creer_avecRoleAgent_shouldRetourner403` existant couvre deja AGENT sur cet endpoint.)

PATCH /api/v1/agents/{id} :
- `modifier_commeChefPoste_agentDeSonPoste_shouldRetourner200` (positif, changement de nom seul).
- `modifier_commeChefPoste_agentDAutrePoste_shouldRetourner403` (perimetre sur poste actuel de la cible).
- `modifier_commeChefPoste_versAutrePoste_shouldRetourner403` (agent cible dans son poste, mais `posteId` de la requete pointe hors de son perimetre -- verifie la regle "empeche de faire sortir un agent de son perimetre").
- `modifier_commeAdminRegional_agentDeSaRegion_shouldRetourner200` (positif).
- `modifier_commeAdminRegional_agentHorsRegion_shouldRetourner403` (perimetre).
- `modifier_commeAdminRegional_versPosteHorsRegion_shouldRetourner403` (meme logique que pour CHEF_POSTE, au niveau region).
- `modifier_commeAgent_shouldRetourner403` (403 par role, absent des tests actuels pour cet endpoint -- seul AUDITEUR y est teste aujourd'hui via `patch_avecRoleAuditeur_shouldRetourner403`).
- `modifier_commeChefPoste_avecIdInconnu_shouldRetourner404` (verifie l'ordre de decision cle #4 : 404 avant 403, meme pour un appelant avec perimetre restreint -- distinct du test existant `modifier_avecIdInconnu_shouldRetourner404` qui utilise ADMIN_NATIONAL).
- (`modifier_avecNomEtPoste_...` existant couvre deja le cas positif ADMIN_NATIONAL sans restriction.)

DELETE /api/v1/agents/{id} :
- `desactiver_commeChefPoste_agentDeSonPoste_shouldRetourner204` (positif).
- `desactiver_commeChefPoste_agentDAutrePoste_shouldRetourner403` (perimetre).
- `desactiver_commeAdminRegional_agentDeSaRegion_shouldRetourner204` (positif).
- `desactiver_commeAdminRegional_agentHorsRegion_shouldRetourner403` (perimetre).
- `desactiver_commeAuditeur_shouldRetourner403` (403 par role, absent des tests actuels pour cet endpoint -- seul AGENT y est teste aujourd'hui).
- `desactiver_commeChefPoste_avecIdInconnu_shouldRetourner404` (ordre 404 avant 403, meme logique que pour PATCH -- distinct du test existant `desactiver_avecIdInconnu_shouldRetourner404` qui utilise ADMIN_NATIONAL).
- (`desactiver_appeleDeuxFois_...` existant couvre deja le cas positif ADMIN_NATIONAL sans restriction ; `desactiver_avecRoleAgent_shouldRetourner403` existant couvre deja AGENT sur cet endpoint.)

Robustesse `appelantCourant()` :
- `lister_commeChefPosteDesactiveApresEmissionDuToken_shouldRetourner403AccesRefuse` -- reproduit le pattern des tests existants `desactiver_puisLogin_...`/`desactiver_accessTokenDejaEmis_...` : login d'un CHEF_POSTE, un ADMIN_NATIONAL le desactive ensuite (`DELETE /api/v1/agents/{id}`), puis reutilisation du token access encore valide du CHEF_POSTE desactive sur `GET /api/v1/agents` -> attendu 403 avec `code = ACCES_REFUSE`, PAS 500. Couvre explicitement le risque documente dans le design ("Optional.get() non gere").

**Couverture "au moins un cas positif et un cas 403 par role" (exigence explicite de l'AC4)** -- recapitulatif par role, pour verification finale :
- AGENT : positif -> voir "Ecarts identifies" ci-dessous (aucun acces legitime au CRUD agents, resolu via un endpoint different) ; 403 -> `lister_commeAgent_shouldRetourner403`, `creer_avecRoleAgent_shouldRetourner403` (existant), `modifier_commeAgent_shouldRetourner403`, `desactiver_avecRoleAgent_shouldRetourner403` (existant).
- CHEF_POSTE : positif -> `lister_commeChefPoste_...`, `creer_commeChefPoste_versSonPropresPoste_...`, `modifier_commeChefPoste_agentDeSonPoste_...`, `desactiver_commeChefPoste_agentDeSonPoste_...` ; 403 -> `creer_commeChefPoste_versAutrePoste_...`, `creer_commeChefPoste_avecRoleChefPoste_...`, `modifier_commeChefPoste_agentDAutrePoste_...`, `desactiver_commeChefPoste_agentDAutrePoste_...`.
- ADMIN_REGIONAL : positif -> `lister_commeAdminRegional_...`, `creer_commeAdminRegional_versPosteDeSaRegion_...`, `modifier_commeAdminRegional_agentDeSaRegion_...`, `desactiver_commeAdminRegional_agentDeSaRegion_...` ; 403 -> `creer_commeAdminRegional_versPosteHorsRegion_...`, `creer_commeAdminRegional_avecRoleAdminNational_...`, `modifier_commeAdminRegional_agentHorsRegion_...`, `desactiver_commeAdminRegional_agentHorsRegion_...`.
- ADMIN_NATIONAL : positif -> `creer_avecDonneesValides_...` (existant), `modifier_avecNomEtPoste_...` (existant), `desactiver_appeleDeuxFois_...` (existant), `lister_commeAdminNational_...` (nouveau) ; 403 -> aucun cas 403 legitime pour ADMIN_NATIONAL sur ce CRUD (role sans restriction par design) -- voir "Ecarts identifies".
- AUDITEUR : positif -> voir "Ecarts identifies" (meme raisonnement qu'AGENT) ; 403 -> `patch_avecRoleAuditeur_shouldRetourner403` (existant), `creer_commeAuditeur_shouldRetourner403`, `desactiver_commeAuditeur_shouldRetourner403`, `lister_commeAuditeur_shouldRetourner403`.

**AC3 (admin regional : agrege de sa region oui, detail nominatif d'un autre poste non)** -- aucun endpoint de stats n'existe (#26). Couvert par :
- `PerimetreRegionalTest` (nouveau, unitaire pur, `sn/samapiece/iam/security/PerimetreRegionalTest.java`) : `estDansPerimetreRegion_avecAdminNational_shouldRetournerVraiPourNimporteQuelleRegion`, `estDansPerimetreRegion_avecAdminRegionalDeLaMemeRegion_shouldRetournerVrai`, `estDansPerimetreRegion_avecAdminRegionalDuneAutreRegion_shouldRetournerFaux`, `estDansPerimetreRegion_avecChefPoste_shouldRetournerFaux`, `estDansPerimetreRegion_avecAgent_shouldRetournerFaux`, `estDansPerimetreRegion_avecAuditeur_shouldRetournerFaux`.
- `lister_commeAdminRegional_shouldRetournerAgentsDeSaRegionUniquement` (ci-dessus) demontre deja, sur l'endpoint existant le plus proche, que ADMIN_REGIONAL ne recoit jamais le detail nominatif d'un poste hors de sa region.
- Le detail "agrege vs detail nominatif" (distinction de DTO) reste explicitement hors de portee de ce ticket (pas d'endpoint cible) -- test manuel/couverture reportee au ticket #26, a documenter dans sa propre spec en reutilisant `PerimetreRegional.estDansPerimetreRegion`.

## Ecarts identifies

- **AC4 pour AGENT/AUDITEUR ("au moins un cas positif par role")** : ces deux roles n'ont aucun acces legitime au CRUD `/api/v1/agents` (matrice = 403 partout, decision assumee du design). Il n'existe donc pas de "cas positif" a tester sur cet endpoint pour ces deux roles. Resolution retenue pour satisfaire l'esprit de l'AC sans inventer un acces qui contredirait le design : reutiliser `GET /api/v1/postes` (endpoint public existant, deja fonctionnel avec n'importe quel role authentifie ou meme sans authentification) comme "cas positif" demontrant qu'un token AGENT/AUDITEUR valide fonctionne bien pour un endpoint auquel ils ont legitimement acces. Tests suggeres : `listerPostes_commeAgent_shouldRetourner200` et `listerPostes_commeAuditeur_shouldRetourner200` (a placer dans `AgentAdminIntegrationTest.java` par commodite d'infrastructure de test existante, ou dans un test dedie au controleur `PosteController` si prefere par le codeur -- les deux sont acceptables, aucune contrainte du design ne l'empeche). A confirmer une fois pour toutes ici plutot que de laisser un flou bloquant au codage.
- **AC4 pour ADMIN_NATIONAL ("au moins un cas 403 par role")** : par design, ADMIN_NATIONAL n'a aucune restriction de perimetre ni de role sur le CRUD agents -- il n'existe donc aucun scenario 403 lie au RBAC pour ce role sur ces 4 endpoints. Le seul 403 possible pour un token ADMIN_NATIONAL serait un cas hors sujet RBAC (ex. token expire/invalide, deja couvert par les tests JWT du ticket #7, `AuthIntegrationTest`/`JwtServiceTest`). Resolution retenue : ne pas fabriquer de test 403 artificiel pour ADMIN_NATIONAL sur `/api/v1/agents` (ce serait un faux positif ne testant rien de reel) ; documenter ce choix ici comme reponse explicite a l'ecart plutot que de le laisser ouvert.
