# Review — #11 Endpoint API de création d'une fiche pièce

APPROVE

## Critères d'acceptation

| Critère | Statut |
|---|---|
| `POST /api/v1/pieces` réservé aux rôles AGENT/CHEF_POSTE, valide les champs obligatoires, hash le numéro de document côté serveur | Couvert |
| Génère un numéro de fiche unique (`PC-<poste>-<année>-<séquence>`) retourné dans la réponse | Couvert |
| `poste_id`/`agent_createur_id` déduits du contexte d'authentification, jamais du payload client | Couvert |
| Tests d'intégration : création valide, champs manquants (400), rôle non autorisé (403) | Couvert |

## Analyse détaillée

Diff isolé avec `git diff bolt/issue-10-piece-model..HEAD` (5 commits, 14 fichiers, +1363/-0). Code de
production comparé ligne à ligne au contrat technique de `spec.md` : correspondance exacte pour
`Piece.java`, `PieceNumeroFicheGenerator`, `PieceService`, `PieceRepository`, `CreerPieceRequest`,
`PieceResponse`, `PieceController` et la migration `V5`.

1. **`Piece.java`** : `numeroFiche` ajouté en premier paramètre du constructeur unique (aucun second
   constructeur public), getter sans setter, colonne `numero_fiche` `NOT NULL UNIQUE`. `PieceTest` a bien
   les 3 assertions d'origine intactes (statut `DISPONIBLE` par défaut, aucun champ `String` n'expose le
   numéro en clair, un seul constructeur public) — seule la liste de types de l'assertion de réflexion a
   été étendue avec `String.class` en tête, conforme au contrat.

2. **`CreerPieceRequest`** : vérifié directement dans le fichier (pas seulement via le test) — le record
   ne porte que `typeDocument, nomTitulaire, prenomTitulaire, numeroDocument, dateNaissanceTitulaire,
   dateDepot, etatDocument, remarques`. Aucun `posteId`/`agentCreateurId` : impossible à injecter par le
   client quel que soit le payload, ce qui garantit structurellement le 3e critère.

3. **`PieceService.creer`** : `poste`/`agentCreateur` proviennent exclusivement de
   `appelantCourant()` → `appelant.getPoste()`, jamais d'un champ de la requête. `appelantCourant()` est
   une duplication fidèle (byte pour byte) du bloc `AgentAdminService.appelantCourant()`, y compris le
   contrôle `isActif()` levant `AccesRefuseException` (mappée globalement par
   `AgentAdminExceptionHandler`, un `@RestControllerAdvice` non restreint à un package).

4. **`PieceNumeroFicheGenerator`** : SQL `INSERT ... ON CONFLICT (poste_id, annee) DO UPDATE SET
   dernier_numero = piece_sequence.dernier_numero + 1 RETURNING dernier_numero` conforme au contrat
   (upsert atomique, un seul aller-retour SQL, correction sémantique OK : primo-insertion à 1, incréments
   suivants par le upsert). Format `PC-%s-%d-%05d` avec segment hex du `posteId` tronqué à 8 caractères
   majuscules, année tirée de `dateDepot.getYear()` (jamais l'horloge serveur) — conforme.

5. **Hash côté serveur** : `numeroDocument` en clair n'est jamais journalisé ni renvoyé. `PieceResponse`
   n'expose que `numeroDocumentMasque` ; `numeroDocumentHash`/`numeroDocumentSel`/`numeroDocument` sont
   absents du DTO de réponse (vérifié dans le code, pas seulement dans le test).

6. **RBAC** : `@PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE')")` sur `PieceController.creer`, ensemble
   bien disjoint du RBAC `/api/v1/agents` (`CHEF_POSTE`/`ADMIN_REGIONAL`/`ADMIN_NATIONAL`). Pas de règle
   `permitAll` ajoutée dans `SecurityConfig` pour `/api/v1/pieces` : l'endpoint retombe sur
   `anyRequest().authenticated()` puis `@PreAuthorize`, comme documenté dans le contrat.

7. **Test de concurrence** (`PieceNumeroFicheGeneratorTest`) : utilise un vrai `ExecutorService` à 2
   threads synchronisés par `CyclicBarrier(2)` pour maximiser le chevauchement des deux appels
   `genererNumeroFiche` sur le même `poste_id`/année, puis vérifie que les deux résultats sont distincts
   et valent respectivement `00001`/`00002`. Ce n'est pas un test séquentiel déguisé — il prouve
   effectivement l'absence de collision sous accès concurrent réel au niveau SQL (upsert atomique côté
   Postgres).

`PieceIntegrationTest` (386 lignes) suit fidèlement le plan de tests de `spec.md` : 201 pour
AGENT/CHEF_POSTE, 403 pour ADMIN_REGIONAL/ADMIN_NATIONAL/AUDITEUR, 400 pour chacun des 5 champs
obligatoires retirés un par un, vérification du hash côté serveur à la fois côté JSON
(`jsonPath(...).doesNotExist()` sur `numeroDocumentHash`/`numeroDocumentSel`/`numeroDocument`, et
`numeroDocumentMasque` ne contient pas le numéro en clair) et côté base (relecture via
`pieceRepository.findById` : `numeroDocumentHash != clair`, `numeroDocumentSel` non vide), vérification
du format et de l'incrémentation du numéro de fiche, vérification croisée que deux agents de deux postes
différents obtiennent chacun leur propre `posteId`/`agentCreateurId` dans la réponse, et cas de l'agent
désactivé après émission du token (403 `ACCES_REFUSE`).

Migration `V5` : `ALTER TABLE piece ADD COLUMN numero_fiche VARCHAR(50) NOT NULL` suivi d'une contrainte
`UNIQUE` — cohérent avec la tâche de vérification préalable documentée dans `spec.md` (table `piece`
supposée vide avant ce ticket, `#10` n'ayant livré que l'entité sans chemin de code pour l'alimenter).
`piece_sequence` avec clé primaire composite `(poste_id, annee)` et `REFERENCES poste(id)`, cohérent avec
l'upsert du générateur.

Aucune régression détectée sur le code existant (`AgentAdminService`, `SecurityConfig`,
`AgentAdminExceptionHandler` non modifiés par ce diff).

## Build/tests

- `mvn -pl backend -am test-compile` → succès (compilation propre du module et de ses dépendances).
- `mvn -pl backend -am test -Dtest=CreerPieceRequestTest,PieceTest,NumeroDocumentHasherTest` → **13/13
  succès** (CreerPieceRequestTest: 1, PieceTest: 3, NumeroDocumentHasherTest: 9 — confirmé par les
  rapports Surefire, pas seulement par le rapport du codeur).
- `mvn -pl backend -am test -Dtest=PieceIntegrationTest,PieceNumeroFicheGeneratorTest` → échec
  d'environnement, **limitation déjà documentée dans le repo** (commit `ea21556`, tests Testcontainers
  région/poste) : `ContainerFetchException` / `Could not find a valid Docker environment` sur Docker
  Desktop Windows (échec du named-pipe `npipe://./pipe/docker_cli`, code retour 400). Ce n'est pas un
  échec de test dû au code du bolt — les deux fichiers ont été relus intégralement et comparés au plan de
  tests détaillé de `spec.md` (voir section ci-dessus) faute de pouvoir les exécuter dans cet
  environnement.

## Verdict

APPROVE. Le diff propre au ticket #11 (`bolt/issue-10-piece-model..HEAD`) correspond exactement au
contrat technique de `spec.md` : RBAC correct et disjoint des autres endpoints, `poste_id`/
`agent_createur_id` structurellement impossibles à falsifier depuis le payload, hachage serveur sans
fuite de numéro en clair (ni JSON, ni journal, vérifié en base par le test d'intégration), numéro de
fiche généré de façon atomique et testé sous concurrence réelle. Les 13 tests unitaires exécutables dans
cet environnement passent tous ; les tests Testcontainers échouent uniquement pour la raison
d'environnement Windows/Docker déjà connue du projet, pas pour une raison imputable au code de ce bolt.
