# Review — #10 Modèle Piece (numéro hashé + masqué) + migration Flyway

APPROVE

## Critères d'acceptation

| Critère | Statut |
|---|---|
| Migration Flyway créant la table `piece` avec toutes les colonnes attendues (type_document, nom/prénom titulaire, numero_document_hash, numero_document_masque, date_naissance, date_depot, etat_document, statut, remarques, poste_id, agent_createur_id) | Couvert — `backend/src/main/resources/db/migration/V4__create_piece.sql` reprend colonne pour colonne (+ `numero_document_sel`, `id`, `cree_le`, `maj_le`) le contrat exact de `spec.md`, avec FK vers `poste(id)`/`agent(id)`, `CHECK` sur `type_document`/`statut`, et les 3 index prescrits. Pas de contrainte `UNIQUE` sur `numero_document_hash` (correct, vu le sel par ligne). |
| Le hash utilise un sel unique par enregistrement ; le champ masqué n'affiche jamais plus de 4 caractères en clair | Couvert — `NumeroDocumentHasher` génère un sel `SecureRandom` 16 octets (32 hex) par appel, hash SHA-256(sel+numeroClair) en 64 hex. `NumeroDocumentHasherTest.hacher_shouldGenererUnSelDifferentAChaqueAppel_memePourLeMemeNumero` vérifie deux sels/hash distincts pour le même numéro. Masquage : seuil `<= 4` (masquage complet), sinon 2 premiers + 2 derniers clairs max, testé pour longueurs 1, 3, 4, 5, 10 avec assertions sur la valeur exacte (pas seulement le compte de caractères). |
| Enum de statut : DISPONIBLE, RECLAMEE, RETIREE, LITIGE, ARCHIVEE, DETRUITE, SIGNALEE | Couvert — `StatutPiece` contient exactement ces 7 valeurs, synchronisées en minuscules avec le `CHECK` SQL via `StatutPieceConverter`. `PieceTest.nouvellePiece_shouldAvoirStatutDisponibleParDefaut` vérifie le statut initial forcé à `DISPONIBLE` dans le constructeur (non paramétrable). |
| Test unitaire vérifiant qu'aucun numéro en clair n'est persistable directement (champ clair absent en base) | Couvert — `PieceTest.piece_shouldNeJamaisExposerLeNumeroEnClair` (réflexion sur tous les champs `String`, comparaison à un numéro clair de test connu localement) + `PieceTest.piece_shouldAvoirUnSeulConstructeurPublicNAcceptantAucunNumeroBrutCandidat` (un seul constructeur public, signature figée sans `String` unique candidat à un numéro brut). `Piece` n'a aucun setter, aucun champ, aucun constructeur acceptant un numéro brut — seul le triplet hash/sel/masqué déjà calculé est accepté. |

## Points critiques vérifiés

1. `Piece` — un seul constructeur public (`Piece.class.getConstructors().length == 1`), prenant le triplet déjà calculé ; constructeur protégé sans arguments pour JPA. Aucun setter. Conforme à la spec et au design.
2. `NumeroDocumentHasher` — aucune dépendance JPA/`Piece` ; `@Component` Spring simple. Sel `SecureRandom` statique partagé (thread-safe par contrat JDK), 16 octets → 32 hex. Hash SHA-256(sel + numéro) → 64 hex. Deux appels avec le même numéro clair produisent des sels et hash différents (testé).
3. Masquage — seuil correct `<= 4` (pas `< 4`) : longueur 4 → masquage complet (`●●●●`), longueur 5 → `12●45` (1 seul caractère masqué, 4 clairs), longueur 10 → `12` + 6×`●` + `90` (4 clairs). Conforme à l'algorithme imposé par la spec.
4. Schéma SQL — colonnes, types, `CHECK` (type_document 8 valeurs minuscules, statut 7 valeurs minuscules), FK vers `poste`/`agent`, 3 index (`poste_id`, `agent_createur_id`, `statut`) : conformes au contrat exact de `spec.md`. Pas de contrainte `UNIQUE` sur `numero_document_hash` (correctement absente). Pas de trigger `maj_le`, cohérent avec V1/V2/V3.
5. `StatutPiece` — exactement 7 valeurs, converter minuscule cohérent avec le `CHECK` SQL.
6. `PieceTest` par réflexion — itère sur tous les champs `String` déclarés (`nomTitulaire`, `prenomTitulaire`, `numeroDocumentHash`, `numeroDocumentSel`, `numeroDocumentMasque`, `etatDocument`, `remarques`) et compare chaque valeur au numéro clair de test ; vérifie aussi l'absence d'un champ nommé `numeroDocument`. Test réellement exigeant, pas une simple vérification de noms.
7. Aucun fichier existant modifié — le diff `bolt/issue-8-rbac..HEAD` (hors `docs/`) ne contient que des fichiers nouveaux dans `sn.samapiece.enregistrement` + `V4__create_piece.sql`. Aucune régression de surface introduite.

Cohérence avec §10 de `PROJET-SAMAPIECE.md` : le numéro de document n'est jamais stocké en clair (seulement hash salé + masqué partiel), conforme aux principes de minimisation et à l'exigence de masquage partiel côté recherche publique (§7.2).

Cohérence avec `spec.md` : le code correspond quasi littéralement au contrat technique fourni (mêmes noms de colonnes, mêmes signatures, même algorithme de masquage/hachage). Aucune tâche de la checklist non réalisée. Aucun setter ajouté au-delà du périmètre. `TypeDocument`/`TypeDocumentConverter` bien conservés comme enum Java (décision tranchée de la spec), pas réduits à un `VARCHAR` libre.

## Findings

Aucun finding bloquant. Rien à signaler à ce stade — le code est une implémentation fidèle et complète de la spec, avec des tests qui échoueraient effectivement si le comportement attendu était retiré (sel non unique, seuil de masquage incorrect, statut non forcé à `DISPONIBLE`, ajout d'un champ/constructeur exposant un numéro en clair).

## Build/tests

- `mvn -q -pl backend -am test -Dtest=NumeroDocumentHasherTest,PieceTest` → succès, aucune sortie d'erreur. Rapports Surefire confirmés :
  - `NumeroDocumentHasherTest` : Tests run: 9, Failures: 0, Errors: 0
  - `PieceTest` : Tests run: 3, Failures: 0, Errors: 0
  - Total 12/12, conforme à l'affirmation du codeur.
- `mvn -q -pl backend -am test` (suite complète) → `Tests run: 32, Failures: 0, Errors: 5`. Les 5 erreurs (`SamaPieceApplicationTests`, `AgentAdminIntegrationTest`, `AgentIntegrationTest`, `AuthIntegrationTest`, `PosteIntegrationTest`) sont toutes `ContainerFetchException: Can't get Docker image` (Testcontainers, absence de Docker dans l'environnement de review) — limitation préexistante et connue, non liée à ce ticket, aucune régression nouvelle introduite par le diff de #10.
