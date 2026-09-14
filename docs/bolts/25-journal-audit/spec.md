# Spec -- #25 Journal d'audit immuable des actions sensibles

## Résumé

Ajout d'un module `sn.samapiece.audit` (entité + repository restreint + service + aspect `@Around` + endpoint de lecture paginé) qui journalise création, consultation, retrait, signalement et déblocage de `Piece` via une nouvelle annotation `@ActionAuditee`, avec écriture d'audit **en transaction séparée après la fin (commit ou rollback) de la transaction métier**, jamais capable de faire échouer ni de masquer l'action interceptée.

## Décision tranchée : transaction de l'écriture d'audit

**Choix retenu : (b) transaction séparée / best-effort, écrite directement par l'aspect après `joinPoint.proceed()` -- pas de mécanisme d'événement supplémentaire de type `@TransactionalEventListener(AFTER_COMMIT)`.**

Analyse du code existant confirmant la faisabilité :
- `PieceController` n'a aucune annotation `@Transactional` propre ; aucun filtre servlet du repo n'ouvre de transaction au niveau de la requête HTTP (pas d'`OpenSessionInViewFilter` transactionnel, `spring.jpa.open-in-view` non surchargé dans `application.yml`).
- `PieceService.creer/retirer/signaler/debloquer` sont chacun `@Transactional` (voir `backend/src/main/java/sn/samapiece/enregistrement/PieceService.java:46,93,113,130`). Le proxy transactionnel de Spring démarre et **commit (ou rollback)** la transaction intégralement à l'intérieur de l'appel `pieceService.xxx(...)`, avant que le contrôle ne revienne à la méthode du contrôleur.
- `AuditAspect` est un `@Around("@annotation(actionAuditee)")` posé sur la **méthode du contrôleur**, pas sur la méthode du service. Quand `joinPoint.proceed()` retourne (cas succès) ou lève (cas échec), la transaction métier sous-jacente est déjà terminée dans les deux cas -- committée en cas de succès, rollback déjà effectué par le proxy transactionnel du service en cas d'exception métier (`AccesRefuseException`, `PieceIntrouvableException`, `TransitionStatutInterditeException`, etc.).
- Conséquence : contrairement à `PieceIndexableEvent`/`PieceIndexationListener` (qui doivent utiliser `@TransactionalEventListener(AFTER_COMMIT)` car l'événement est publié **depuis l'intérieur** de la transaction du service), **aucun mécanisme d'événement n'est nécessaire ici** : l'aspect appelle directement `EvenementAuditService.enregistrer(...)` après `proceed()`, et cet appel se produit structurellement après la fin de la transaction métier.

Contrat imposé au codeur pour préserver cette garantie :
- `EvenementAuditService.enregistrer(...)` est annoté `@Transactional(propagation = Propagation.REQUIRES_NEW)` -- explicite et robuste même si l'architecture évolue plus tard (ex. si un jour un filtre/aspect englobant ouvre une transaction de requête, `REQUIRES_NEW` garantit toujours une transaction indépendante pour l'audit).
- L'appel à `evenementAuditService.enregistrer(...)` est **entouré d'un `try/catch(Exception)` dans `AuditAspect`** : si l'écriture d'audit elle-même échoue (contrainte DB, connexion, etc.), l'exception est loguée en `ERROR` via SLF4J et **avalée** -- jamais propagée. L'action métier (retour ou exception d'origine du `joinPoint.proceed()`) n'est jamais altérée par un échec d'écriture d'audit.
- Compromis assumé et à documenter dans le code (commentaire sur `AuditAspect`) : la garantie d'exhaustivité de `evenement_audit` n'est **pas transactionnellement stricte** -- une écriture d'audit peut silencieusement échouer (log uniquement) sans jamais bloquer ni annuler l'action métier. C'est cohérent avec l'objectif du ticket ("traçabilité pour prévenir/investiguer la fraude", pas un système où la cohérence transactionnelle prime absolument) et avec le pattern déjà établi dans le repo pour les side-effects non critiques.

## Tâches

- [ ] `backend/pom.xml` -- ajouter la dépendance `spring-boot-starter-aop` (sans version, gérée par le parent `spring-boot-starter-parent`). Aucune configuration `@EnableAspectJAutoProxy` manuelle n'est nécessaire : `AopAutoConfiguration` de Spring Boot l'active automatiquement dès que la dépendance est présente.
- [ ] `backend/src/main/resources/db/migration/V10__create_evenement_audit.sql` -- créer la table `evenement_audit` (voir Contrat technique pour le DDL exact) avec index sur `entite_cible, entite_cible_id`, `acteur_id`, et `horodatage DESC`.
- [ ] `backend/src/main/java/sn/samapiece/audit/package-info.java` -- package-info du nouveau module.
- [ ] `backend/src/main/java/sn/samapiece/audit/EvenementAudit.java` -- entité JPA, aucun setter, aucune méthode de mutation, un seul constructeur complet (7 paramètres métier, `id` généré via `GenerationType.UUID`, `horodatage` `insertable=false`/`updatable=false` avec `DEFAULT now()` porté par la migration, exactement le pattern de `Piece.creeLe`).
- [ ] `backend/src/main/java/sn/samapiece/audit/EvenementAuditRepository.java` -- **n'étend pas** `JpaRepository`, étend `org.springframework.data.repository.Repository<EvenementAudit, UUID>` avec uniquement 3 méthodes déclarées explicitement : `save`, `findAll(Pageable)`, `findById`. Aucune méthode `delete*`/`update` ne doit être déclarée sur l'interface (garantie de compilation, pas seulement de convention).
- [ ] `backend/src/main/java/sn/samapiece/audit/EvenementAuditService.java` -- `enregistrer(...)` (`@Transactional(propagation = Propagation.REQUIRES_NEW)`, appelée par l'aspect) et `lister(Pageable)` (`@Transactional(readOnly = true)`, appelée par le contrôleur, retourne `Page<EvenementAuditResponse>`).
- [ ] `backend/src/main/java/sn/samapiece/audit/ActionAuditee.java` -- annotation `@Retention(RUNTIME) @Target(METHOD)`, attributs `action()` et `entiteCible()` (String).
- [ ] `backend/src/main/java/sn/samapiece/audit/AuditAspect.java` -- `@Aspect @Component`, `@Around("@annotation(actionAuditee)")`. Voir Contrat technique pour l'algorithme exact (extraction `entiteCibleId`, résolution acteur/IP, construction `details`, gestion succès/échec, non-propagation d'une erreur d'audit).
- [ ] `backend/src/main/java/sn/samapiece/audit/web/EvenementAuditResponse.java` -- DTO record de sortie (jamais l'entité directement), avec méthode statique `of(EvenementAudit)`.
- [ ] `backend/src/main/java/sn/samapiece/audit/web/AuditController.java` -- `GET /api/v1/audit/evenements`, `@PreAuthorize("hasAnyRole('AUDITEUR','ADMIN_NATIONAL')")`, paramètre `Pageable` avec `@PageableDefault(size = 20, sort = "horodatage", direction = Sort.Direction.DESC)`.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/PieceService.java` -- ajouter `consulter(UUID id)` : `@Transactional(readOnly = true)`, résout l'appelant via `appelantCourant()` (réutilise la méthode privée existante), charge la `Piece` (404 via `PieceIntrouvableException` sinon), vérifie `!appelant.getPoste().getId().equals(piece.getPoste().getId())` -> `AccesRefuseException` (comparaison directe, identique à `retirer`/`signaler`, **pas** `PerimetrePoste.estDansPerimetre`), retourne `PieceResponse.of(piece)`.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/web/PieceController.java` -- ajouter `GET /{id}` (`consulter`) avec `@PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE')")` (voir Écarts identifiés -- RBAC alignée sur `retirer`/`signaler`, pas sur la proposition initiale du design) et `@ActionAuditee(action = "PIECE_CONSULTEE", entiteCible = "PIECE")` ; poser `@ActionAuditee` sur `creer` (`PIECE_CREEE`, `entiteCible = "PIECE"`), `retirer` (`PIECE_RETIREE`), `signaler` (`PIECE_SIGNALEE`), `debloquer` (`PIECE_DEBLOQUEE`).
- [ ] `backend/src/test/java/sn/samapiece/audit/EvenementAuditRepositoryContractTest.java` -- test unitaire (pas de contexte Spring) vérifiant par réflexion que `EvenementAuditRepository` ne déclare aucune méthode dont le nom commence par `delete` ou `update`.
- [ ] `backend/src/test/java/sn/samapiece/audit/AuditAspectTest.java` -- tests unitaires Mockito de `AuditAspect` (voir Plan de tests).
- [ ] `backend/src/test/java/sn/samapiece/audit/EvenementAuditServiceTest.java` -- tests unitaires Mockito de `enregistrer`/`lister`.
- [ ] `backend/src/test/java/sn/samapiece/audit/PieceAuditIntegrationTest.java` -- test d'intégration `@SpringBootTest` + `MockMvc` + Testcontainers couvrant la génération effective d'`evenement_audit` par les endpoints `PieceController` (voir Plan de tests). Respecter l'ordre de nettoyage `@BeforeEach` (voir Contrat technique).
- [ ] `backend/src/test/java/sn/samapiece/audit/AuditEndpointIntegrationTest.java` -- test d'intégration RBAC de `GET /api/v1/audit/evenements`.

## Contrat technique

### Migration `V10__create_evenement_audit.sql`

```sql
CREATE TABLE evenement_audit (
    id               UUID PRIMARY KEY,
    acteur_id        UUID,
    type_acteur      VARCHAR(50) NOT NULL,
    action           VARCHAR(100) NOT NULL,
    entite_cible     VARCHAR(100) NOT NULL,
    entite_cible_id  UUID,
    details          JSONB NOT NULL,
    adresse_ip       VARCHAR(45),
    horodatage       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_evenement_audit_entite_cible ON evenement_audit(entite_cible, entite_cible_id);
CREATE INDEX idx_evenement_audit_acteur_id ON evenement_audit(acteur_id);
CREATE INDEX idx_evenement_audit_horodatage ON evenement_audit(horodatage DESC);
```

Pas de FK sur `acteur_id` ni `entite_cible_id` (générique, cohérent avec la décision du design -- l'ERD 9.1 prévoit d'autres types d'acteurs/entités futurs). `adresse_ip` en `VARCHAR(45)` pour couvrir IPv6.

### `EvenementAudit` (entité)

```java
@Entity
@Table(name = "evenement_audit")
public class EvenementAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "acteur_id")
    private UUID acteurId;

    @Column(name = "type_acteur", nullable = false)
    private String typeActeur;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "entite_cible", nullable = false)
    private String entiteCible;

    @Column(name = "entite_cible_id")
    private UUID entiteCibleId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", nullable = false, columnDefinition = "jsonb")
    private String details;

    @Column(name = "adresse_ip")
    private String adresseIp;

    @Column(name = "horodatage", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime horodatage;

    public EvenementAudit(UUID acteurId, String typeActeur, String action, String entiteCible,
            UUID entiteCibleId, String details, String adresseIp) { ... }

    // uniquement des getters -- aucun setter, aucune méthode de mutation.
}
```

### `EvenementAuditRepository`

```java
public interface EvenementAuditRepository extends Repository<EvenementAudit, UUID> {
    EvenementAudit save(EvenementAudit evenementAudit);
    Page<EvenementAudit> findAll(Pageable pageable);
    Optional<EvenementAudit> findById(UUID id);
}
```

### `EvenementAuditService`

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void enregistrer(UUID acteurId, String typeActeur, String action, String entiteCible,
        UUID entiteCibleId, String details, String adresseIp) {
    evenementAuditRepository.save(new EvenementAudit(
            acteurId, typeActeur, action, entiteCible, entiteCibleId, details, adresseIp));
}

@Transactional(readOnly = true)
public Page<EvenementAuditResponse> lister(Pageable pageable) {
    return evenementAuditRepository.findAll(pageable).map(EvenementAuditResponse::of);
}
```

### `ActionAuditee`

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ActionAuditee {
    String action();
    String entiteCible();
}
```

### `AuditAspect` -- algorithme exact

```java
@Around("@annotation(actionAuditee)")
public Object autourAction(ProceedingJoinPoint joinPoint, ActionAuditee actionAuditee) throws Throwable {
    UUID entiteCibleId = extraireIdDepuisPathVariable(joinPoint);
    try {
        Object retour = joinPoint.proceed();
        UUID idFinal = entiteCibleId != null ? entiteCibleId : extraireIdDepuisReponse(retour);
        auditerSansPropagerErreur(actionAuditee, idFinal, "SUCCES", null);
        return retour;
    } catch (Throwable ex) {
        auditerSansPropagerErreur(actionAuditee, entiteCibleId, "ECHEC", ex);
        throw ex; // ne JAMAIS masquer l'exception métier d'origine
    }
}
```

Règles précises :
- **Extraction de `entiteCibleId` -- deux sources, dans cet ordre** :
  1. Le premier paramètre de la méthode annotée `@PathVariable` dont le type est `UUID` (via `MethodSignature.getMethod().getParameterAnnotations()` + `joinPoint.getArgs()`). Couvre `consulter`, `retirer`, `signaler`, `debloquer`.
  2. Si aucun `@PathVariable` UUID n'existe (cas de `creer`, qui n'a pas d'id dans l'URL) **et uniquement en cas de succès** (le retour n'existe pas en cas d'échec avant persistance) : si `retour instanceof ResponseEntity<?> re` et que `re.getBody()` possède une méthode publique sans argument nommée `id` retournant `UUID` (cas d'un record comme `PieceResponse`, invoquée par réflexion), l'utiliser. Si l'extraction échoue pour n'importe quelle raison, `entiteCibleId` reste `null` (pas d'exception levée pour cette seule extraction) et un `log.debug` est émis -- l'entrée d'audit est quand même écrite avec `entiteCibleId = null`.
- **Résolution de l'acteur** : `SecurityContextHolder.getContext().getAuthentication().getName()` (matricule) -> `agentRepository.findByMatricule(matricule)` -> `.map(Agent::getId).orElse(null)`. Duplication assumée avec `PieceService.appelantCourant()` (pas de composant partagé dans ce ticket, dette déjà actée par le design). `typeActeur` est fixé à la constante `"AGENT"`.
- **Résolution de l'IP** : `RequestContextHolder.getRequestAttributes()` casté en `ServletRequestAttributes`, puis `getRequest().getRemoteAddr()`. `null` si aucune requête n'est liée au thread courant (ne doit normalement jamais arriver dans ce contexte HTTP synchrone). Pas de gestion `X-Forwarded-For` (identique à `RecherchePubliqueRateLimitFilter`).
- **Construction de `details`** (JSON, via l'`ObjectMapper` Spring injecté) :
  - Succès : `{"resultat":"SUCCES"}`
  - Échec : `{"resultat":"ECHEC","exception":"<simpleName de la classe de l'exception>","message":"<ex.getMessage()>"}`
  - Aucune donnée métier (nom/prénom titulaire, etc.) n'est dupliquée dans `details` (minimisation -- `entiteCibleId` suffit à retrouver la fiche pour un auditeur habilité).
- **`auditerSansPropagerErreur`** entoure l'appel à `evenementAuditService.enregistrer(...)` d'un `try/catch(Exception)` : toute exception levée par l'écriture d'audit elle-même est capturée et loguée en `ERROR` (SLF4J, avec `action`, `resultat` et le nom de l'agent si résolu) puis **avalée**. Ni le retour ni l'exception d'origine du `joinPoint` ne sont jamais affectés.

### `AuditController`

```java
@GetMapping("/evenements")
@PreAuthorize("hasAnyRole('AUDITEUR','ADMIN_NATIONAL')")
public Page<EvenementAuditResponse> lister(
        @PageableDefault(size = 20, sort = "horodatage", direction = Sort.Direction.DESC) Pageable pageable) {
    return evenementAuditService.lister(pageable);
}
```

Aucune configuration Spring Data Web supplémentaire n'est nécessaire (`SpringDataWebAutoConfiguration` est déjà active via `spring-boot-starter-data-jpa`).

### `PieceService.consulter` / `PieceController.consulter`

```java
@Transactional(readOnly = true)
public PieceResponse consulter(UUID pieceId) {
    Agent appelant = appelantCourant();
    Piece piece = pieceRepository.findById(pieceId)
            .orElseThrow(() -> new PieceIntrouvableException(pieceId));
    if (!appelant.getPoste().getId().equals(piece.getPoste().getId())) {
        throw new AccesRefuseException("Poste hors perimetre pour cette piece.");
    }
    return PieceResponse.of(piece);
}
```

```java
@GetMapping("/{id}")
@PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE')")
@ActionAuditee(action = "PIECE_CONSULTEE", entiteCible = "PIECE")
public ResponseEntity<PieceResponse> consulter(@PathVariable UUID id) {
    return ResponseEntity.ok(pieceService.consulter(id));
}
```

### Ordre de nettoyage `@BeforeEach` pour tout `@SpringBootTest` du module audit créant des `Piece`/`Poste`

`EvenementAuditRepository` n'expose **aucune** méthode `deleteAll`/`delete*` (restriction volontaire de l'interface). Le nettoyage de `evenement_audit` entre tests doit donc passer par `JdbcTemplate`, comme `piece_sequence` :

```java
@BeforeEach
void nettoyer() {
    jdbcTemplate.update("DELETE FROM evenement_audit");
    retraitRepository.deleteAll();
    pieceRepository.deleteAll();
    jdbcTemplate.update("DELETE FROM piece_sequence"); // avant les Poste (FK)
    agentRepository.deleteAll();
    posteRepository.deleteAll();
    regionRepository.deleteAll();
}
```

(`evenement_audit` n'a pas de FK vers `agent`/`piece`/`poste` donc l'ordre exact vis-à-vis de ces tables n'a pas d'impact FK, mais il doit être vidé pour garantir l'isolation entre tests qui font des assertions de comptage/contenu sur cette table.)

## Plan de tests

| Critère d'acceptation / cas | Test | Type |
|---|---|---|
| AC1 -- table append-only enregistrant acteur/action/entité/détails/IP/horodatage | `PieceAuditIntegrationTest.consulter_commeAgentDuPoste_shouldCreerEvenementAuditPieceConsultee` : après `GET /api/v1/pieces/{id}`, lecture directe via `JdbcTemplate`/`EvenementAuditRepository.findAll` de la ligne créée, assertions sur `acteur_id` (= id de l'agent), `type_acteur` = `AGENT`, `action` = `PIECE_CONSULTEE`, `entite_cible` = `PIECE`, `entite_cible_id` = id de la pièce, `adresse_ip` non nul, `horodatage` non nul, `details` contient `"resultat":"SUCCES"` | Intégration `@SpringBootTest` + `MockMvc` + Testcontainers |
| AC1 -- garantie applicative "pas de delete/update" | `EvenementAuditRepositoryContractTest.repository_neDoitExposerAucuneMethodeDeleteOuUpdate` : réflexion sur `EvenementAuditRepository.class.getMethods()`, assertion qu'aucun nom ne commence par `delete`/`update` | Unitaire (JUnit pur) |
| AC2 -- interception création | `PieceAuditIntegrationTest.creer_avecDonneesValides_shouldCreerEvenementAuditPieceCreee` : `POST /api/v1/pieces`, vérifie une ligne `action=PIECE_CREEE`, `entite_cible_id` = id retourné dans la réponse (couvre la voie d'extraction par réflexion sur `PieceResponse.id()`, sans `@PathVariable`) | Intégration |
| AC2 -- interception retrait | `PieceAuditIntegrationTest.retirer_commeAgentDuPoste_shouldCreerEvenementAuditPieceRetiree` : `POST /api/v1/pieces/{id}/retrait`, vérifie `action=PIECE_RETIREE`, `resultat=SUCCES` | Intégration |
| AC2 -- interception signalement/déblocage (bonus, cohérence cycle de vie #24) | `PieceAuditIntegrationTest.signaler_...` et `debloquer_...` équivalents | Intégration |
| AC2 -- modification/export | Non implémentés sur cette branche (endpoints inexistants) -- **non couvert par un test**, documenté en Écarts identifiés | Manuel/N-A |
| AC3 -- RBAC lecture `GET /api/v1/audit/evenements` | `AuditEndpointIntegrationTest` : 403 pour `AGENT`, `CHEF_POSTE`, `ADMIN_REGIONAL` ; 200 pour `AUDITEUR` et `ADMIN_NATIONAL` ; test de forme de la réponse paginée (`content`, `totalElements`, `number`, `size`) | Intégration |
| AC4 -- consultation génère une entrée d'audit | Même test que la ligne AC1/consultation ci-dessus (`consulter_commeAgentDuPoste_shouldCreerEvenementAuditPieceConsultee`) -- c'est le test explicitement requis par le ticket | Intégration |
| Cas "tentative échouée quand même auditée" | `PieceAuditIntegrationTest.retirer_commeAgentDunAutrePoste_shouldRetourner403EtCreerEvenementAuditEchec` : `POST /api/v1/pieces/{id}/retrait` avec un agent hors périmètre, attend 403, puis vérifie une ligne `action=PIECE_RETIREE`, `entite_cible_id` = id de la pièce, `details` contient `"resultat":"ECHEC"` et `"exception":"AccesRefuseException"` | Intégration |
| RBAC consultation de fiche (`GET /api/v1/pieces/{id}`) | `PieceAuditIntegrationTest.consulter_commeAgentDunAutrePoste_shouldRetourner403` (périmètre poste), plus un test 403 pour `ADMIN_REGIONAL`/`ADMIN_NATIONAL`/`AUDITEUR` compte tenu de la RBAC resserrée (voir Écarts identifiés) | Intégration |
| L'audit ne bloque ni ne masque jamais l'action métier | `AuditAspectTest.quandEnregistrementAuditEchoue_actionMetierResteInchangee` : mock `EvenementAuditService.enregistrer(...)` levant une `RuntimeException`, vérifie que le retour du `joinPoint.proceed()` (cas succès) est quand même retourné par l'aspect, et que l'exception d'origine (cas échec) est quand même celle propagée -- pas celle de l'audit | Unitaire Mockito |
| Extraction correcte de `entiteCibleId` (les deux voies) | `AuditAspectTest.extraitIdDepuisPathVariable` et `extraitIdDepuisReponseQuandAucunPathVariable` | Unitaire Mockito |
| `EvenementAuditService.enregistrer`/`lister` | `EvenementAuditServiceTest` : vérifie l'appel à `repository.save(...)` avec les bons champs, et le mapping `Page<EvenementAudit>` -> `Page<EvenementAuditResponse>` | Unitaire Mockito |

Rappels obligatoires pour toutes les assertions `MockMvc` : `getContentAsString(StandardCharsets.UTF_8)`, jamais `getContentAsString()` sans argument.

## Écarts identifiés

1. **Extraction de `entiteCibleId` pour `POST /api/v1/pieces` non couverte par le design.** Le design ne prévoit que l'extraction via `@PathVariable UUID`, ce qui ne fonctionne pas pour `creer` (pas d'id dans l'URL, endpoint pourtant explicitement cité par le critère d'acceptation 2 -- "création ... de fiche"). Résolu dans cette spec par une règle de repli explicite : extraction par réflexion de l'accesseur `id()` sur le corps de la `ResponseEntity` retournée, uniquement en cas de succès. À implémenter exactement comme documenté dans "Contrat technique" pour que le test `creer_avecDonneesValides_shouldCreerEvenementAuditPieceCreee` passe.

2. **Incohérence RBAC/périmètre dans la proposition initiale du design pour `GET /api/v1/pieces/{id}`.** Le design proposait `@PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')")` combiné à une comparaison directe de poste (`appelant.getPoste().getId().equals(piece.getPoste().getId())`, identique à `retirer`/`signaler`). Ces deux choix sont contradictoires : un `ADMIN_REGIONAL`/`ADMIN_NATIONAL` autorisé par le rôle serait quasi systématiquement rejeté par la comparaison directe de poste dès que la pièce n'est pas rattachée à son poste exact (contrairement à `debloquer`, qui utilise `PerimetrePoste.estDansPerimetre` pour permettre un accès régional/national). Cette spec tranche en alignant strictement `consulter` sur `retirer`/`signaler` : RBAC restreinte à `AGENT`/`CHEF_POSTE` **et** comparaison directe de poste, conformément à l'instruction explicite reçue. Un accès de consultation élargi aux rôles admin (avec `PerimetrePoste`) est laissé hors périmètre, à traiter dans un futur ticket si le besoin est confirmé.

3. **Couverture partielle du critère d'acceptation 2 ("modification de fiche", "export").** Ni `PATCH /api/v1/pieces/{id}` ni l'export/reçu de dépôt (#14) n'existent sur cette branche. Le critère d'acceptation tel qu'écrit dans le ticket cite ces quatre actions comme devant être interceptées, mais seules création/consultation/retrait (+ signalement/déblocage par cohérence) le sont réellement dans ce lot. Ce n'est pas une régression introduite par ce ticket -- les fonctionnalités sous-jacentes n'existent tout simplement pas encore -- mais cela doit être signalé explicitement au reviewer et, le cas échéant, à la personne qui clôt le ticket #25 : l'AC 2 n'est satisfait qu'à la mesure des endpoints existants. L'architecture (`@ActionAuditee` + `AuditAspect`) ne nécessite aucune modification pour couvrir PATCH/export le jour où ils seront créés -- il suffira de poser l'annotation dessus, en respectant le contrat d'extraction de `entiteCibleId` documenté ci-dessus.

4. **Aucune contrainte au niveau moteur PostgreSQL.** Comme déjà noté par le design, l'immutabilité de `evenement_audit` reste purement applicative (pas de `REVOKE UPDATE/DELETE`, pas de trigger). Le critère d'acceptation 1 ("append-only") n'est donc vérifié qu'au niveau du code Java compilé (`EvenementAuditRepository` restreint), pas au niveau de la base -- limite connue, non traitée dans ce lot, cohérente avec le périmètre d'un ticket applicatif Spring Boot.
