# Design #74 - Lister/consulter les pièces en stock

_Rédigé par le bolt-architect (sans outil d'écriture) ; transcrit dans ce fichier par l'orchestrateur. Constats vérifiés : dernière migration = V13 ; statuts comptés par #26 = `disponible`, `reclamee` (`PieceRepository`)._

## Approche
Ajout d'un endpoint paginé `GET /api/v1/pieces` (lecture seule, sur `PieceController`/`PieceService`/`PieceRepository` existants, module `enregistrement`) et d'un onglet frontend « Pièces en stock » qui ouvre la fiche existante (`ConsulterFichePage`/`FichePieceCard`). La liste reprend exactement le filtre du #26 : `statut IN ('disponible','reclamee')` et ancienneté = `CURRENT_DATE - date_depot`, de sorte que le total de la liste par défaut égale le compteur du dashboard. Compromis : le DTO de ligne est volontairement pauvre (pas d'identité du titulaire) ; l'agent doit ouvrir la fiche (auditée) pour voir les données personnelles.

## Fichiers/modules impactés
Backend (`backend/src/main/java/sn/samapiece/enregistrement/`) :
- `PieceRepository.java` : + une méthode `Page<Piece>` en JPQL, `WHERE p.poste.id = :posteId AND p.statut IN :statuts ORDER BY p.dateDepot ASC, p.numeroFiche ASC`. Tri dans la requête ; le `Pageable` ne porte que page et size.
- `PieceService.java` : + `lister(UUID posteId, StatutPiece statut, Pageable)` en `@Transactional(readOnly = true)`, réutilise `appelantCourant()`.
- `web/PieceController.java` : + `@GetMapping` sur la racine.
- `web/PieceListeItemResponse.java` : nouveau record.
- `backend/src/main/resources/db/migration/V14__index_piece_poste_statut_date_depot.sql`.
- Tests liste (contrôleur/service) dans le package de test `enregistrement`, sur le modèle des tests existants. Conventions mémoire : vider `piece_sequence` avant de supprimer les `Poste` dans les `@SpringBootTest` ; `getContentAsString(StandardCharsets.UTF_8)`.

Frontend :
- `frontend/src/features/pieces/PiecesEnStockPage.tsx` (+ `.test.tsx`).
- `piecesApi.ts` : + `listerPieces`. `types.ts` : + `PieceListeItem` et `PageResponse<T>`.
- `frontend/src/shared/layout/AgentShell.tsx` : `OngletAgent` += `'stock'`, un bouton ajouté (voir Risques).
- `frontend/src/app/App.tsx` : `'stock'` dans `ONGLETS_AGENT`, rendu conditionnel, état `pieceAOuvrir`.
- `frontend/src/features/pieces/ConsulterFichePage.tsx` : prop optionnelle `idInitial?: string`.
- `frontend/src/shared/layout/AgentShell.test.tsx` : cas du nouvel onglet.

## Décisions clés
- **Contrat** : `GET /api/v1/pieces?posteId=&statut=&page=0&size=20`.
  - `posteId` optionnel ; par défaut, le poste de l'appelant.
  - `statut` optionnel, valeur d'enum `StatutPiece` (`DISPONIBLE`, etc.). Absent = « en stock », soit DISPONIBLE + RECLAMEE, aligné sur #26. Valeur invalide => 400.
  - `size` = 20 par défaut (`@PageableDefault(size = 20)`), plafonné à 100 dans le service.
  - Tri **fixe** : `dateDepot ASC` (la plus ancienne d'abord) puis `numeroFiche`. Tout `sort=` client est ignoré (pas d'injection de propriétés de tri).
  - Retour : `Page<PieceListeItemResponse>` Spring standard, comme `AuditController`.
- **DTO de ligne** : `id`, `numeroFiche`, `typeDocument`, `statut`, `dateDepot`, `ancienneteJours`, `depasseSeuil` (seuil = `StatistiquesProperties.seuilAncienneteJours`). **Exclus** : nom et prénom du titulaire, date de naissance, numéro de document (même masqué), remarques, `etatDocument`, `agentCreateurId`. `id` reste nécessaire pour le lien vers la fiche. Ne jamais réutiliser `PieceResponse` pour la liste.
- **Périmètre** : `@PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE')")`, comme `consulter` (le détail n'est accessible qu'à ces rôles ; une liste ouverte aux admins mènerait à des 403 sur chaque fiche). Si `posteId` est fourni : charger le poste (`PosteRepository`, sinon `PosteIntrouvableException`) puis `PerimetrePoste.estDansPerimetre` (sinon `AccesRefuseException`), comme `StatistiquesPosteService`. Pour AGENT/CHEF_POSTE, cela revient à leur poste. ADMIN_REGIONAL/ADMIN_NATIONAL hors périmètre.
- **Ancienneté** : calculée en **Java** sur la page, `ChronoUnit.DAYS.between(dateDepot, LocalDate.now())`, équivalent à `CURRENT_DATE - date_depot` de #26 (écart possible seulement si le fuseau JVM diffère de celui de la base autour de minuit ; pas de `Clock` injectable dans ce module, on n'en crée pas). Dépassement : `ancienneteJours > seuil` (strict, comme `compterDepassantSeuil`).
- **Audit** : la liste n'est pas auditée (pas de `@ActionAuditee`) : aucune donnée personnelle exposée, `PIECE_CONSULTEE` est émis à l'ouverture de la fiche, et `AuditAspect` extrait l'id cible d'un `@PathVariable UUID` ou d'un `ResponseEntity`, inadapté à un `Page`.
- **Index** : V14 `CREATE INDEX idx_piece_poste_statut_date_depot ON piece(poste_id, statut, date_depot);` (couvre filtre et tri). `idx_piece_poste_id` devient redondant mais n'est pas supprimé.
- **UI** : bouton « Pièces en stock » (icône `IconDocument` existante) après « Tableau de bord », sans garde de rôle (le contrôle réel est celui de l'endpoint). Select de statut : « En stock (défaut) », Disponible, Réclamée, Retirée, Signalée, Litige, Archivée, Détruite (aligner sur les valeurs réelles de `StatutPiece`). Tableau : N° fiche, type, statut, date de dépôt, ancienneté + badge si `depasseSeuil`. Pagination Précédent/Suivant + « page X/Y » (depuis `totalPages`). États chargement, vide, erreur (`alert-error`). Bouton « Ouvrir la fiche » par ligne.
- **Navigation sans routeur** : `App.tsx` garde `pieceAOuvrir: string | null`. Clic sur une ligne => `setPieceAOuvrir(id); setOnglet('fiche')`. `ConsulterFichePage idInitial={pieceAOuvrir}` pré-remplit l'identifiant et appelle `consulterPiece` au montage (refactor minimal : extraire la logique de `ouvrirFiche` en `charger(id)`). `pieceAOuvrir` repasse à `null` quand on va vers « fiche » via la sidebar (ouverture manuelle inchangée). Pas de retour automatique à la liste : filtre et page réinitialisés au retour (accepté).
- **Cohérence #26** : test d'intégration créant des pièces `disponible`, `reclamee`, `retiree` ; le `totalElements` de la liste par défaut égale `nombrePieces` de `/api/v1/statistiques/poste/{id}`, et le nombre de lignes `depasseSeuil` égale `nombreDepassant`.
- **Tests supplémentaires** : RBAC (403 poste hors périmètre, refus ADMIN_*/AUDITEUR, 401 sans token) ; filtre statut, pagination, plafond de `size` ; absence de `nomTitulaire`, `prenomTitulaire`, `numeroDocumentMasque` dans le JSON ; frontend : rendu, changement de filtre, pagination, clic vers la fiche (mock de `consulterPiece`), états vide et erreur.

## Risques / points d'attention
- **Conflit de merge avec la PR #83 (#73)**, non mergée : elle modifie `AgentShell.tsx` (onglet Agents conditionné par `features/agents/roles.ts`) et possiblement `App.tsx`. Diff localisé : 1 élément dans `OngletAgent`, 1 dans `ONGLETS_AGENT`, 1 `<button>` inséré après « Tableau de bord » et avant le bloc `vue-multi-poste`, 1 ligne de rendu dans `App.tsx`. Ne pas reformater ni réordonner les boutons voisins. Conflit probable sur `OngletAgent`/`ONGLETS_AGENT` : à signaler dans la PR, rebaser après le merge de #83.
- Fuite de données personnelles : voir DTO de ligne.
- Ouverture d'une fiche hors périmètre : déjà couverte par le 403 de `consulter` (qui ne contrôle que le poste propre de l'appelant).
- Offline-first : la liste est online-only (pas de cache PWA), même message « hors connexion » que `ConsulterFichePage`. Les pièces créées hors ligne apparaissent après synchro.
- Forme JSON de `Page` (`content`, `totalElements`, `totalPages`, `number`) et mapping d'un statut invalide en 400 (le handler global existant peut le mapper autrement) : non vérifiés par lecture, à couvrir par test.
- `AgentShell.tsx` est dans `frontend/src/shared/layout/`.

## Hors périmètre
- Accès à la liste pour ADMIN_REGIONAL/ADMIN_NATIONAL, liste multi-postes, recherche par titulaire, tri côté client, export.
- Actions retrait/signalement/déblocage (#63, déjà dans `FichePieceCard`) : on les rend seulement atteignables.
- Routeur (react-router), cache offline de la liste, retour à la liste avec état conservé.
- Modification des requêtes ou compteurs de #26, ajout d'un `Clock`, audit de la liste.
