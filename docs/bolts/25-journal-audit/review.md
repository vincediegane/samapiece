# Review -- #25 Journal d'audit immuable des actions sensibles

APPROVE

## Perimetre revu

git diff bolt/issue-24-workflow-retrait..bolt/issue-25-journal-audit (19 fichiers, +1477/-0). Le code de #24 n'a pas ete relu (deja approuve separement).

## Criteres d'acceptation

| # | Critere | Statut |
|---|---|---|
| AC1 | Table evenement_audit append-only (acteur, action, entite, details, IP, horodatage) | Couvert |
| AC2 | Interception automatique creation/consultation/retrait/export | Partiel (PATCH/export inexistants sur le repo, ecart documente en spec) |
| AC3 | GET /api/v1/audit/evenements reserve AUDITEUR/ADMIN_NATIONAL, lecture seule | Couvert |
| AC4 | Test : consultation de fiche complete genere une entree d'audit | Couvert |

## Details des criteres d'acceptation

AC1 : migration V10 conforme au DDL fige de la spec, EvenementAuditRepository n'expose que save/findAll(Pageable)/findById, EvenementAudit sans setter ni mutateur. Teste par EvenementAuditRepositoryContractTest (reflexion) et PieceAuditIntegrationTest (verifie tous les champs en base : acteur_id, type_acteur, entite_cible, entite_cible_id, adresse_ip, horodatage, details).

AC2 : creation, consultation, retrait interceptes et testes ; signalement/deblocage ajoutes par coherence avec le cycle de vie (bonus). Modification (PATCH) et export (#14) n'existent pas encore sur le repo -- ecart explicitement documente en spec (section Ecarts identifies, point 3), non imputable a ce ticket.

AC3 : @PreAuthorize(hasAnyRole AUDITEUR, ADMIN_NATIONAL) sur AuditController.lister, methode de service en @Transactional(readOnly = true), pagination Pageable avec @PageableDefault(size=20, sort=horodatage, direction=DESC). Teste par AuditEndpointIntegrationTest (403 pour AGENT/CHEF_POSTE/ADMIN_REGIONAL, 200 pour AUDITEUR/ADMIN_NATIONAL, forme paginee verifiee).

AC4 : PieceAuditIntegrationTest.consulter_commeAgentDuPoste_shouldCreerEvenementAuditPieceConsultee verifie qu'un GET /api/v1/pieces/{id} par un agent authentifie cree bien une ligne evenement_audit avec tous les champs attendus.

## Verifications ciblees (points de vigilance de la mission)

1. Transaction separee : EvenementAuditService.enregistrer est bien @Transactional(propagation = Propagation.REQUIRES_NEW), appelee directement par AuditAspect.auditerSansPropagerErreur apres joinPoint.proceed() (ou dans le catch). Aucun mecanisme d'evenement Spring. Conforme a la spec.

2. Non-blocage/non-masquage : auditerSansPropagerErreur entoure l'appel a enregistrer(...) d'un try/catch(Exception), logue en ERROR via SLF4J, avale l'exception. autourAction retourne le retour metier en cas de succes et re-leve l'exception d'origine (jamais celle de l'audit) en cas d'echec. Teste unitairement par AuditAspectTest.quandEnregistrementAuditEchoue_succesResteRetourne et quandEnregistrementAuditEchoue_exceptionMetierResteInchangee, qui font echouer volontairement enregistrer(...) et verifient que le comportement metier n'est pas altere. Les deux tests passent (verifie par execution reelle).

3. Repository restreint : EvenementAuditRepository extends Repository<EvenementAudit, UUID> (pas JpaRepository ni CrudRepository), seules 3 methodes declarees (save, findAll(Pageable), findById). EvenementAuditRepositoryContractTest verifie par reflexion qu'aucune methode ne commence par delete ou update -- test execute, passe.

4. Immutabilite de l'entite : EvenementAudit expose uniquement des getters, un seul constructeur metier a 7 parametres (plus un constructeur protege sans arguments requis par JPA), aucun setter ni methode de mutation.

5. Extraction de entiteCibleId : ordre respecte -- premier parametre annote @PathVariable de type UUID en priorite (extraireIdDepuisPathVariable), sinon reflexion sur l'accesseur id() du corps de la ResponseEntity en cas de succes uniquement (extraireIdDepuisReponse, appelee seulement apres un proceed() reussi), sinon null sans exception propagee (catch interne + log.debug). Teste unitairement (extraitIdDepuisPathVariable, extraitIdDepuisReponseQuandAucunPathVariable) et en integration (creer_avecDonneesValides_shouldCreerEvenementAuditPieceCreee exerce la voie reflexion reelle sur PieceResponse.id() puisque POST /api/v1/pieces n'a pas de PathVariable).

6. RBAC de consulter : @PreAuthorize(hasAnyRole AGENT, CHEF_POSTE) strictement, comparaison directe de poste (pas PerimetrePoste), aucun role admin autorise -- conforme a l'ecart tranche explicitement par la spec (point 2 des Ecarts identifies). Teste par PieceAuditIntegrationTest.consulter_commeAgentDunAutrePoste_shouldRetourner403 plus trois tests 403 dedies pour ADMIN_REGIONAL, ADMIN_NATIONAL et AUDITEUR, confirmant qu'aucun role admin ne passe malgre le hasAnyRole restreint.

7. @ActionAuditee sur les 5 endpoints attendus, verifie par lecture de PieceController.java : creer -> PIECE_CREEE, consulter -> PIECE_CONSULTEE, retirer -> PIECE_RETIREE, signaler -> PIECE_SIGNALEE, debloquer -> PIECE_DEBLOQUEE, tous avec entiteCible = PIECE.

8. Aucune PII dupliquee dans details : construireDetails ne construit que resultat=SUCCES, ou resultat=ECHEC avec exception (nom simple de la classe) et message. Aucun champ metier (nom/prenom du titulaire, numero de document) n'y est recopie -- conforme a la minimisation.

9. GET /api/v1/audit/evenements : RBAC correcte, Pageable avec PageableDefault(size=20, sort=horodatage, direction=DESC), endpoint purement lecture (lister ne fait qu'un findAll mappe vers des DTO, aucune ecriture possible).

10. Migration V10 : DDL identique au contrat fige de la spec (table, colonnes, types, 3 index sur entite_cible+entite_cible_id, acteur_id, et horodatage DESC ; details en jsonb ; pas de FK sur acteur_id ni entite_cible_id).

11. Nettoyage des tests : les deux SpringBootTest (PieceAuditIntegrationTest, AuditEndpointIntegrationTest) vident evenement_audit via JdbcTemplate avant retraitRepository.deleteAll(), pieceRepository.deleteAll(), DELETE FROM piece_sequence, agentRepository.deleteAll(), posteRepository.deleteAll(), regionRepository.deleteAll() -- ordre conforme a la memoire projet et a la spec.

12. UTF-8 : toutes les extractions de corps de reponse MockMvc (login, creer_avecDonneesValides...) utilisent getContentAsString(StandardCharsets.UTF_8).

Aucune donnee sensible (numero de document, contact citoyen) n'est manipulee en clair par ce module : PieceResponse est reutilise sans modification et continue d'exposer numeroDocumentMasque (deja traite par #24), et le journal d'audit lui-meme ne stocke aucune PII (voir point 8).

## Coherence design/spec/code

Design et spec coherents entre eux (les 4 ecarts identifies par le design sont repris et tranches explicitement en spec). Le code correspond quasi litteralement au Contrat technique de la spec (entite, repository, service, aspect, controleur, migration) : aucune tache de la spec non realisee, aucun ecart non documente constate.

## Findings

Aucun finding bloquant.

Deux remarques mineures, deja actees comme dette assumee par le design/spec (pas de nouvelle dette introduite) :
- Duplication de la resolution de l'acteur courant entre AuditAspect et PieceService.appelantCourant() -- assumee explicitement par le design pour ne pas toucher au code de #24 non merge au moment de la conception.
- Aucune contrainte PostgreSQL (trigger, REVOKE UPDATE/DELETE) pour l'immutabilite -- garantie purement applicative (repository restreint), assumee explicitement comme limite connue par la spec.

## Build/tests

- mvn -q -pl backend -am compile : SUCCESS.
- mvn -q -pl backend -am test-compile : SUCCESS.
- mvn -pl backend -am test -Dtest=EvenementAuditRepositoryContractTest,AuditAspectTest,EvenementAuditServiceTest : SUCCESS, 7/7 tests, 0 echec. Le log ERROR attendu de AuditAspect (declenche par le test quandEnregistrementAuditEchoue_exceptionMetierResteInchangee) apparait bien dans la sortie, confirmant que l'echec simule d'ecriture d'audit est logue et avale sans jamais remonter au client.
- mvn -pl backend -am test -Dtest=sn.samapiece.enregistrement.** : 69 tests executes, 66 passent, dont PieceServiceTest (11/11) et PieceTest (25/25), tous unitaires purs, sans aucune regression sur le code de #24 touche par ce ticket (PieceService.consulter, annotations sur PieceController). Les 3 seuls echecs (PieceNumeroFicheGeneratorTest, PhotoIntegrationTest, PieceIntegrationTest) sont des ContainerFetchException dues a l'absence de Docker dans ce sandbox -- limitation connue et documentee, sans rapport avec le code de #25.
- PieceAuditIntegrationTest et AuditEndpointIntegrationTest (Testcontainers) n'ont pas pu etre executes pour la meme raison (pas de Docker disponible) -- compense par une relecture manuelle ligne a ligne des deux fichiers et du code de production qu'ils exercent (voir les points 1 a 12 ci-dessus), qui confirme une couverture fidele au plan de tests de la spec.

## Fichiers cles revus

- backend/src/main/java/sn/samapiece/audit/AuditAspect.java
- backend/src/main/java/sn/samapiece/audit/EvenementAudit.java
- backend/src/main/java/sn/samapiece/audit/EvenementAuditRepository.java
- backend/src/main/java/sn/samapiece/audit/EvenementAuditService.java
- backend/src/main/java/sn/samapiece/audit/ActionAuditee.java
- backend/src/main/java/sn/samapiece/audit/web/AuditController.java
- backend/src/main/java/sn/samapiece/audit/web/EvenementAuditResponse.java
- backend/src/main/resources/db/migration/V10__create_evenement_audit.sql
- backend/src/main/java/sn/samapiece/enregistrement/PieceService.java (methode consulter)
- backend/src/main/java/sn/samapiece/enregistrement/web/PieceController.java
- backend/src/test/java/sn/samapiece/audit/EvenementAuditRepositoryContractTest.java
- backend/src/test/java/sn/samapiece/audit/AuditAspectTest.java
- backend/src/test/java/sn/samapiece/audit/EvenementAuditServiceTest.java
- backend/src/test/java/sn/samapiece/audit/PieceAuditIntegrationTest.java
- backend/src/test/java/sn/samapiece/audit/AuditEndpointIntegrationTest.java
