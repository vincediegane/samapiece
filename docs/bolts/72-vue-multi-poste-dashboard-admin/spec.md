# Spec #72 - Vue multi-poste du dashboard (ADMIN_REGIONAL / ADMIN_NATIONAL)

## Résumé
Deux endpoints en lecture seule (`GET /api/v1/statistiques/regionale` et `/nationale`) renvoient les statistiques par poste et consolidées (seuil #26 inclus), et un onglet « Vue multi-poste », visible uniquement pour ADMIN_REGIONAL et ADMIN_NATIONAL, les affiche. `DashboardPage` et `GET /poste/{id}` ne sont pas modifiés.

## Tâches

### Backend (`backend/src/main/java/sn/samapiece/`)
- [ ] 1. Créer `reporting/StockParPosteAgrege.java` (interface de projection Spring Data, même style que `StockPosteAgrege`) : voir Contrat, section Projection.
- [ ] 2. Modifier `enregistrement/PieceRepository.java` : ajouter `List<StockParPosteAgrege> agregerStockParPosteNational(@Param("seuilJours") int seuilJours)` et `List<StockParPosteAgrege> agregerStockParPosteRegion(@Param("regionId") UUID regionId, @Param("seuilJours") int seuilJours)` (requêtes natives, voir Contrat). Ne pas toucher `agregerStockParPoste` ni `compterDepassantSeuil`. Ajouter l'import `sn.samapiece.reporting.StockParPosteAgrege`.
- [ ] 3. Créer `reporting/StatistiquesConsolideesResponse.java` : record + records imbriqués `Totaux` et `LignePoste` (voir Contrat).
- [ ] 4. Créer `reporting/StatistiquesConsolideesService.java` (`@Service`, dépendances : `PieceRepository`, `AgentRepository`, `StatistiquesProperties`) avec `@Transactional(readOnly = true)` sur `consulterRegionale()` et `consulterNationale()`. `@Transactional` est requis : `appelant.getPoste().getRegion()` est chargé en lazy. Copier `appelantCourant()` de `StatistiquesPosteService` (matricule via `SecurityContextHolder`, `AccesRefuseException` si introuvable ou inactif) ; ne pas modifier `StatistiquesPosteService`.
- [ ] 5. Dans le service, implémenter le calcul des totaux (voir Contrat, section Totaux) à partir de la liste de projections, sans second appel SQL.
- [ ] 6. Modifier `reporting/web/StatistiquesController.java` : injecter `StatistiquesConsolideesService` (constructeur), ajouter les handlers `GET /regionale` (`@PreAuthorize("hasRole('ADMIN_REGIONAL')")`) et `GET /nationale` (`@PreAuthorize("hasRole('ADMIN_NATIONAL')")`), retour `ResponseEntity<StatistiquesConsolideesResponse>` 200. Déclarer les mappings littéraux : pas de conflit avec `/poste/{id}`.
- [ ] 7. Vérifier `SecurityConfig` : si des règles `requestMatchers` par chemin existent pour `/api/v1/statistiques/**`, s'assurer qu'elles laissent passer les 2 nouveaux chemins (authentifié) ; sinon rien à changer.

### Frontend (`frontend/src/`)
- [ ] 8. Modifier `features/dashboard/types.ts` : ajouter `StatistiquesConsolidees`, `TotauxConsolides`, `LignePosteConsolidee` (voir Contrat). Ne pas modifier `StatistiquesPoste` / `AgentCourant`.
- [ ] 9. Modifier `features/dashboard/dashboardApi.ts` : ajouter `getStatistiquesRegionale()` et `getStatistiquesNationale()` (même `enTeteAutorisation()`, `throw new Error(\`Erreur ${reponse.status}\`)` si `!ok`).
- [ ] 10. Créer `features/dashboard/roles.ts` : `peutVoirVueMultiPoste(role: string | null | undefined): boolean`, vrai pour `ADMIN_REGIONAL` et `ADMIN_NATIONAL` (copie du pattern de `features/audit/roles.ts`, même commentaire « Confort UX uniquement »).
- [ ] 11. Créer `features/dashboard/VueMultiPostePage.tsx` (voir Contrat, section UI).
- [ ] 12. Modifier `shared/layout/AgentShell.tsx` : `OngletAgent` += `'vue-multi-poste'` ; bouton libellé exactement `Vue multi-poste` (icône `IconChart`, comme les autres) affiché si `agent && peutVoirVueMultiPoste(agent.role)`, placé après « Tableau de bord » et avant « Agents ».
- [ ] 13. Modifier `app/App.tsx` : ajouter `'vue-multi-poste'` à `ONGLETS_AGENT`, importer et rendre `{onglet === 'vue-multi-poste' && <VueMultiPostePage />}`.

### Tests
- [ ] 14. Créer `backend/src/test/java/sn/samapiece/reporting/StatistiquesConsolideesIntegrationTest.java` (copier l'infrastructure de `StatistiquesPosteIntegrationTest` : `@SpringBootTest`, `@AutoConfigureMockMvc`, Testcontainers `postgres:16-alpine`, helpers `creerRegion/creerPoste/creerAgentActif/login/creerPiece...`). `@BeforeEach` : `pieceRepository.deleteAll()`, puis `jdbcTemplate.update("DELETE FROM piece_sequence")`, puis agents, postes, régions (ordre FK). Toujours `getContentAsString(StandardCharsets.UTF_8)`. Matricules distincts de ceux du test existant (ex. `PN-2024-01xxx`). Cas listés au Plan de tests.
- [ ] 15. Créer `backend/src/test/java/sn/samapiece/reporting/StatistiquesConsolideesServiceTest.java` (JUnit/Mockito, sans Spring) : totaux, moyenne pondérée, liste vide, `nombrePostesEnDepassement`.
- [ ] 16. Créer `frontend/src/features/dashboard/roles.test.ts` (rôles vrais/faux, `null`/`undefined`).
- [ ] 17. Créer `frontend/src/features/dashboard/VueMultiPostePage.test.tsx` (pattern `DashboardPage.test.tsx` : `vi.stubGlobal('fetch', vi.fn())`, 1er fetch = `/agents/moi`, 2e = statistiques).
- [ ] 18. Modifier `frontend/src/shared/layout/AgentShell.test.tsx` : ajouter les cas « Vue multi-poste » visible/navigation pour ADMIN_REGIONAL et ADMIN_NATIONAL, masqué pour AGENT/CHEF_POSTE/AUDITEUR et rôle non chargé (calqués sur les cas « Référentiel », lignes ~114-170). Aucun autre test existant ne doit changer ; `DashboardPage.test.tsx` intact.
- [ ] 19. Lancer `mvn test` (backend) et `npm test` / `npm run lint` / `npm run build` (frontend).

## Contrat technique

### Endpoints
| Méthode | Chemin | Autorisation | Succès | Erreurs |
|---|---|---|---|---|
| GET | `/api/v1/statistiques/regionale` | `hasRole('ADMIN_REGIONAL')` | 200 | 401 sans jeton ; 403 autres rôles (dont ADMIN_NATIONAL) ; 403 `ACCES_REFUSE` si appelant introuvable/inactif |
| GET | `/api/v1/statistiques/nationale` | `hasRole('ADMIN_NATIONAL')` | 200 | 401 ; 403 autres rôles (dont ADMIN_REGIONAL) |

Aucun paramètre. La région est déduite de `appelant.getPoste().getRegion()` (`poste` et `region` sont `nullable=false` : le cas « appelant sans poste/région » ne peut pas se produire en base ; pas de code défensif dédié, seul le cas appelant introuvable/inactif est géré). Réponse sans données personnelles.

### Réponse (record `StatistiquesConsolideesResponse`)
```java
public record StatistiquesConsolideesResponse(
    String portee,                // "REGIONALE" | "NATIONALE"
    UUID regionId,                // null si NATIONALE
    String regionNom,             // null si NATIONALE
    int seuilAncienneteJours,     // StatistiquesProperties.getSeuilAncienneteJours()
    Totaux totaux,
    List<LignePoste> postes) {

  public record Totaux(
      long nombrePiecesEnAttente,
      Double ancienneteMoyenneJours,      // null si 0 pièce
      Long ancienneteMaxJours,            // null si 0 pièce
      long nombrePiecesDepassantSeuil,
      int nombrePostes,
      int nombrePostesEnDepassement) {}   // postes avec nombrePiecesDepassantSeuil > 0

  public record LignePoste(
      UUID posteId, String posteNom, String regionNom,
      long nombrePiecesEnAttente,
      Double ancienneteMoyenneJours,      // null si 0 pièce
      Long ancienneteMaxJours,            // null si 0 pièce
      long nombrePiecesDepassantSeuil) {}
}
```
Exemple (ADMIN_REGIONAL, région Dakar, seuil 180) :
```json
{
  "portee": "REGIONALE",
  "regionId": "0b7c2f0e-6a63-4c1e-9a52-1d3f0f6a9b11",
  "regionNom": "Dakar",
  "seuilAncienneteJours": 180,
  "totaux": {
    "nombrePiecesEnAttente": 5,
    "ancienneteMoyenneJours": 96.4,
    "ancienneteMaxJours": 200,
    "nombrePiecesDepassantSeuil": 1,
    "nombrePostes": 3,
    "nombrePostesEnDepassement": 1
  },
  "postes": [
    { "posteId": "…", "posteNom": "Commissariat Central Dakar", "regionNom": "Dakar",
      "nombrePiecesEnAttente": 2, "ancienneteMoyenneJours": 115.0, "ancienneteMaxJours": 200,
      "nombrePiecesDepassantSeuil": 1 },
    { "posteId": "…", "posteNom": "Commissariat Pikine", "regionNom": "Dakar",
      "nombrePiecesEnAttente": 3, "ancienneteMoyenneJours": 84.0, "ancienneteMaxJours": 120,
      "nombrePiecesDepassantSeuil": 0 },
    { "posteId": "…", "posteNom": "Gendarmerie Rufisque", "regionNom": "Dakar",
      "nombrePiecesEnAttente": 0, "ancienneteMoyenneJours": null, "ancienneteMaxJours": null,
      "nombrePiecesDepassantSeuil": 0 }
  ]
}
```
En NATIONALE : `regionId` et `regionNom` valent `null`, `regionNom` de chaque ligne est renseigné.

### Projection `StockParPosteAgrege`
```java
public interface StockParPosteAgrege {
    UUID getPosteId();
    String getPosteNom();
    UUID getRegionId();
    String getRegionNom();
    long getNombrePieces();            // COUNT(p.id) : jamais null (0 pour poste vide)
    Long getAncienneteTotaleJours();   // COALESCE(SUM(CURRENT_DATE - p.date_depot), 0)
    Double getAncienneteMoyenneJours();// AVG(...) numeric, null si 0 pièce (même lecture que StockPosteAgrege)
    Long getAncienneteMaxJours();      // MAX(...) integer, null si 0 pièce
    long getNombreDepassant();         // COUNT(p.id) FILTER (WHERE (CURRENT_DATE - p.date_depot) > :seuilJours)
}
```
Mapping PostgreSQL : `count` -> bigint -> `long` ; `avg(integer)` -> numeric -> `Double` (converti par la projection comme l'existant) ; `max`/`sum` sur `date - date` (integer) : `MAX` -> integer, `SUM(integer)` -> bigint, lus en `Long` ; `uuid` -> `UUID`. Utiliser des alias non quotés identiques aux getters (`posteId`, `regionNom`, `nombrePieces`, `ancienneteTotaleJours`, `ancienneteMoyenneJours`, `ancienneteMaxJours`, `nombreDepassant`) comme `agregerStockParPoste` ; le test d'intégration valide le mapping (si un getter revient null à tort, passer les alias en `"..."` quotés). Utiliser `COUNT(p.id)` (pas `COUNT(*)`) pour que le LEFT JOIN renvoie 0 sur un poste vide.

`ancienneteTotaleJours` est un ajout au design : il permet de calculer la moyenne pondérée des totaux exactement (somme / nombre) sans erreur de flottants.

### Requêtes (natives)
```sql
SELECT po.id AS posteId, po.nom AS posteNom, r.id AS regionId, r.nom AS regionNom,
       COUNT(p.id) AS nombrePieces,
       COALESCE(SUM(CURRENT_DATE - p.date_depot), 0) AS ancienneteTotaleJours,
       AVG(CURRENT_DATE - p.date_depot) AS ancienneteMoyenneJours,
       MAX(CURRENT_DATE - p.date_depot) AS ancienneteMaxJours,
       COUNT(p.id) FILTER (WHERE (CURRENT_DATE - p.date_depot) > :seuilJours) AS nombreDepassant
FROM poste po
JOIN region r ON r.id = po.region_id
LEFT JOIN piece p ON p.poste_id = po.id AND p.statut IN ('disponible', 'reclamee')
[WHERE po.region_id = :regionId]          -- uniquement dans la variante région
GROUP BY po.id, po.nom, r.id, r.nom
ORDER BY COUNT(p.id) FILTER (WHERE (CURRENT_DATE - p.date_depot) > :seuilJours) DESC, po.nom ASC, po.id ASC
```
Le filtre statut est dans la clause `ON` (pas dans `WHERE`) pour conserver les postes sans pièce. Tri : dépassants décroissant, puis nom de poste croissant, puis id (déterminisme). Le service ne re-trie pas.

### Totaux (calcul Java)
- `nombrePiecesEnAttente` = somme de `nombrePieces` ; `nombrePiecesDepassantSeuil` = somme de `nombreDepassant`.
- `ancienneteMoyenneJours` = `somme(ancienneteTotaleJours) / nombrePiecesEnAttente` (double), `null` si `nombrePiecesEnAttente == 0`.
- `ancienneteMaxJours` = max des `ancienneteMaxJours` non nuls, `null` si aucun.
- `nombrePostes` = taille de la liste ; `nombrePostesEnDepassement` = nb de lignes avec `nombreDepassant > 0`.
- Liste vide : totaux à 0 / `null`, `postes: []`. Pas d'arrondi (cohérent avec `GET /poste/{id}`).
- Portée régionale : `regionId`/`regionNom` pris sur `appelant.getPoste().getRegion()` (pas sur la liste, qui contient toujours au moins le poste de l'appelant).

### UI `VueMultiPostePage.tsx`
Logique : `recupererAgentCourant()` puis, selon `agent.role` : `ADMIN_REGIONAL` -> `getStatistiquesRegionale()`, `ADMIN_NATIONAL` -> `getStatistiquesNationale()`, autre rôle -> pas d'appel statistiques, état 403.

Types TS : `LignePosteConsolidee { posteId; posteNom; regionNom; nombrePiecesEnAttente; ancienneteMoyenneJours: number|null; ancienneteMaxJours: number|null; nombrePiecesDepassantSeuil }`, `TotauxConsolides { nombrePiecesEnAttente; ancienneteMoyenneJours: number|null; ancienneteMaxJours: number|null; nombrePiecesDepassantSeuil; nombrePostes; nombrePostesEnDepassement }`, `StatistiquesConsolidees { portee: 'REGIONALE'|'NATIONALE'; regionId: string|null; regionNom: string|null; seuilAncienneteJours; totaux; postes }`.

États :
- Chargement : `<p role="status">Chargement des statistiques…</p>` tant que ni données ni erreur.
- Erreur générique (fetch rejeté ou statut autre que 403) : `<p role="alert" className="alert-error">Impossible de charger la vue multi-poste.</p>`.
- 403 (message d'erreur `Erreur 403`, ou rôle non admin) : `<p role="alert" className="alert-error">Accès refusé : cette vue est réservée aux administrateurs régionaux et nationaux.</p>`.
- Vide (`postes.length === 0`) : `<p>Aucun poste dans le périmètre.</p>` à la place du tableau ; les tuiles restent affichées.
- Titre h1 (`page-title`) : `Vue multi-poste — {regionNom}` en REGIONALE ; `Vue multi-poste — National` en NATIONALE ; `Vue multi-poste` pendant chargement/erreur.
- Bannière `role="alert"` `alert-error` avec `IconAlertTriangle` si `totaux.nombrePiecesDepassantSeuil > 0` : `{n} pièce(s) dépassent le seuil de {seuil} jours` (mêmes mots que `DashboardPage`), suivie de `dans {k} poste(s)`.
- Tuiles (`stat-tile`) : Postes, Pièces en attente, Ancienneté moyenne (`—` si null), Ancienneté maximale (`—` si null), avec `sr-only` comme `DashboardPage`.
- Tableau `<table>` avec `<caption className="sr-only">` : colonnes Poste, [Région, seulement si `portee === 'NATIONALE'`], Pièces en attente, Ancienneté moyenne, Ancienneté max, Pièces au-delà du seuil. Valeurs null affichées `—`. Ligne avec `nombrePiecesDepassantSeuil > 0` : classes `bg-danger-50` et texte `text-danger-500` (styles `danger` existants). Ordre des lignes = ordre reçu.

## Plan de tests

| Critère du ticket | Test |
|---|---|
| Endpoint stats agrégées pour une région (ADMIN_REGIONAL) | `StatistiquesConsolideesIntegrationTest` : `regionale_commeAdminRegional_shouldRetournerSeulementSaRegion` (2 régions avec pièces ; seuls les postes de la région de l'admin, `portee=REGIONALE`, `regionNom` correct, totaux exacts) ; `regionale_regionSansPiece_shouldRetournerTotauxNuls` (totaux 0, moyenne/max `null`, postes présents avec 0) |
| Endpoint stats pour tout le territoire (ADMIN_NATIONAL) | `nationale_commeAdminNational_shouldRetournerTousLesPostes` (`portee=NATIONALE`, `regionId`/`regionNom` null, postes de toutes régions, `regionNom` par ligne) ; `nationale_shouldTrierParDepassantsPuisNom` |
| Accès restreint aux deux rôles | `regionale_commeAgent/ChefPoste/Auditeur/AdminNational_shouldRetourner403` ; `nationale_commeAgent/ChefPoste/Auditeur/AdminRegional_shouldRetourner403` ; `regionale_et_nationale_sansJeton_shouldRetourner401` |
| Cohérence seuils/alertes #26 à l'échelle agrégée | `consolide_shouldEtreCoherentAvecEndpointPoste` : même jeu de données (pièces disponible/réclamée/retirée, âges 5, 30, 200 jours, comparaison stricte : pièce à exactement 180 jours non comptée), pour chaque poste la ligne du consolidé == `GET /poste/{id}` (nombre, moyenne, max, dépassants) et `seuilAncienneteJours` identique ; statut `RETIREE` exclu. `StatistiquesConsolideesServiceTest` : moyenne pondérée, max des max, `nombrePostesEnDepassement`, liste vide |
| Vue frontend dédiée, visible uniquement pour ces deux rôles | `roles.test.ts` ; `AgentShell.test.tsx` (onglet visible + `onNaviguer('vue-multi-poste')` pour ADMIN_REGIONAL/ADMIN_NATIONAL ; masqué pour AGENT, CHEF_POSTE, AUDITEUR et rôle non chargé) ; `VueMultiPostePage.test.tsx` : ADMIN_REGIONAL appelle `/api/v1/statistiques/regionale` (colonne Région absente), ADMIN_NATIONAL appelle `/nationale` (colonne Région présente), bannière d'alerte si dépassants, pas de bannière sinon, valeurs null rendues `—`, état chargement, erreur générique, 403 (réponse `Erreur 403` et rôle AGENT sans appel stats), vide |
| Dashboard poste unique inchangé pour Agent et Chef de poste | `DashboardPage.test.tsx` existant exécuté sans modification ; `DashboardPage.tsx`, `StatistiquesPosteService`, `GET /poste/{id}` non modifiés (`StatistiquesPosteIntegrationTest` inchangé et vert) |
| Non-régression navigation | `App.test.tsx` et `AgentShell.test.tsx` existants verts ; manuel : se connecter en ADMIN_REGIONAL puis ADMIN_NATIONAL, ouvrir « Vue multi-poste » et « Tableau de bord » |

## Écarts identifiés
1. **Nom de projection** : `StockPosteAgrege` existe (`reporting/StockPosteAgrege.java`, 3 champs) ; le design propose `StockParPosteAgrege` (nom proche, source de confusion possible). Tranché : garder `StockParPosteAgrege`, déclarée dans le même package, Javadoc d'une ligne pour distinguer.
2. **Champ ajouté au design** : `ancienneteTotaleJours` dans la projection (moyenne pondérée exacte). Le design calculait la moyenne pondérée « en Java » à partir des moyennes ; équivalent en résultat, plus précis.
3. **Tri** : le design le dit « puis `posteNom` » sans préciser où ; tranché en SQL (`ORDER BY` avec tiebreak `po.id`), donc collation PostgreSQL et non Java.
4. **Valeurs `portee`** non précisées par le design ; tranché : `REGIONALE` / `NATIONALE` (aligné sur les noms d'endpoints).
5. **Alias non quotés** en SQL natif : PostgreSQL les met en minuscules. Le code existant (`agregerStockParPoste`) fonctionne avec ce style, mais `posteId`/`regionNom` sont de nouveaux getters composés ; le test d'intégration est le garde-fou (repli : alias quotés).
6. **Statut 403 côté UI** : `dashboardApi` lève `Error('Erreur <status>')` sans classe dédiée ; la page teste `message === 'Erreur 403'`. Acceptable, pas de refonte de l'API partagée.
7. **Ticket vs design** : le ticket dit « agrégées (et/ou liste par poste) » ; le design fournit les deux, conforme. Le drill-down région pour ADMIN_NATIONAL est hors périmètre, à confirmer avec le PO si attendu.
8. **Vérifier** `SecurityConfig` (tâche 7) : non relu ici ; si un filtrage par chemin existe pour `/statistiques/**`, il doit accepter les nouveaux chemins.
