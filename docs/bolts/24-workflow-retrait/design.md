# Design — Ticket #24 : Workflow de retrait avec double vérification d'identité

## Approche

Le workflow se traite entièrement dans le module `enregistrement` existant, sans nouveau module. Trois actions viennent compléter `PieceService`/`PieceController` : `retirer` (transition vers `RETIREE`), `signaler` (transition vers `LITIGE`/`SIGNALEE`) et `debloquer` (retour à `DISPONIBLE`, réservé à un rôle habilité). Les informations de retrait (réclamant, pièce justificative, agent validateur, date) sont persistées dans une **nouvelle table `retrait`** dédiée (insert-only, un enregistrement par tentative de retrait réussie), conformément au modèle de données de référence (§9.1, entité `RETRAIT`) — pas de table d'audit générique puisque le ticket #25 n'est pas encore livré. Les informations de signalement/déblocage (motif, auteur, date), moins critiques légalement que le retrait lui-même, sont stockées comme **colonnes nullables directement sur `piece`** (état courant, sans historisation complète) pour limiter la surface du changement ; ce choix sacrifie l'historique des signalements successifs, acceptable tant que le ticket #25 (journal d'audit immuable) n'est pas livré. Bonne nouvelle constatée à l'exploration : tous les statuts requis (`RETIREE`, `LITIGE`, `ARCHIVEE`, `SIGNALEE`) existent déjà dans `StatutPiece` et dans la contrainte `CHECK` SQL de `V4__create_piece.sql` — **aucune migration d'extension d'enum n'est nécessaire**, seulement une migration additive (nouvelle table + nouvelles colonnes).

## Fichiers/modules impactés

Backend (`backend/src/main/java/sn/samapiece/enregistrement/`) :
- `Piece.java` — ajouter des méthodes métier sans setter générique (pattern déjà suivi par `Agent.desactiver()`/`modifierInformations()`) : `retirer(...)`, `signaler(StatutPiece, String motif, Agent)`, `debloquer(String motif, Agent)`, avec vérifications de transition internes ; ajout des colonnes `signale_par_id`, `signale_le`, `motif_signalement`, `debloque_par_id`, `debloque_le`, `motif_deblocage`.
- `PieceService.java` — nouvelles méthodes `retirer(UUID pieceId, RetraitRequest)`, `signaler(UUID pieceId, SignalerRequest)`, `debloquer(UUID pieceId, DeblocageRequest)`, chacune : résout `appelantCourant()` (pattern existant), charge la `Piece` via `PieceRepository.findById` (sinon `PieceIntrouvableException`, déjà gérée globalement par `PhotoExceptionHandler`), vérifie le périmètre poste, applique la transition.
- `PieceRepository.java` — inchangé a priori (pas de nouvelle requête nécessaire pour ces AC).
- Nouveau `Retrait.java` (entité), `RetraitRepository.java` — table `retrait` : `id`, `piece_id` (FK, non unique — un déblocage peut permettre un nouveau retrait), `agent_validateur_id` (FK `agent`), `nom_reclamant`, `piece_justificative_presentee`, `date_retrait`, `cree_le`.
- Nouvelles exceptions dans `enregistrement/` : `TransitionStatutInterditeException` (409, réutilisée pour retrait bloqué et signalement bloqué), portant le statut courant et l'action refusée.
- `web/PieceController.java` — 3 nouveaux endpoints (voir ci-dessous).
- Nouveaux `web/RetraitRequest.java`, `web/SignalerRequest.java`, `web/DeblocageRequest.java`, `web/RetraitResponse.java` (ou réutilisation de `PieceResponse`).
- Nouveau `web/PieceExceptionHandler.java` (`@RestControllerAdvice`) pour `TransitionStatutInterditeException` → 409. Ne pas redéclarer de handler pour `PieceIntrouvableException` : `PhotoExceptionHandler` le fait déjà globalement (un second `@ExceptionHandler` sur le même type casserait le démarrage Spring).
- `backend/src/main/resources/db/migration/V9__create_retrait_et_signalement.sql` — création table `retrait` + `ALTER TABLE piece ADD COLUMN` pour les 6 colonnes signalement/déblocage (toutes nullables, pas de modification de la contrainte `CHECK` existante sur `statut`).

Tests (structure à suivre par le codeur) :
- `backend/src/test/java/sn/samapiece/enregistrement/PieceServiceTest.java` — étendre (retrait valide, retrait sur statut bloqué, signalement, déblocage, contrôle de périmètre).
- `backend/src/test/java/sn/samapiece/enregistrement/web/PieceIntegrationTest.java` — étendre : retrait valide (200), retrait sur pièce déjà `RETIREE` (409), signalement bloquant un retrait ultérieur (409), déblocage puis retrait (200).

Frontend : aucun fichier de `frontend/src/features/pieces/` n'est requis par les critères d'acceptation (ils ne portent que sur l'API). Pas d'écran de retrait/signalement existant actuellement — cf. Hors périmètre.

## Endpoints REST

- `POST /api/v1/pieces/{id}/retrait` — `@PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE')")`, périmètre = même poste que l'appelant. Corps : `{ nomReclamant, pieceJustificativePresentee, commentaire? }`. Précondition : `statut` ∈ {`DISPONIBLE`, `RECLAMEE`}, sinon 409. Effet : crée une ligne `retrait`, passe `statut` à `RETIREE`. Retour : 200 avec `PieceResponse` mis à jour (ou `RetraitResponse` dédié).
- `POST /api/v1/pieces/{id}/signaler` — `@PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE')")`, même périmètre. Corps : `{ statutCible: "LITIGE"|"SIGNALEE", motif }` (motif obligatoire). Précondition : `statut` ∈ {`DISPONIBLE`, `RECLAMEE`}, sinon 409. Effet : renseigne `signale_par_id`/`signale_le`/`motif_signalement`, passe `statut` à la valeur demandée. Retour : 200.
- `POST /api/v1/pieces/{id}/debloquer` — `@PreAuthorize("hasAnyRole('CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')")`, périmètre via `PerimetrePoste.estDansPerimetre`. Corps : `{ motif }` (obligatoire). Précondition : `statut` ∈ {`RETIREE`, `ARCHIVEE`, `LITIGE`, `SIGNALEE`}, sinon 409. Effet : renseigne `debloque_par_id`/`debloque_le`/`motif_deblocage`, repasse `statut` à `DISPONIBLE`. Retour : 200.

## Décisions clés

1. **RBAC retrait/signalement** : identique au rôle qui crée les fiches (`AGENT`, `CHEF_POSTE`), avec un contrôle de périmètre poste direct (`appelant.getPoste().getId().equals(piece.getPoste().getId())`, même pattern que `PhotoService.uploader`) — pas besoin de `PerimetrePoste`/périmètre régional ici puisque `ADMIN_*` n'intervient pas dans ces deux actions.
2. **Déblocage réservé à un rôle supérieur** : nouveau rôle habilité = `CHEF_POSTE`, `ADMIN_REGIONAL`, `ADMIN_NATIONAL`, même liste que `AgentAdminController`. Motif obligatoire, périmètre vérifié via `PerimetrePoste.estDansPerimetre` (couvre le cas régional/national). Le déblocage remet le statut à `DISPONIBLE`, ce qui permet ensuite un retrait normal.
3. **Distinction LITIGE / SIGNALEE** : `LITIGE` = contestation entre deux réclamants ou doute sur l'identité du réclamant lors d'un retrait en cours (cf. §8.2 "deux personnes réclament la même pièce", §7.5 double vérification pour cas sensibles). `SIGNALEE` = suspicion de fraude sur la fiche elle-même (ex. numéro de document falsifié, cf. §8.2 dernière ligne du tableau) ; le §8.2 mentionne aussi l'exclusion de la recherche publique pour ce statut, mais **cette exclusion n'est pas demandée par les AC du ticket #24** et n'est donc pas traitée ici (voir Hors périmètre).
4. **Interprétation de "double vérification d'identité" (ambiguïté à confirmer par le spec-writer)** : le titre du ticket va au-delà des AC détaillées. Interprétation retenue — une double déclaration actée par l'agent, sans OCR/scan (hors scope technique probable) : (a) le nom du réclamant saisi par l'agent est destiné à être rapproché du `nomTitulaire`/`prenomTitulaire` enregistrés sur la `Piece`, mais en cas de non-concordance le retrait **n'est pas bloqué automatiquement** (cas légitimes : nom d'épouse, homonymie partielle) — l'information saisie est simplement conservée telle quelle dans `retrait.nom_reclamant` pour traçabilité, la décision finale restant à l'agent ; (b) une pièce justificative alternative présentée physiquement par le réclamant est décrite en texte libre par l'agent dans `retrait.piece_justificative_presentee` (ex. "Carte d'électeur n° ... vérifiée visuellement"), sans vérification automatisée. La "validation par un second agent" pour cas sensibles (§7.5) n'est pas implémentée : `agent_validateur_id` capture un agent unique. **Point ouvert explicite pour le spec-writer** : confirmer si un rejet strict (409/422) doit avoir lieu en cas de non-concordance de nom, ou si une simple alerte visuelle côté agent suffit.
5. **Pas de migration d'extension d'enum** : contrairement à l'hypothèse d'exploration initiale, `RETIREE`, `LITIGE`, `ARCHIVEE`, `SIGNALEE` existent déjà dans `StatutPiece` et dans le `CHECK` SQL de V4. La migration V9 est purement additive.
6. **AC2 vs AC3, incohérence mineure tranchée** : AC2 ne liste que `RETIREE`, `ARCHIVEE`, `LITIGE` comme statuts bloquant le retrait sans déblocage, mais AC3 indique que le signalement doit aussi bloquer le retrait. Décision : traiter `SIGNALEE` comme les trois autres — tout statut différent de `DISPONIBLE`/`RECLAMEE` bloque le retrait et exige un déblocage préalable. À confirmer par le spec-writer.

## Risques / points d'attention

- **Statut `RECLAMEE` jamais positionné actuellement** : rien dans le code existant ne fait transiter une `Piece` de `DISPONIBLE` vers `RECLAMEE` (probablement prévu pour un ticket futur). Le workflow de retrait doit accepter `DISPONIBLE` comme statut nominal, et `RECLAMEE` par anticipation sans certitude qu'il sera atteignable en pratique aujourd'hui.
- **Conflit `@RestControllerAdvice`** : vérifier que le nouveau `PieceExceptionHandler` ne redéclare pas `@ExceptionHandler(PieceIntrouvableException.class)`, déjà pris en charge globalement par `PhotoExceptionHandler` — un doublon casserait le démarrage de l'application.
- **Historique du signalement non conservé** : stocker l'état de signalement/déblocage comme colonnes mutables sur `piece` plutôt que dans une table d'événements fait qu'un second signalement écrase les traces du premier. Dette assumée en attendant le ticket #25, à signaler au reviewer.
- **Concurrence** : deux requêtes de retrait simultanées sur la même pièce (double clic, deux agents) doivent être protégées par la relecture du statut dans la transaction avant transition ; ne pas introduire de verrou optimiste (`@Version`) dans ce ticket sauf nécessité avérée en test.
- **Minimisation des données** : `nom_reclamant` et `piece_justificative_presentee` sont des données personnelles nouvelles conservées sur `retrait` — cohérent avec §9.2 (colonnes d'audit sur tables sensibles), durée de conservation alignée sur le cycle de vie de la `Piece`.
- **Offline-first** : ce ticket ne touche pas le frontend agent PWA ; un futur écran de retrait devra prévoir la mise en queue locale IndexedDB comme pour la création de fiche (§7.1) — non traité ici.

## Hors périmètre

- Toute interface frontend (écran de retrait, de signalement ou de déblocage dans `frontend/src/features/pieces/`) — les AC ne demandent que l'API REST.
- OCR/scan automatique de la pièce justificative présentée par le réclamant.
- Validation par un second agent pour les cas sensibles (mentionnée au §7.5 comme option) — seul un agent validateur unique est capturé dans ce ticket.
- Exclusion automatique des pièces `SIGNALEE` de la recherche publique (mentionnée au §8.2) — dépend du module `recherche`, non demandée par les AC du ticket #24.
- Journal d'audit générique/immuable (`EVENEMENT_AUDIT`) — c'est le ticket #25.
- Signature électronique/émargement numérique du réclamant (mentionnée au §7.5) — non demandée par les AC.
- Notification SMS de restitution au déposant (mentionnée au §7.3/§8.1) — dépend du module `notification`, non demandée par les AC de ce ticket.
