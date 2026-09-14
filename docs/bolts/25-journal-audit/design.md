# Design -- #25 Journal d'audit immuable des actions sensibles

## Approche

Nouveau module `sn.samapiece.audit` (bounded context inexistant a ce jour). L'interception se fait via une annotation dediee `@ActionAuditee(action, entiteCible)` posee sur les methodes de controleur ciblees, interceptee par un `@Aspect` Spring AOP en `@Around` (pas de pointcut base sur les noms de package/classe, trop fragile). Le choix de `@Around` plutot que `@AfterReturning`/`@AfterThrowing` separes permet de journaliser aussi bien les succes que les tentatives echouees (acces refuse, transition de statut interdite, piece introuvable) dans un seul point de code, avec un champ `resultat` (SUCCES/ECHEC) dans les details -- pertinent pour la detection de fraude (7.5/10.4 : une tentative de retrait refusee est un signal utile). Cout du choix : nouvelle dependance spring-boot-starter-aop (absente du pom actuel) et un module transverse qui doit rester decouple (pas de dependance de enregistrement/iam vers audit, seulement l'inverse). L'acteur courant et son IP sont resolus dans l'aspect lui-meme (SecurityContextHolder + AgentRepository, HttpServletRequest via RequestContextHolder), au prix d'une petite duplication avec PieceService.appelantCourant() -- assumee pour ne pas toucher au code de #24 non merge.

Comme GET /api/v1/pieces/{id} (consultation complete) n'existe pas encore dans le controleur, ce ticket doit l'ajouter a minima (juste retour de PieceResponse, meme perimetre poste que retirer/signaler) pour que le critere d'acceptation "test verifiant qu'une consultation genere une entree d'audit" soit testable. La table evenement_audit est append-only par convention applicative : le repository n'expose que save/findAll(Pageable)/findById, jamais deleteById/update.

## Fichiers/modules impactes

Backend -- nouveau module backend/src/main/java/sn/samapiece/audit/
- package-info.java
- EvenementAudit.java -- entite JPA, aucun setter, aucune methode de mutation, un seul constructeur complet (id genere via GenerationType.UUID), champs : acteurId (UUID, nullable), typeActeur (String, "AGENT" pour ce ticket), action (String, ex. PIECE_CREEE, PIECE_CONSULTEE, PIECE_RETIREE), entiteCible (String, ex. PIECE), entiteCibleId (UUID nullable, pas de FK -- generique, coherent avec l'ERD 9.1), details (String brut JSON, @JdbcTypeCode(SqlTypes.JSON) + columnDefinition = "jsonb", meme pattern que Poste.horaires), adresseIp (String), horodatage (OffsetDateTime, insertable=false avec DEFAULT now() en colonne, comme Piece.creeLe).
- EvenementAuditRepository.java -- n'etend pas JpaRepository mais org.springframework.data.repository.Repository<EvenementAudit, UUID>, avec seulement EvenementAudit save(EvenementAudit e) et Page<EvenementAudit> findAll(Pageable pageable) declares explicitement (pas de deleteById/delete disponibles du tout sur l'interface -- garantie plus forte qu'un simple "on n'appelle jamais ces methodes").
- EvenementAuditService.java -- enregistrer(...) (appele par l'aspect) et lister(Pageable) (appele par le controleur).
- ActionAuditee.java -- annotation @Retention(RUNTIME) @Target(METHOD), attributs action (String) et entiteCible (String).
- AuditAspect.java -- @Aspect @Component, @Around("@annotation(actionAuditee)"), resout acteur/IP, extrait l'id cible via le premier parametre annote @PathVariable de type UUID (reflection sur MethodSignature), capture succes/exception, appelle EvenementAuditService.enregistrer(...), propage l'exception d'origine si echec (ne doit jamais masquer une erreur metier).
- web/AuditController.java -- GET /api/v1/audit/evenements, @PreAuthorize("hasAnyRole('AUDITEUR','ADMIN_NATIONAL')").
- web/EvenementAuditResponse.java -- DTO de sortie (jamais l'entite directement).

Backend -- fichiers modifies
- backend/pom.xml -- ajout de spring-boot-starter-aop.
- backend/src/main/resources/db/migration/V10__create_evenement_audit.sql (V9 est la derniere version sur cette branche).
- backend/src/main/java/sn/samapiece/enregistrement/web/PieceController.java -- ajout de @ActionAuditee sur creer (action PIECE_CREEE), retirer (PIECE_RETIREE), signaler (PIECE_SIGNALEE) ; ajout d'un nouvel endpoint GET /{id} (consulter, action PIECE_CONSULTEE) avec @PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')") et verification de perimetre poste comme retirer/signaler. debloquer peut aussi recevoir @ActionAuditee (action PIECE_DEBLOQUEE) meme si non explicitement liste dans les criteres, par coherence avec le cycle de vie retrait/audit deja couvert par #24.
- backend/src/main/java/sn/samapiece/enregistrement/PieceService.java -- ajout d'une methode consulter(UUID id) (lecture seule, meme controle de perimetre que retirer), retournant PieceResponse.
- backend/src/main/java/sn/samapiece/config/SecurityConfig.java -- aucune route publique a ajouter (audit et consultation sont deja couverts par anyRequest().authenticated() + @PreAuthorize), mais a verifier que @EnableMethodSecurity reste actif (deja le cas).

Tests (nouveau repertoire backend/src/test/java/sn/samapiece/audit/)
- Test d'integration @SpringBootTest + MockMvc : GET /api/v1/pieces/{id} par un agent authentifie cree bien une ligne evenement_audit (action PIECE_CONSULTEE, entiteCibleId = id de la piece, acteur = agent authentifie) -- verification directe via EvenementAuditRepository/requete SQL, pas seulement via le retour HTTP.
- Test complementaire recommande (non explicitement requis par l'AC mais couvre le risque principal) : un retrait refuse (poste hors perimetre, AccesRefuseException) genere quand meme une entree avec resultat=ECHEC.
- GET /api/v1/audit/evenements : 403 pour un AGENT/CHEF_POSTE, 200 pour AUDITEUR/ADMIN_NATIONAL.
- Attention (memoire projet) : nettoyer piece_sequence avant les Poste si le test cree des Piece ; utiliser getContentAsString(StandardCharsets.UTF_8).

Frontend : hors perimetre (aucune UI d'audit n'est demandee par le ticket ni identifiee dans le repo).

## Decisions cles

1. Annotation + @Aspect @Around, pas de pointcut par package. Explicite sur chaque endpoint concerne, resilient au renommage de classes/packages ; prix : il faut penser a poser l'annotation sur chaque nouvel endpoint sensible futur (PATCH modification, export #14) -- aucun garde-fou automatique ne le rappellera.
2. Consultation complete = ajout d'un GET /api/v1/pieces/{id} minimal dans ce ticket. Cet endpoint n'existe pas sur la branche actuelle malgre sa mention au paragraphe 12 du cahier des charges. Sans lui, le critere d'acceptation "test verifiant qu'une consultation genere une entree d'audit" est intestable. Le perimetre de cet ajout est volontairement minimal (retour de PieceResponse, controle de perimetre poste identique a retirer) -- ce n'est pas une fonctionnalite de consultation enrichie, juste la brique necessaire a #25. Distinct de POST /api/v1/recherche-publique (anonyme, resultats deja minimises, hors perimetre audit).
3. Modification (PATCH /api/v1/pieces/{id}) et export (recu de depot, #14) : non implementes sur cette branche donc non traites ici. L'architecture (annotation + aspect generique) n'a besoin d'aucune modification pour les couvrir plus tard : il suffira de poser @ActionAuditee sur les futurs endpoints. Documente comme hors perimetre explicite plutot que traite par un stub.
4. type_acteur fixe a AGENT pour ce ticket, bien que l'ERD (9.1) prevoie aussi systeme/citoyen. Aucune action systeme ou citoyenne n'est dans le perimetre des criteres d'acceptation actuels ; la colonne reste generique (String) pour ne pas re-migrer plus tard.
5. Repository restreint (Repository<EvenementAudit, UUID> + methodes explicites) plutot que JpaRepository. Plus fort qu'une simple convention "ne jamais appeler delete/save-en-update" : la methode n'existe meme pas sur l'interface compilee. Aucune contrainte DB (trigger, permissions) n'est ajoutee -- juste une garantie applicative, jugee suffisante pour un ticket Spring Boot classique (une contrainte DB stricte est documentee comme option non retenue, voir Risques).
6. @Around unique capturant succes ET echec, avec un champ resultat dans details (JSON) plutot que deux chemins de code separes (@AfterReturning/@AfterThrowing). Permet d'auditer une tentative de retrait refusee (7.5/10.4, detection de fraude) sans dupliquer la logique de resolution acteur/IP.
7. Contenu de details minimal, sans PII dupliquee. Pour respecter la minimisation (10.2), details ne recopie pas nomTitulaire/prenomTitulaire deja presents sur Piece -- seulement des metadonnees techniques (ex. resultat=ECHEC, exception=AccesRefuseException, message="Poste hors perimetre..." ou resultat=SUCCES). entiteCibleId suffit a retrouver la fiche complete pour un auditeur habilite.
8. GET /api/v1/audit/evenements pagine (Pageable), sans filtre dans ce ticket. Premiere utilisation de la pagination Spring Data dans ce repo (les listes existantes, ex. AgentAdminController.lister(), renvoient des List completes) -- necessaire ici car la table grossit indefiniment. Filtres (acteur, action, plage de dates) non ajoutes pour rester au perimetre strict de l'AC ; laisses en point ouvert pour le spec-writer.
9. IP resolue via HttpServletRequest.getRemoteAddr(), comme le fait deja RecherchePubliqueRateLimitFilter/RecherchePubliqueCaptchaFilter. Pas de gestion X-Forwarded-For (aucun indice de reverse-proxy dans docker-compose.yml ; coherent avec l'existant, documente comme risque plutot que traite).

## Risques / points d'attention

- Fiabilite de l'aspect en cas d'exception non prevue. Si EvenementAuditService.enregistrer(...) echoue lui-meme (ex. contrainte DB), l'@Around ne doit pas faire echouer silencieusement l'action metier sous-jacente ni, inversement, masquer une erreur d'audit critique. Decision a documenter explicitement dans la spec : l'ecriture d'audit doit se faire dans la meme transaction que l'action (coherence forte, mais un rollback d'audit annule aussi l'action) ou dans une transaction separee/best-effort (l'action reussit meme si l'audit echoue, mais alors la tracabilite n'est plus garantie a 100% -- contradiction avec "immuable" si des ecritures peuvent silencieusement manquer). C'est un point ouvert important pour le spec-writer, pas tranche ici.
- Extraction de entiteCibleId par reflection. Fonctionne pour les endpoints {id} actuels (UUID en @PathVariable) mais suppose une convention (premier @PathVariable de type UUID) qui devra etre respectee par tout futur endpoint annote -- a documenter comme contrat implicite de @ActionAuditee.
- Duplication de la resolution acteur courant entre AuditAspect et PieceService.appelantCourant() (deux implementations independantes de la meme logique SecurityContextHolder -> AgentRepository). Pas de refactoring vers un composant partage dans ce ticket pour ne pas toucher au code de #24 non merge -- dette mineure a signaler.
- GET /api/v1/pieces/{id} ajoute par ce ticket peut chevaucher un futur ticket dedie a la consultation de fiche. Si un tel ticket existe deja dans le backlog, le spec-writer doit verifier qu'il n'y a pas de double-emploi/conflit de perimetre.
- Pas de contrainte DB anti-update/delete. La garantie "append-only" reste purement applicative (repository restreint). Un acces direct a la base (migration manuelle, script d'admin) pourrait toujours modifier/supprimer une ligne. Une option plus forte (revoke UPDATE/DELETE au niveau du role SQL applicatif, ou trigger BEFORE UPDATE/DELETE levant une exception) est documentee ici comme option non retenue -- hors perimetre d'un ticket applicatif Spring Boot, a evaluer separement si une exigence de conformite CDP l'impose.
- Volume de la table. Aucune politique de purge/archivage n'est definie (la retention des donnees d'audit n'est pas precisee par le ticket) ; a documenter comme dette explicite, coherent avec l'absence de politique de retention deja notee pour alerte (#22).
- IP absente/incorrecte derriere un reverse proxy en production si un load balancer est ajoute plus tard sans propager X-Forwarded-For correctement -- non traite ici (aucune infra proxy visible dans le repo actuel).

## Hors perimetre

- Endpoint de modification de fiche (PATCH /api/v1/pieces/{id}) : n'existe pas encore, non cree par ce ticket.
- Export/recu de depot (ticket #14) : non implemente sur cette branche, aucune interception a prevoir maintenant.
- Filtres de recherche (par acteur/action/date) sur GET /api/v1/audit/evenements : seule la pagination de base est fournie.
- Toute contrainte DB (trigger, permissions restreintes) garantissant l'immutabilite au niveau du moteur PostgreSQL.
- Politique de purge/archivage de evenement_audit.
- Gestion X-Forwarded-For/reverse proxy pour la resolution d'IP.
- Refactoring de PieceService.appelantCourant() vers un composant partage avec AuditAspect.
