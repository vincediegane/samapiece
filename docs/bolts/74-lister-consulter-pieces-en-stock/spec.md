# Spec #74 - Lister/consulter les pièces en stock

## Résumé
Un endpoint paginé `GET /api/v1/pieces` (AGENT/CHEF_POSTE, poste de l'appelant, défaut = DISPONIBLE + RECLAMEE comme le dashboard #26) et un onglet frontend « Pièces en stock » listant ces pièces avec ouverture de la fiche détaillée existante.

## Vérifications faites dans le code (à ne pas re-explorer)
- Migrations existantes : V1 à V13 sans trou (dernière = `V13__unicite_region_poste.sql`) -> nouvelle migration = **V14**.
- `StatutPiece` (`backend/src/main/java/sn/samapiece/enregistrement/StatutPiece.java`) : `DISPONIBLE, RECLAMEE, RETIREE, LITIGE, ARCHIVEE, DETRUITE, SIGNALEE`. En JSON (entrée/sortie Jackson) et en query param : nom exact en MAJUSCULES. En base : minuscules (`disponible`, `reclamee`, ...) via `StatutPieceConverter` (`@Convert` sur `Piece.statut`, `toLowerCase()` à l'écriture, `toUpperCase()` à la lecture ; CHECK SQL en minuscules dans V4).
- Les requêtes natives de #26 (`agregerStockParPoste`, `compterDepassantSeuil`) comparent en dur `'disponible','reclamee'` : équivalent exact de `{DISPONIBLE, RECLAMEE}`. La JPQL de la liste doit passer par l'enum (`p.statut IN :statuts` avec `Collection<StatutPiece>`) : Hibernate applique le converter au paramètre lié, aucune minuscule à écrire côté Java. Ne PAS écrire de littéral `'disponible'` dans la JPQL.
- Champs de `Piece` : `id` (UUID), `numeroFiche`, `poste` (ManyToOne LAZY, colonne `poste_id`), `typeDocument` (`TypeDocument`, converter), `statut`, `dateDepot` (`LocalDate`). JPQL : `p.poste.id`, `p.dateDepot`, `p.numeroFiche` (pas de jointure, `poste` n'est jamais initialisé dans le DTO).
- Statut invalide -> 400 : aucun `@ExceptionHandler` du repo ne traite `MethodArgumentTypeMismatchException` (les advices : `PieceExceptionHandler`, `AgentAdminExceptionHandler` (global, gère aussi `IllegalArgumentException` -> 400 `REQUETE_INVALIDE`), `AlerteExceptionHandler`, `Photo...`, `Auth...`, etc. ; `ReferentielExceptionHandler` est limité à `sn.samapiece.referentiel.web`). `MethodArgumentTypeMismatchException` n'étend pas `IllegalArgumentException` : elle est donc résolue par `DefaultHandlerExceptionResolver` -> `sendError(400)`, et `SecurityConfig` a `/error` en `permitAll` (correctif #60) : le 400 n'est pas réécrit en 401. Corps = réponse `/error` standard de Boot (**pas** de champ `code`). Ne pas ajouter de handler ; le test vérifie le statut 400 seulement. Le converter String -> enum de Spring est sensible à la casse : `statut=disponible` -> 400.
- Erreurs de périmètre : `PosteIntrouvableException` -> 404 `{"code":"POSTE_INTROUVABLE",...}` ; `AccesRefuseException` -> 403 `{"code":"ACCES_REFUSE","message":"Acces refuse."}` (tous deux dans `AgentAdminExceptionHandler`, global). Rôle non autorisé (`@PreAuthorize`) -> 403 ; sans jeton -> 401 (`HttpStatusEntryPoint`).
- Pageable : `AuditController` utilise `@PageableDefault(size = 20, ...)`, résolu par `PageableHandlerMethodArgumentResolver` (page < 0 ou non numérique -> défaut 0 ; size < 1 -> défaut ; size > 2000 -> ramené à 2000 ; `sort=` parsé). Pour la liste : on ne transmet jamais le `Sort` du client, le service reconstruit `PageRequest.of(page, min(size, 100))` sans tri ; le tri vient de la JPQL (`ORDER BY`).
- Forme JSON de `Page` (Spring Boot 3.3.13 / Spring Data 3.3, `PageImpl` sérialisé tel quel comme `AuditController`, un WARN de log Spring Data est attendu et déjà présent pour l'audit) : `content[]`, `pageable{...}`, `totalElements`, `totalPages`, `last`, `first`, `size`, `number`, `numberOfElements`, `sort`, `empty`. Le frontend ne lit que `content`, `totalElements`, `totalPages`, `number`, `size`.
- Fichiers frontend réels : `frontend/src/shared/layout/AgentShell.tsx` (`OngletAgent` ligne 21), `frontend/src/app/App.tsx` (`ONGLETS_AGENT` ligne 19), `frontend/src/features/pieces/{ConsulterFichePage,piecesApi,types}.ts(x)`. `types.ts` contient déjà `StatutPiece`, `STATUT_PIECE_LABELS` (LITIGE = « En litige », pas « Litige »), `STATUT_PIECE_COULEURS`, `TYPE_DOCUMENT_LABELS` : les réutiliser.

## Tâches

### Backend
- [ ] 1. Créer `backend/src/main/resources/db/migration/V14__index_piece_poste_statut_date_depot.sql` : `CREATE INDEX idx_piece_poste_statut_date_depot ON piece(poste_id, statut, date_depot);` (ne pas toucher `idx_piece_poste_id`).
- [ ] 2. Créer `backend/src/main/java/sn/samapiece/enregistrement/web/PieceListeItemResponse.java` (record, voir contrat) avec factory statique `of(Piece piece, long ancienneteJours, boolean depasseSeuil)`. Ne pas réutiliser `PieceResponse`.
- [ ] 3. Modifier `backend/src/main/java/sn/samapiece/enregistrement/PieceRepository.java` : ajouter la méthode JPQL paginée (voir contrat). Imports `Page`, `Pageable`.
- [ ] 4. Modifier `backend/src/main/java/sn/samapiece/enregistrement/PieceService.java` : injecter `PosteRepository` (`sn.samapiece.referentiel`) et `StatistiquesProperties` (`sn.samapiece.reporting`) dans le constructeur (ajouter en fin de liste de paramètres), ajouter `lister(UUID posteId, StatutPiece statut, Pageable pageable)` `@Transactional(readOnly = true)`.
- [ ] 5. Modifier `backend/src/main/java/sn/samapiece/enregistrement/web/PieceController.java` : ajouter `@GetMapping` (racine, sans `{id}`) avec `@PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE')")`, **sans** `@ActionAuditee`. Placer la méthode avant `consulter`.
- [ ] 6. Mettre à jour `backend/src/test/java/sn/samapiece/enregistrement/PieceServiceTest.java` : le constructeur `new PieceService(...)` (ligne ~50) change de signature (seul appel du constructeur dans `src/`) ; ajouter les mocks `PosteRepository` et `StatistiquesProperties` (ou instance réelle avec le seuil par défaut 180) et les tests unitaires de `lister` (voir plan de tests).
- [ ] 7. Créer `backend/src/test/java/sn/samapiece/enregistrement/web/PieceListeIntegrationTest.java` (`@SpringBootTest @AutoConfigureMockMvc @Testcontainers`, modèle : `backend/src/test/java/sn/samapiece/reporting/StatistiquesPosteIntegrationTest.java`). Nettoyage `@BeforeEach` : `pieceRepository.deleteAll()`, **`jdbcTemplate.update("DELETE FROM piece_sequence")` avant** `agentRepository.deleteAll()` / `posteRepository.deleteAll()` / `regionRepository.deleteAll()` ; toujours `getContentAsString(StandardCharsets.UTF_8)`.

### Frontend
- [ ] 8. Modifier `frontend/src/features/pieces/types.ts` : ajouter `PieceListeItem` et `PageResponse<T>` (voir contrat).
- [ ] 9. Modifier `frontend/src/features/pieces/piecesApi.ts` : ajouter `listerPieces(params)` (voir contrat), même style que `consulterPiece` (`PieceApiError`, 401/403/404/autre).
- [ ] 10. Modifier `frontend/src/features/pieces/ConsulterFichePage.tsx` : prop optionnelle `idInitial?: string` (refactor minimal, voir contrat).
- [ ] 11. Créer `frontend/src/features/pieces/PiecesEnStockPage.tsx` : props `{ onOuvrirFiche: (id: string) => void }`.
- [ ] 12. Modifier `frontend/src/shared/layout/AgentShell.tsx` (diff minimal, voir section Conflit PR #83).
- [ ] 13. Modifier `frontend/src/app/App.tsx` (diff minimal, voir section Conflit PR #83).
- [ ] 14. Tests frontend : créer `frontend/src/features/pieces/PiecesEnStockPage.test.tsx` ; compléter `ConsulterFichePage.test.tsx` (cas `idInitial`) ; compléter `frontend/src/shared/layout/AgentShell.test.tsx` (onglet) ; compléter `frontend/src/features/pieces/piecesApi.test.ts` (`listerPieces`) ; compléter `frontend/src/app/App.test.tsx` (parcours liste -> fiche, mêmes mocks que le test existant « affiche ConsulterFichePage lors de la navigation vers l'onglet fiche »).

## Contrat technique

### Endpoint
`GET /api/v1/pieces?posteId={uuid}&statut={StatutPiece}&page={n}&size={n}`

| Param | Requis | Règle |
|---|---|---|
| `posteId` | non | absent -> `appelant.getPoste()`. Présent -> `posteRepository.findById` sinon `PosteIntrouvableException` (404) ; puis `PerimetrePoste.estDansPerimetre(appelant, poste)` sinon `AccesRefuseException` (403). Même ordre que `StatistiquesPosteService.consulter`. UUID mal formé -> 400 (défaut Spring). |
| `statut` | non | valeur exacte de l'enum en majuscules. Absent -> `List.of(DISPONIBLE, RECLAMEE)`. Présent -> `List.of(statut)`. Invalide/minuscule -> 400. |
| `page` | non | défaut 0 |
| `size` | non | défaut 20 (`@PageableDefault(size = 20)`), plafonné à 100 dans le service |

- `@PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE')")`. ADMIN_REGIONAL, ADMIN_NATIONAL, AUDITEUR -> 403. Sans jeton -> 401.
- Signature contrôleur : `public Page<PieceListeItemResponse> lister(@RequestParam(required = false) UUID posteId, @RequestParam(required = false) StatutPiece statut, @PageableDefault(size = 20) Pageable pageable)` ; retourne `pieceService.lister(posteId, statut, pageable)` (le type de retour `Page` direct, comme `AuditController`, pas `ResponseEntity`). Statut HTTP 200.
- Non audité.

### Repository
```java
@Query("""
        SELECT p FROM Piece p
        WHERE p.poste.id = :posteId AND p.statut IN :statuts
        ORDER BY p.dateDepot ASC, p.numeroFiche ASC
        """)
Page<Piece> findByPosteEtStatuts(
        @Param("posteId") UUID posteId,
        @Param("statuts") Collection<StatutPiece> statuts,
        Pageable pageable);
```
Ajouter un `countQuery` explicite : `SELECT COUNT(p) FROM Piece p WHERE p.poste.id = :posteId AND p.statut IN :statuts`. `Collection` est déjà importé.

### Service
```java
@Transactional(readOnly = true)
public Page<PieceListeItemResponse> lister(UUID posteId, StatutPiece statut, Pageable pageable)
```
1. `Agent appelant = appelantCourant();`
2. Résoudre l'id du poste (règles du tableau ci-dessus).
3. `Pageable borne = PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), 100));` (jamais de `Sort`).
4. `LocalDate aujourdHui = LocalDate.now();` une seule fois par appel ; pour chaque pièce : `long anciennete = ChronoUnit.DAYS.between(p.getDateDepot(), aujourdHui);` `boolean depasse = (p.getStatut() == DISPONIBLE || p.getStatut() == RECLAMEE) && anciennete > seuil` avec `seuil = statistiquesProperties.getSeuilAncienneteJours()`. (Précision vs design : `depasseSeuil` n'est vrai que pour les statuts « en stock », pour que le décompte sur la liste par défaut égale `nombreDepassant` de #26 et qu'un badge n'apparaisse pas sur une pièce retirée ; sur la liste par défaut le comportement est identique au design.)
5. `page.map(...)`.

### DTO
```java
public record PieceListeItemResponse(
        UUID id, String numeroFiche, TypeDocument typeDocument, StatutPiece statut,
        LocalDate dateDepot, long ancienneteJours, boolean depasseSeuil) {}
```
Exemple de réponse (`GET /api/v1/pieces?size=2`) :
```json
{
  "content": [
    {
      "id": "3f0c2c7e-6a52-4d0b-9d5e-1b5f0f6c9a11",
      "numeroFiche": "PC-ABCDEF01-2026-00001",
      "typeDocument": "CNI",
      "statut": "DISPONIBLE",
      "dateDepot": "2026-01-15",
      "ancienneteJours": 257,
      "depasseSeuil": true
    },
    {
      "id": "8a1d7b90-2e34-4c6f-b1a8-5d2e7c4f0b22",
      "numeroFiche": "PC-ABCDEF01-2026-00002",
      "typeDocument": "PASSEPORT",
      "statut": "RECLAMEE",
      "dateDepot": "2026-09-01",
      "ancienneteJours": 28,
      "depasseSeuil": false
    }
  ],
  "pageable": { "pageNumber": 0, "pageSize": 2, "sort": { "sorted": false, "unsorted": true, "empty": true }, "offset": 0, "paged": true, "unpaged": false },
  "totalElements": 2,
  "totalPages": 1,
  "last": true,
  "first": true,
  "size": 2,
  "number": 0,
  "numberOfElements": 2,
  "sort": { "sorted": false, "unsorted": true, "empty": true },
  "empty": false
}
```
Clés interdites dans le JSON : `nomTitulaire`, `prenomTitulaire`, `numeroDocumentMasque`, `dateNaissanceTitulaire`, `remarques`, `etatDocument`, `agentCreateurId`, `posteId`.

### Frontend
`types.ts` :
```ts
export interface PieceListeItem {
  id: string; numeroFiche: string; typeDocument: TypeDocument; statut: StatutPiece;
  dateDepot: string; ancienneteJours: number; depasseSeuil: boolean;
}
export interface PageResponse<T> {
  content: T[]; totalElements: number; totalPages: number; number: number; size: number;
}
```
`piecesApi.ts` : `listerPieces(params: { statut?: StatutPiece; page: number; size?: number }): Promise<PageResponse<PieceListeItem>>` -> `GET /api/v1/pieces?page=..&size=..[&statut=..]` (via `URLSearchParams`, `statut` omis pour le défaut, `posteId` jamais envoyé). Messages : 401 « Session expirée, reconnectez-vous. », 403 « Accès refusé à la liste des pièces. », autre `Erreur ${status}`.

`PiecesEnStockPage.tsx` :
- Titre `<h1 className="page-title">Pièces en stock</h1>` ; `<select>` labellisé « Statut » : option vide « En stock (disponibles et réclamées) » (défaut) puis les 7 statuts avec `STATUT_PIECE_LABELS`. Changement de statut -> page 0.
- Tableau : colonnes N° fiche, Type (`TYPE_DOCUMENT_LABELS[typeDocument]`), Statut (`STATUT_PIECE_LABELS`), Date de dépôt, Ancienneté (`{n} j` + badge « Dépasse le seuil » si `depasseSeuil`), bouton « Ouvrir la fiche » par ligne -> `onOuvrirFiche(id)`.
- Pagination : boutons « Précédent » (désactivé si `number === 0`) / « Suivant » (désactivé si `number + 1 >= totalPages`) et texte « Page {number+1} / {max(totalPages,1)} » ; affiche aussi `totalElements`.
- États : chargement (« Chargement… »), vide (« Aucune pièce en stock. »), erreur (`<p role="alert" className="alert-error">`) ; hors connexion (`PieceApiError` absent ou `!navigator.onLine`) : « Action impossible hors connexion. Réessayez une fois la connexion rétablie. » (même logique que `ConsulterFichePage`).

`ConsulterFichePage.tsx` (refactor minimal) :
- signature `function ConsulterFichePage({ idInitial }: { idInitial?: string })` ; `useState(idInitial ?? '')` pour `identifiant`.
- extraire le corps de `ouvrirFiche` après `preventDefault()` en `async function charger(id: string)` (validation regex, `consulterPiece(id.trim())`, rôle agent, gestion d'erreur inchangés) ; `ouvrirFiche(e)` = `e.preventDefault(); await charger(identifiant);`.
- `useEffect(() => { if (idInitial) void charger(idInitial); }, [])` (au montage uniquement, désactiver la règle exhaustive-deps sur cette ligne si le lint l'exige). En dev StrictMode l'appel est doublé (lecture seule, sans impact prod).
- Sans `idInitial` : comportement strictement inchangé (tests existants verts).

## Conflit de merge avec la PR #83 (#73) : diff minimal exigé
La PR #83 (non mergée) touche `frontend/src/shared/layout/AgentShell.tsx` (onglet Agents conditionné par `features/agents/roles.ts`) et possiblement `frontend/src/app/App.tsx`. Ne rien reformater, ne pas réordonner ni renommer les boutons/imports voisins. Modifications autorisées, et seulement celles-là :

`AgentShell.tsx` (3 emplacements) :
1. ligne 21 : ajouter `| 'stock'` à `OngletAgent` (`'pieces' | 'fiche' | 'dashboard' | 'stock' | 'agents' ...` : insérer juste après `'dashboard'`).
2. insérer, **entre** le `</button>` « Tableau de bord » (ligne 125) et le bloc `{agent && peutVoirVueMultiPoste(...)` (ligne 126), un seul bloc :
```tsx
          <button
            type="button"
            className={actif === 'stock' ? 'sidebar-link-active' : 'sidebar-link'}
            onClick={() => onNaviguer('stock')}
          >
            <IconDocument width={18} height={18} />
            Pièces en stock
          </button>
```
   Aucun nouvel import (`IconDocument` est déjà importé). Sans garde de rôle.

`App.tsx` (4 emplacements) :
1. ligne 19 : ajouter `'stock'` à `ONGLETS_AGENT` (après `'dashboard'`).
2. dans `App()`, sous `const [onglet, setOnglet] = ...` : `const [pieceAOuvrir, setPieceAOuvrir] = useState<string | null>(null);` et
```tsx
  function naviguerAgent(cible: OngletAgent) {
    if (cible === 'fiche') setPieceAOuvrir(null);
    setOnglet(cible);
  }
```
3. dans `<AgentShell ...>` : remplacer uniquement `onNaviguer={setOnglet}` par `onNaviguer={naviguerAgent}`.
4. rendu : remplacer la ligne `{onglet === 'fiche' && <ConsulterFichePage />}` par
```tsx
        {onglet === 'fiche' && (
          <ConsulterFichePage key={pieceAOuvrir ?? 'manuel'} idInitial={pieceAOuvrir ?? undefined} />
        )}
```
   et insérer juste après la ligne `dashboard` :
```tsx
        {onglet === 'stock' && (
          <PiecesEnStockPage onOuvrirFiche={(id) => { setPieceAOuvrir(id); setOnglet('fiche'); }} />
        )}
```
   + un import `import PiecesEnStockPage from '../features/pieces/PiecesEnStockPage';` placé juste après l'import de `ConsulterFichePage` (ligne 8).
Conflits probables lignes 19 et 21 (union de tableaux/types) : à signaler dans la PR ; rebaser sur `main` après le merge de #83 et résoudre en conservant les deux ajouts.

## Plan de tests

| Critère d'acceptation | Test(s) |
|---|---|
| AC1 endpoint paginé, filtre statut, restreint au poste | `PieceListeIntegrationTest` : `lister_commeAgent_shouldRetournerSeulementPiecesDuPosteDefautEnStock` (DISPONIBLE+RECLAMEE, exclut RETIREE et pièces d'un autre poste) ; `lister_avecStatutRetiree_shouldFiltrer` ; `lister_pagination_shouldRespecterPageEtSize` (3 pièces, size=2 -> `totalPages=2`, page 1 = 1 élément) ; `lister_sizeSuperieurA100_shouldEtrePlafonne` (size=500 -> `$.size` = 100) ; `lister_ordreParDateDepotPuisNumeroFiche` (ancienne d'abord, `sort=numeroFiche,desc` client ignoré) ; `lister_statutInvalide_shouldRetourner400` (`statut=NIMPORTEQUOI` et `statut=disponible`) ; `lister_commeChefPoste_shouldRetourner200` |
| AC1 rôles ayant accès au poste | `lister_posteIdAutrePoste_shouldRetourner403` (`ACCES_REFUSE`) ; `lister_posteIdInconnu_shouldRetourner404` (`POSTE_INTROUVABLE`) ; `lister_posteIdEgalPostePropre_shouldRetourner200` ; `lister_commeAdminRegional_AdminNational_Auditeur_shouldRetourner403` (`@ParameterizedTest` sur les 3 rôles) ; `lister_sansJeton_shouldRetourner401` |
| AC1 pas de fuite de données personnelles | `lister_reponse_shouldNePasContenirDonneesPersonnelles` : pièce avec nom « Diop », prénom « Awa », remarque ; le JSON brut (`getContentAsString(StandardCharsets.UTF_8)`) ne contient aucune des clés interdites ni « Diop » |
| AC4 cohérence #26 | `lister_totalElementsEgalNombrePiecesEnAttenteDuDashboard` : pièces DISPONIBLE (30 j), RECLAMEE (200 j), RETIREE (5 j) ; `GET /api/v1/pieces` `totalElements` == `nombrePiecesEnAttente` de `GET /api/v1/statistiques/poste/{id}` ; le nombre d'éléments `depasseSeuil == true` == `nombrePiecesDepassantSeuil` ; `ancienneteJours` == `ChronoUnit.DAYS.between(dateDepot, LocalDate.now())` |
| AC1 logique service | `PieceServiceTest` (Mockito) : `lister_sansPosteId_utilisePosteAppelant` ; `lister_posteIdHorsPerimetre_throwAccesRefuse` ; `lister_posteInconnu_throwPosteIntrouvable` ; `lister_sansStatut_passeDisponibleEtReclamee` (ArgumentCaptor sur la collection) ; `lister_sizeSuperieurA100_borneA100` et `lister_ignoreLeSortDuPageable` (le `Pageable` capturé n'a pas de tri) ; `lister_depasseSeuilFalsePourPieceRetiree` |
| AC2 écran frontend + navigation agent | `AgentShell.test.tsx` : bouton « Pièces en stock » présent (rôle AGENT) et clic -> `onNaviguer('stock')` ; `PiecesEnStockPage.test.tsx` : rend N° fiche / type / statut / ancienneté d'après un `listerPieces` mocké, badge « Dépasse le seuil » si `depasseSeuil`, changement de filtre -> `listerPieces` rappelé avec `statut` et `page: 0`, pagination Suivant/Précédent (`page` 1 puis 0, boutons désactivés aux bornes), états vide, chargement, erreur (`role="alert"`) ; `piecesApi.test.ts` : URL construite (`statut` omis par défaut, présent sinon), mapping 401/403 |
| AC3 accès à la fiche depuis la liste | `PiecesEnStockPage.test.tsx` : clic « Ouvrir la fiche » -> `onOuvrirFiche(id)` ; `ConsulterFichePage.test.tsx` : avec `idInitial` valide -> `consulterPiece` appelé avec cet id au montage et `FichePieceCard` affichée, sans `idInitial` -> pas d'appel ; `App.test.tsx` : onglet « Pièces en stock » -> clic ligne -> `consulterPiece` appelé et fiche affichée ; puis clic sidebar « Ouvrir une fiche » -> champ UUID vide (`pieceAOuvrir` remis à `null`) |
| AC4 (côté UI) | Manuel : sur un poste de démo, comparer le compteur « pièces en attente » du dashboard et le total affiché par la liste par défaut |
| Migration V14 | Couverte par le démarrage Flyway de tout `@SpringBootTest` Testcontainers (Postgres 16) ; pas de test dédié |

Commandes de validation : `mvn -f backend/pom.xml test` ; `npm --prefix frontend run test` et `npm --prefix frontend run lint`/`build` (typecheck).

## Écarts identifiés
1. **Dépendance de package cyclique** : `PieceService` (`enregistrement`) devra importer `sn.samapiece.reporting.StatistiquesProperties`, alors que `reporting` importe déjà `enregistrement.PieceRepository`. Aucun Spring Modulith/ArchUnit dans le repo (vérifié), donc non bloquant ; on injecte quand même le bean existant plutôt que de dupliquer la propriété `samapiece.reporting.seuil-anciennete-jours`. À signaler au reviewer.
2. **`PieceServiceTest` à adapter** : le design ne le mentionne pas ; le constructeur `PieceService` change (2 nouvelles dépendances), le test existant ne compile plus sans mise à jour (tâche 6).
3. **`depasseSeuil` sur statuts hors stock** : le design le définit par `ancienneteJours > seuil` sans condition de statut ; précisé ci-dessus (limité à DISPONIBLE/RECLAMEE) pour rester cohérent avec #26 quand l'utilisateur filtre p. ex. RETIREE. Aucun impact sur la liste par défaut.
4. **Corps de la réponse 400 (statut invalide)** : format `/error` de Boot, pas `{code,message}` comme les erreurs métier ; le design signalait ce point comme non vérifié, il est tranché (400 garanti, pas de `code`).
5. **Libellé du filtre** : le design écrit « Litige » ; le libellé réel du repo est `STATUT_PIECE_LABELS.LITIGE = 'En litige'` (on réutilise la constante).
6. **Critère « restreint aux rôles ayant accès au poste »** : interprété comme AGENT/CHEF_POSTE sur leur poste uniquement (ADMIN_* exclus, cohérent avec `consulter`) ; à confirmer côté produit si les admins doivent aussi lister (hors périmètre du design).
7. **Écart pré-existant non traité** : `PieceService.consulter` n'autorise que le poste propre de l'appelant (pas `PerimetrePoste`) ; sans conséquence ici puisque la liste est limitée au même périmètre.
8. **Ticket AC2 « ancienneté »** : fournie en jours (`ancienneteJours`) ; l'affichage « {n} j » est un choix de la spec.
