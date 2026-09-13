# Design — #11 Endpoint API de création d'une fiche pièce

## Approche

On ajoute au package existant `sn.samapiece.enregistrement` (déjà peuplé par #10 avec `Piece`,
`NumeroDocumentHasher`, les enums/converters) la couche persistance + service + web manquante :
`PieceRepository` (Spring Data), un petit composant `PieceNumeroFicheGenerator` dédié à la génération
atomique du numéro de fiche, `PieceService` (orchestration, calqué sur le patron déjà établi par
`AgentAdminService`), et `PieceController` + DTOs (calqués sur `AgentAdminController`). Le numéro de
fiche est garanti unique par un compteur persistant `piece_sequence(poste_id, annee)` incrémenté via un
`INSERT ... ON CONFLICT ... DO UPDATE ... RETURNING` exécuté dans la même transaction que l'insertion
de la `Piece` : Postgres sérialise les accès concurrents sur la ligne du compteur (verrou de ligne), donc
deux créations simultanées pour le même poste/année ne peuvent jamais obtenir le même numéro, et un
rollback de la création annule aussi l'incrément (pas de numéro brûlé en cas d'échec technique, au prix
de trous possibles seulement si l'écriture échoue après un commit partiel, cas non applicable ici
puisque tout est dans une seule transaction). C'est le compromis le plus simple qui reste correct sous
concurrence, sans introduire de séquence SQL dynamique par poste ni de verrou applicatif explicite.

## Fichiers/modules impactés

Nouveaux :
- backend/src/main/resources/db/migration/V5__create_piece_sequence_et_numero_fiche.sql : table
  piece_sequence + colonne numero_fiche sur piece.
- backend/src/main/java/sn/samapiece/enregistrement/PieceRepository.java : JpaRepository<Piece, UUID>
  (aucune méthode de requête supplémentaire nécessaire pour ce ticket).
- backend/src/main/java/sn/samapiece/enregistrement/PieceNumeroFicheGenerator.java : @Component,
  utilise JdbcTemplate pour l'upsert atomique et compose la chaîne PC-<posteCourt>-<année>-<séquence>.
- backend/src/main/java/sn/samapiece/enregistrement/PieceService.java : orchestration (résolution de
  l'appelant, hachage, génération du numéro, construction et sauvegarde de Piece).
- backend/src/main/java/sn/samapiece/enregistrement/web/CreerPieceRequest.java : DTO requête (Bean
  Validation).
- backend/src/main/java/sn/samapiece/enregistrement/web/PieceResponse.java : DTO réponse.
- backend/src/main/java/sn/samapiece/enregistrement/web/PieceController.java : POST /api/v1/pieces.
- backend/src/test/java/sn/samapiece/enregistrement/web/PieceIntegrationTest.java : tests d'intégration
  (Testcontainers + MockMvc, même patron que AgentAdminIntegrationTest) : création valide, champs
  manquants (400), rôle non autorisé (403).
- backend/src/test/java/sn/samapiece/enregistrement/PieceNumeroFicheGeneratorTest.java : test
  d'intégration ciblé sur l'unicité sous accès concurrents (deux threads/deux transactions sur le même
  poste+année ne doivent jamais produire le même numéro).

Modifiés :
- backend/src/main/java/sn/samapiece/enregistrement/Piece.java : le constructeur unique existant gagne
  un paramètre numeroFiche (nouveau premier paramètre, voir Décisions clés #2) + colonne/champ/getter
  associés. Aucun setter ajouté, l'immutabilité de #10 est préservée.
- backend/src/test/java/sn/samapiece/enregistrement/PieceTest.java : tous les appels au constructeur de
  Piece doivent être mis à jour avec le nouveau paramètre ; l'assertion "un seul constructeur public"
  reste vraie (signature étendue, pas de constructeur additionnel).

Non modifiés (référencés en lecture seule) : sn.samapiece.referentiel.Poste (pas de champ code
ajouté, voir Décisions clés #1), sn.samapiece.iam.Agent/AgentRepository, sn.samapiece.iam.Role,
SecurityConfig (aucune règle à ajouter : /api/v1/pieces n'est pas dans la liste permitAll, donc déjà
authenticated() par défaut ; @PreAuthorize fait le reste, comme pour /api/v1/agents).

## Contrat JSON

Requête POST /api/v1/pieces :
```json
{
  "typeDocument": "CNI",
  "nomTitulaire": "Diop",
  "prenomTitulaire": "Awa",
  "numeroDocument": "1234567890123",
  "dateNaissanceTitulaire": "1990-05-12",
  "dateDepot": "2026-09-13",
  "etatDocument": "bon état",
  "remarques": "trouvée sur la voie publique"
}
```
posteId et agentCreateurId sont absents intentionnellement du contrat : déduits de l'agent
authentifié (appelant.getPoste() / appelant). numeroDocument est en clair uniquement dans la requête,
jamais renvoyé, jamais stocké tel quel (haché immédiatement par NumeroDocumentHasher, patron de #10).
Champs obligatoires : typeDocument, nomTitulaire, prenomTitulaire, numeroDocument, dateDepot.
Nullable : dateNaissanceTitulaire, etatDocument, remarques.

Réponse 201 Created :
```json
{
  "id": "5b6c...",
  "numeroFiche": "PC-3F2A9C1B-2026-00001",
  "posteId": "b2e1...",
  "agentCreateurId": "9a01...",
  "typeDocument": "CNI",
  "nomTitulaire": "Diop",
  "prenomTitulaire": "Awa",
  "numeroDocumentMasque": "12######23",
  "dateNaissanceTitulaire": "1990-05-12",
  "dateDepot": "2026-09-13",
  "etatDocument": "bon état",
  "statut": "DISPONIBLE",
  "remarques": "trouvée sur la voie publique",
  "creeLe": "2026-09-13T10:15:30Z"
}
```
Jamais de numeroDocumentHash/numeroDocumentSel dans la réponse (mêmes garanties de minimisation que #10).
Note : le caractère de masquage réellement utilisé par NumeroDocumentHasher est le point noir unicode "●",
pas "#" (simplifié ci-dessus pour la lisibilité de l'exemple).

## Décisions clés

1. Segment poste du numéro de fiche = 8 premiers caractères hexadécimaux de l'UUID du poste
   (sans tirets, majuscules), pas un nouveau champ code sur Poste. Poste (#5) n'a aujourd'hui que
   nom (texte libre, ex. Commissariat Central Dakar) : aucun champ court exploitable. Ajouter un
   code obligerait à changer le constructeur de Poste et donc tous ses appelants existants
   (AgentAdminService, PosteController, AgentAdminIntegrationTest, PosteIntegrationTest,
   potentiellement le frontend), un rayon d'impact largement hors du périmètre d'un ticket endpoint de
   création de pièce. Prix payé : le segment n'est pas lisible/mémorisable par un agent (ex.
   3F2A9C1B plutôt que DKR-01) ; acceptable pour un numéro de fiche destiné à être scanné/collé sur un
   reçu (#14), pas dicté oralement. Un vrai code de poste lisible reste une amélioration possible via un
   ticket dédié au module referentiel, pas ici.
2. Le constructeur unique de Piece est étendu avec numeroFiche en premier paramètre, plutôt que
   d'ajouter un second constructeur public. #10 a délibérément verrouillé "un seul constructeur public"
   par un test de réflexion (PieceTest) : ajouter un second constructeur casserait cette garantie
   d'invariant. Le numéro de fiche est connu avant la construction de l'entité (généré par
   PieceNumeroFicheGenerator en amont, dans le même service), donc il peut être un paramètre obligatoire
   du constructeur comme le triplet hash/sel/masqué.
3. Génération du numéro par upsert atomique sur une table compteur dédiée (piece_sequence), pas par
   séquence SQL Postgres ni par SELECT ... FOR UPDATE explicite. Une SEQUENCE Postgres native ne
   peut pas être créée dynamiquement par poste/année sans DDL à la volée (à exclure). Un
   SELECT ... FOR UPDATE suivi d'un UPDATE demande deux allers-retours et une gestion manuelle du cas
   ligne absente (premier dépôt de l'année pour ce poste) ; l'upsert INSERT ... ON CONFLICT DO UPDATE
   ... RETURNING fait les deux en un seul aller-retour atomique et gère nativement la création de la
   ligne compteur au premier dépôt. Exécuté via JdbcTemplate dans la même transaction Spring
   (@Transactional sur PieceService.creer) que l'INSERT de Piece : les deux écritures committent ou
   rollback ensemble.
4. L'année du numéro de fiche est celle de dateDepot (champ métier fourni par le client), pas
   l'année de l'horloge serveur. dateDepot est la date qui a un sens métier pour la fiche (et sera
   celle affichée sur le reçu de #14) ; utiliser l'horloge serveur créerait une incohérence si une fiche
   est saisie en léger différé (ressaisie après reconnexion, pertinent pour l'offline-first du §11.1)
   avec une dateDepot antérieure au jour de saisie réelle.
5. Aucun nouveau @RestControllerAdvice pour ce ticket. Trois cas d'erreur possibles :
   - Bean Validation (@Valid sur CreerPieceRequest) : 400 géré nativement par Spring Boot (comme pour
     CreerAgentRequest, qui n'a pas de handler dédié pour MethodArgumentNotValidException non plus).
   - Rôle non autorisé : 403 géré par @PreAuthorize/Spring Security, comme /api/v1/agents.
   - Agent appelant introuvable/désactivé (token valide mais compte supprimé/désactivé entre-temps) :
     réutilisation de sn.samapiece.iam.AccesRefuseException, déjà mappée en 403 par
     AgentAdminExceptionHandler (@RestControllerAdvice global, pas limité au package iam.web). Créer
     un handler dédié à enregistrement.web dupliquerait ce mapping sans bénéfice.
6. PieceService duplique une petite méthode privée appelantCourant() (même logique que
   AgentAdminService.appelantCourant() : SecurityContextHolder puis AgentRepository.findByMatricule puis
   vérification isActif()) plutôt que d'extraire un composant partagé dans iam. Avec seulement deux
   occurrences, extraire une abstraction commune maintenant serait prématuré (cohérent avec la posture
   pas de sur-ingénierie déjà tenue en #10) ; à reconsidérer si un troisième endpoint répète le même
   besoin.

## Migration V5 (schéma proposé)

```sql
ALTER TABLE piece ADD COLUMN numero_fiche VARCHAR(50) NOT NULL;
ALTER TABLE piece ADD CONSTRAINT uq_piece_numero_fiche UNIQUE (numero_fiche);

CREATE TABLE piece_sequence (
    poste_id       UUID NOT NULL REFERENCES poste(id),
    annee          INTEGER NOT NULL,
    dernier_numero INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (poste_id, annee)
);
```
ADD COLUMN ... NOT NULL sans DEFAULT est sûr ici car la table piece est vide à ce stade du projet
(aucun chemin de code ne l'alimente avant ce ticket : #10 n'a livré que l'entité). À vérifier par le
codeur avant d'exécuter la migration sur un environnement où des données de test manuelles auraient pu
être insérées.

## Risques / points d'attention

- Table piece supposée vide avant V5 (cf. ci-dessus) : si ce n'est pas le cas dans un environnement
  donné, la migration NOT NULL sans backfill échouera ; prévoir un DEFAULT temporaire ou un backfill
  explicite si le spec-writer confirme des données existantes.
- Segment de poste non lisible (Décision clé #1) : accepté pour ce ticket, mais à signaler
  explicitement si le futur reçu PDF (#14) ou un usage terrain révèle un besoin réel de code de poste
  lisible ; impliquerait alors une migration sur Poste hors périmètre ici.
- Trous de séquence en cas d'échec après l'upsert ne sont pas un risque réel ici (tout est dans une
  seule transaction Spring) ; en revanche, si un futur refactoring déplace la génération du numéro dans
  une transaction séparée de l'insertion de Piece, l'atomicité décrite au point 3 des Décisions clés
  serait rompue : point d'attention pour le codeur.
- Extension du constructeur de Piece touche un fichier livré et review-approuvé par #10 : le codeur
  doit mettre à jour PieceTest (tous les appels du constructeur) sans affaiblir les assertions
  existantes (statut forcé à DISPONIBLE, un seul constructeur public, aucun champ ne porte le numéro en
  clair).
- Cohérence RBAC : l'ensemble de rôles AGENT/CHEF_POSTE autorisé ici est disjoint de celui
  d'/api/v1/agents (CHEF_POSTE/ADMIN_REGIONAL/ADMIN_NATIONAL) ; volontaire (un agent de terrain
  crée des fiches, un admin régional/national ne le fait normalement pas), mais à confirmer explicitement
  par le spec-writer si un profil ADMIN doit aussi pouvoir créer une fiche en pratique (dépannage).
- Test de concurrence (PieceNumeroFicheGeneratorTest) nécessite Docker/Testcontainers comme le reste
  de la suite d'intégration ; le mécanisme de verrouillage de ligne Postgres ne peut pas être vérifié par
  un test purement unitaire/mocké ; accepter que ce test soit lent et dépende de threads réels lançant
  des transactions concurrentes.

## Hors périmètre

- Détection de doublons par numéro de document (§7.1 de PROJET-SAMAPIECE.md) : non demandée par les
  critères d'acceptation de ce ticket ; nécessiterait un balayage/comparaison par sel (cf. risque déjà
  documenté dans docs/bolts/10-piece-model/design.md) : traitement futur dédié.
- Génération du reçu PDF (#14) : ce ticket garantit seulement que numero_fiche est une colonne stable
  et durable, pas la génération du document.
- Upload/floutage des photos du document (PHOTO du §9.1) : hors périmètre, pas dans les critères
  d'acceptation.
- Queue de synchronisation offline-first côté frontend (§7.1, §11.1) : ce ticket ne livre qu'un endpoint
  REST synchrone côté backend ; l'intégration offline (IndexedDB, retry) est un sujet frontend séparé.
- Listing/consultation des fiches (GET /api/v1/pieces) et scoping par poste/région à la lecture : pas
  demandé par ce ticket, qui ne couvre que la création.
- Ajout d'un champ code lisible sur Poste : évoqué en Décisions clés #1 mais explicitement écarté du
  périmètre de ce ticket.
