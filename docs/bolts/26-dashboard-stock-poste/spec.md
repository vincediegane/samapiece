# Spec -- #26 Vue "stock courant" par poste (Dashboard)

## Résumé

Ajout d'un endpoint `GET /api/v1/statistiques/poste/{id}` (module `sn.samapiece.reporting`) exposant le stock de pièces en attente d'un poste avec son ancienneté moyenne/max et une alerte de dépassement de seuil, d'un endpoint `GET /api/v1/agents/moi` (prérequis, module `iam`) pour que le frontend connaisse le poste de l'agent connecté, et d'un écran `DashboardPage` affichant ces indicateurs avec un badge d'alerte visuelle.

## Décisions tranchées (fermeture des points ouverts du design)

1. **`PerimetrePoste` non étendu pour `AUDITEUR`** : décision définitive, pas une lacune. Le ticket #26 ne mentionne pas explicitement ce rôle ; `PerimetrePoste` est partagé avec le workflow de retrait (#24, déjà mergé) et ne doit pas être modifié sans un besoin métier confirmé explicitement par un futur ticket. `AUDITEUR` reçoit donc systématiquement 403 sur `GET /api/v1/statistiques/poste/{id}`, via la branche `default -> false` déjà existante.
2. **`GET /api/v1/agents/moi` dans un nouveau contrôleur dédié `AgentSelfController`**, package `sn.samapiece.iam.web`, fichier `backend/src/main/java/sn/samapiece/iam/web/AgentSelfController.java`. Ne touche pas à `AgentAdminController.java` : son `@PreAuthorize("hasAnyRole('CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')")` sur toutes ses méthodes existantes ne doit pas être dilué ou confondu avec un endpoint self-service ouvert à tout agent authentifié et actif. Route exacte : `GET /api/v1/agents/moi`, méthode `moi()`.
   - Pour rester cohérent avec le pattern du reste du code (chaque `@RestController` délègue à une classe `*Service`, aucun contrôleur n'accède directement à `SecurityContextHolder`/aux repositories -- voir `PieceController`→`PieceService`, `AgentAdminController`→`AgentAdminService`), créer aussi `backend/src/main/java/sn/samapiece/iam/AgentSelfService.java` avec une méthode `moi()` qui résout l'appelant courant (même bloc que `PieceService.appelantCourant()`, dupliqué à l'identique -- aucune extraction commune n'existe dans ce repo, ne pas en créer une pour ce ticket) et retourne `AgentResponse.of(appelant)`.
3. **Pas de `Clock` injectable** dans `StatistiquesPosteService`/`PieceService`. Décision définitive pour ce ticket : introduire un `Clock` complexifierait deux classes qui n'en ont besoin que pour isoler un seul test. Les dates de dépôt utilisées dans le test d'intégration sont calculées une seule fois à partir de `LocalDate.now()` capturé dans une variable locale au début du test, et les valeurs attendues d'ancienneté sont calculées dynamiquement via `ChronoUnit.DAYS.between(dateDepot, memeVariableAujourdhui)` -- jamais un nombre de jours codé en dur. Voir Plan de tests.
4. **Pas de nouveau module `features/agent-courant/`** : le seul appel à `GET /api/v1/agents/moi` est ajouté directement dans `frontend/src/features/dashboard/dashboardApi.ts` (fonction `recupererAgentCourant`). Un seul écran consomme ce profil courant actuellement ; créer un module partagé serait une abstraction prématurée.

## Tâches

### Backend -- module `sn.samapiece.reporting`

- [ ] `backend/src/main/java/sn/samapiece/reporting/StatistiquesProperties.java` -- nouveau, `@Component @Validated @ConfigurationProperties(prefix = "samapiece.reporting")`, champ `int seuilAncienneteJours` initialisé à `180`, getter/setter (pattern `PhotoMinioProperties`/`JwtProperties`). Pas de `@NotBlank`/`@Min` requis : la valeur par défaut du champ suffit si la propriété est absente.
- [ ] `backend/src/main/resources/application.yml` -- ajouter, dans la section `samapiece:` existante (après `alerte-correspondance`) :
  ```yaml
  reporting:
    seuil-anciennete-jours: ${SAMAPIECE_SEUIL_ANCIENNETE_JOURS:180}
  ```
  Pas de modification de `backend/src/test/resources/application.yml` nécessaire : la valeur par défaut `180` s'applique déjà en test sans variable d'environnement définie.
- [ ] `backend/src/main/java/sn/samapiece/reporting/StockPosteAgrege.java` -- nouvelle interface de projection Spring Data : `long getNombrePieces(); Double getAncienneteMoyenneJours(); Long getAncienneteMaxJours();`.
- [ ] `backend/src/main/java/sn/samapiece/reporting/StatistiquesPosteResponse.java` -- nouveau record (voir Contrat technique).
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/PieceRepository.java` -- ajouter les deux méthodes `@Query(nativeQuery = true)` (voir Contrat technique). Ne pas modifier la méthode existante `findByTypeDocumentAndStatutAndNomTitulaireIgnoreCase`.
- [ ] `backend/src/main/java/sn/samapiece/reporting/StatistiquesPosteService.java` -- nouveau `@Service` (voir Contrat technique) : résout l'appelant courant (duplique le bloc `appelantCourant()` de `PieceService`, même remarque qu'au point 2 ci-dessus -- pas de factorisation), charge le `Poste` cible via `PosteRepository.findById` (404 `PosteIntrouvableException` sinon), vérifie `PerimetrePoste.estDansPerimetre(appelant, poste)` (403 `AccesRefuseException` sinon), appelle `pieceRepository.agregerStockParPoste(posteId)` puis `pieceRepository.compterDepassantSeuil(posteId, seuil)`, construit la réponse.
- [ ] `backend/src/main/java/sn/samapiece/reporting/web/StatistiquesController.java` -- nouveau `@RestController` (voir Contrat technique).
  - **Aucun nouvel `@RestControllerAdvice` à créer pour ce module.** `AccesRefuseException` et `PosteIntrouvableException` sont déjà gérées globalement par `sn.samapiece.iam.web.AgentAdminExceptionHandler` (`@RestControllerAdvice` sans `basePackages`, donc appliqué à toute l'application, pas seulement à `AgentAdminController`) : 403 `ACCES_REFUSE` / 404 `POSTE_INTROUVABLE` respectivement. Créer un second `@ExceptionHandler` pour ces mêmes types dans le module `reporting` provoquerait une ambiguïté Spring (deux `@RestControllerAdvice` gérant le même type d'exception) -- ne pas le faire.

### Backend -- module `iam` (prérequis frontend)

- [ ] `backend/src/main/java/sn/samapiece/iam/AgentSelfService.java` -- nouveau (voir point 2 ci-dessus et Contrat technique).
- [ ] `backend/src/main/java/sn/samapiece/iam/web/AgentSelfController.java` -- nouveau (voir Contrat technique). Pas de `@PreAuthorize` : la route `/api/v1/agents/moi` n'est dans aucune liste `permitAll()` de `SecurityConfig`, elle retombe donc sur `.anyRequest().authenticated()` -- tout agent authentifié (quel que soit son rôle, y compris `AUDITEUR`) peut l'appeler ; le service refuse ensuite (403 `ACCES_REFUSE`) si l'agent résolu est inactif.
  - Vérifier qu'aucune collision de route n'existe avec `AgentAdminController` : celui-ci n'a pas de `@GetMapping("/{id}")` ni de `@GetMapping("/moi")`, donc `/api/v1/agents/moi` (chemin littéral) ne peut pas être intercepté par erreur.

### Frontend -- nouveau module `frontend/src/features/dashboard/`

- [ ] `frontend/src/features/dashboard/types.ts` -- miroir TS de `StatistiquesPosteResponse` (interface `StatistiquesPoste`) + interface `AgentCourant` miroir de `AgentResponse` (voir Contrat technique).
- [ ] `frontend/src/features/dashboard/dashboardApi.ts` -- `recupererAgentCourant()` (`GET /api/v1/agents/moi`) et `getStatistiquesPoste(posteId: string)` (`GET /api/v1/statistiques/poste/{posteId}`), même pattern que `piecesApi.ts`/`agentsApi.ts` (fetch + `enTeteAutorisation()` dupliquée, pas de client HTTP partagé dans ce repo).
- [ ] `frontend/src/features/dashboard/DashboardPage.tsx` -- au montage (`useEffect`), appelle `recupererAgentCourant()` puis `getStatistiquesPoste(agent.posteId)` ; affiche les indicateurs et un badge d'alerte si `nombrePiecesDepassantSeuil > 0` (voir Contrat technique).
- [ ] `frontend/src/features/dashboard/DashboardPage.test.tsx` -- tests Vitest/Testing Library (voir Plan de tests), même pattern que `EnregistrementPiecePage.test.tsx` (`vi.stubGlobal('fetch', vi.fn())`).
- [ ] `frontend/src/app/App.tsx` -- ajouter `'dashboard'` au type `Onglet`, un bouton de nav ("Tableau de bord"), et le rendu conditionnel de `DashboardPage` (voir Contrat technique).

### Tests backend

- [ ] `backend/src/test/java/sn/samapiece/reporting/StatistiquesPosteIntegrationTest.java` -- nouveau, `@SpringBootTest @AutoConfigureMockMvc @Testcontainers`, même structure que `PieceIntegrationTest.java` (voir Plan de tests pour le détail des cas).
- [ ] `backend/src/test/java/sn/samapiece/iam/AgentSelfIntegrationTest.java` -- nouveau, même structure que `AgentAdminIntegrationTest.java` (voir Plan de tests).

## Contrat technique

### `PieceRepository` (ajouts)

```java
public interface PieceRepository extends JpaRepository<Piece, UUID> {

    List<Piece> findByTypeDocumentAndStatutAndNomTitulaireIgnoreCase(
            TypeDocument typeDocument, StatutPiece statut, String nomTitulaire);

    @Query(value = """
            SELECT COUNT(*) AS nombrePieces,
                   AVG(CURRENT_DATE - date_depot) AS ancienneteMoyenneJours,
                   MAX(CURRENT_DATE - date_depot) AS ancienneteMaxJours
            FROM piece
            WHERE poste_id = :posteId
              AND statut IN ('disponible', 'reclamee')
            """, nativeQuery = true)
    StockPosteAgrege agregerStockParPoste(@Param("posteId") UUID posteId);

    @Query(value = """
            SELECT COUNT(*)
            FROM piece
            WHERE poste_id = :posteId
              AND statut IN ('disponible', 'reclamee')
              AND (CURRENT_DATE - date_depot) > :seuilJours
            """, nativeQuery = true)
    long compterDepassantSeuil(@Param("posteId") UUID posteId, @Param("seuilJours") int seuilJours);
}
```

Points de vigilance impératifs pour le codeur :
- `statut` est stocké en base en **minuscules** (`StatutPieceConverter`/contrainte CHECK de `V4__create_piece.sql` : `'disponible', 'reclamee', ...`). La requête native contourne le converter JPA -- les littéraux `'disponible'`/`'reclamee'` doivent être écrits en minuscules tels quels, ne pas utiliser `StatutPiece.DISPONIBLE.name()` côté Java pour cette requête.
- Les alias `nombrePieces`/`ancienneteMoyenneJours`/`ancienneteMaxJours` doivent correspondre (insensible à la casse) aux noms de getters de `StockPosteAgrege` -- ne pas les renommer sans mettre à jour l'interface de projection en conséquence.
- `COUNT(*)` sans `GROUP BY` renvoie toujours exactement une ligne, y compris si 0 pièce correspond (compteur à `0`, `AVG`/`MAX` à `NULL`) -- pas de `Optional`/liste vide à gérer côté service pour `agregerStockParPoste`.

### `StockPosteAgrege` (nouveau)

```java
public interface StockPosteAgrege {
    long getNombrePieces();
    Double getAncienneteMoyenneJours();
    Long getAncienneteMaxJours();
}
```

### `StatistiquesPosteResponse` (nouveau)

```java
public record StatistiquesPosteResponse(
        UUID posteId,
        String posteNom,
        long nombrePiecesEnAttente,
        Double ancienneteMoyenneJours,
        Long ancienneteMaxJours,
        int seuilAncienneteJours,
        long nombrePiecesDepassantSeuil) {
}
```

`ancienneteMoyenneJours`/`ancienneteMaxJours` sérialisent en JSON `null` (pas `0`) quand `nombrePiecesEnAttente == 0` -- comportement direct de `AVG`/`MAX` SQL sur 0 ligne, aucune logique supplémentaire à écrire côté service pour ce cas.

### `StatistiquesPosteService` (nouveau)

```java
@Service
public class StatistiquesPosteService {

    private final PieceRepository pieceRepository;
    private final AgentRepository agentRepository;
    private final PosteRepository posteRepository;
    private final StatistiquesProperties statistiquesProperties;

    // constructeur avec les 4 dépendances ci-dessus

    @Transactional(readOnly = true)
    public StatistiquesPosteResponse consulter(UUID posteId) {
        Agent appelant = appelantCourant();
        Poste poste = posteRepository.findById(posteId)
                .orElseThrow(() -> new PosteIntrouvableException(posteId));

        if (!PerimetrePoste.estDansPerimetre(appelant, poste)) {
            throw new AccesRefuseException("Poste/region hors perimetre pour ces statistiques.");
        }

        StockPosteAgrege agrege = pieceRepository.agregerStockParPoste(posteId);
        int seuil = statistiquesProperties.getSeuilAncienneteJours();
        long depassant = pieceRepository.compterDepassantSeuil(posteId, seuil);

        return new StatistiquesPosteResponse(
                poste.getId(),
                poste.getNom(),
                agrege.getNombrePieces(),
                agrege.getAncienneteMoyenneJours(),
                agrege.getAncienneteMaxJours(),
                seuil,
                depassant);
    }

    private Agent appelantCourant() {
        // identique a PieceService.appelantCourant() : SecurityContextHolder + AgentRepository.findByMatricule
        // + verification agent.isActif(), sinon AccesRefuseException
    }
}
```

`PosteIntrouvableException` s'importe de `sn.samapiece.iam` (package existant, ne pas en créer une copie dans `reporting`). `AccesRefuseException` idem.

### `StatistiquesController` (nouveau)

```java
package sn.samapiece.reporting.web;

@RestController
@RequestMapping("/api/v1/statistiques")
public class StatistiquesController {

    private final StatistiquesPosteService statistiquesPosteService;

    public StatistiquesController(StatistiquesPosteService statistiquesPosteService) {
        this.statistiquesPosteService = statistiquesPosteService;
    }

    @GetMapping("/poste/{id}")
    @PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')")
    public ResponseEntity<StatistiquesPosteResponse> consulterPoste(@PathVariable UUID id) {
        return ResponseEntity.ok(statistiquesPosteService.consulter(id));
    }
}
```

Le `@PreAuthorize` au niveau contrôleur exclut `AUDITEUR` immédiatement (défense en profondeur, même pattern que `PieceController.debloquer`) ; le contrôle fin poste/région reste entièrement délégué à `PerimetrePoste.estDansPerimetre` dans le service.

Pas d'`@ActionAuditee` sur cet endpoint : il n'expose que des agrégats (aucune donnée nominative de titulaire), à l'image de `AgentAdminController.lister()` qui n'est pas non plus audité.

### `AgentSelfService` / `AgentSelfController` (nouveaux)

```java
package sn.samapiece.iam;

@Service
public class AgentSelfService {

    private final AgentRepository agentRepository;

    public AgentSelfService(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @Transactional(readOnly = true)
    public AgentResponse moi() {
        String matricule = SecurityContextHolder.getContext().getAuthentication().getName();
        Agent appelant = agentRepository.findByMatricule(matricule)
                .orElseThrow(() -> new AccesRefuseException("Agent appelant introuvable."));
        if (!appelant.isActif()) {
            throw new AccesRefuseException("Agent appelant inactif.");
        }
        return AgentResponse.of(appelant);
    }
}
```

```java
package sn.samapiece.iam.web;

@RestController
@RequestMapping("/api/v1/agents")
public class AgentSelfController {

    private final AgentSelfService agentSelfService;

    public AgentSelfController(AgentSelfService agentSelfService) {
        this.agentSelfService = agentSelfService;
    }

    @GetMapping("/moi")
    public ResponseEntity<AgentResponse> moi() {
        return ResponseEntity.ok(agentSelfService.moi());
    }
}
```

Réutilise `AgentResponse.of(...)` déjà existant (contient `posteId`/`posteNom`) -- aucune nouvelle classe de réponse à créer.

### `application.yml` (extrait après modification)

```yaml
samapiece:
  # ... sections existantes inchangées ...
  alerte-correspondance:
    max-tentatives: ${ALERTE_CORRESPONDANCE_MAX_TENTATIVES:3}
    retry-ttl-30s-ms: 30000
    retry-ttl-2m-ms: 120000
    retry-ttl-10m-ms: 600000
  reporting:
    seuil-anciennete-jours: ${SAMAPIECE_SEUIL_ANCIENNETE_JOURS:180}
```

### Frontend -- `types.ts`

```ts
export interface StatistiquesPoste {
  posteId: string;
  posteNom: string;
  nombrePiecesEnAttente: number;
  ancienneteMoyenneJours: number | null;
  ancienneteMaxJours: number | null;
  seuilAncienneteJours: number;
  nombrePiecesDepassantSeuil: number;
}

export interface AgentCourant {
  id: string;
  matricule: string;
  nom: string;
  role: string;
  posteId: string;
  posteNom: string;
  actif: boolean;
  creeLe: string;
}
```

### Frontend -- `dashboardApi.ts`

```ts
import type { AgentCourant, StatistiquesPoste } from './types';

function enTeteAutorisation(): HeadersInit {
  const jeton = window.localStorage.getItem('samapiece.accessToken') ?? '';
  return { Authorization: `Bearer ${jeton}`, 'Content-Type': 'application/json' };
}

export async function recupererAgentCourant(): Promise<AgentCourant> {
  const reponse = await fetch('/api/v1/agents/moi', { headers: enTeteAutorisation() });
  if (!reponse.ok) throw new Error(`Erreur ${reponse.status}`);
  return reponse.json();
}

export async function getStatistiquesPoste(posteId: string): Promise<StatistiquesPoste> {
  const reponse = await fetch(`/api/v1/statistiques/poste/${posteId}`, {
    headers: enTeteAutorisation(),
  });
  if (!reponse.ok) throw new Error(`Erreur ${reponse.status}`);
  return reponse.json();
}
```

### Frontend -- `DashboardPage.tsx` (contenu minimal attendu)

- Au montage : `recupererAgentCourant()` puis, avec `agent.posteId` obtenu, `getStatistiquesPoste(agent.posteId)`. Erreurs affichées via `<p role="alert">` (pattern `AgentsPage.tsx`).
- Affichage : titre incluant `posteNom`, nombre de pièces en attente (`nombrePiecesEnAttente`), ancienneté moyenne et max -- si `ancienneteMoyenneJours`/`ancienneteMaxJours` sont `null` (0 pièce en attente), afficher un texte explicite type "Aucune pièce en attente" plutôt qu'un chiffre.
- Badge d'alerte visuelle : affiché (élément avec `role="alert"` distinct du message d'erreur réseau, ex. texte "X pièce(s) dépassent le seuil de Y jours") si et seulement si `nombrePiecesDepassantSeuil > 0`.

### Frontend -- `App.tsx` (modification exacte)

```tsx
import DashboardPage from '../features/dashboard/DashboardPage';
// ... imports existants inchangés

type Onglet = 'pieces' | 'agents' | 'recherche' | 'dashboard';

// dans <nav>, ajouter :
<button type="button" onClick={() => setOnglet('dashboard')} disabled={onglet === 'dashboard'}>
  Tableau de bord
</button>

// dans le rendu conditionnel, ajouter la branche 'dashboard' -> <DashboardPage />
```

## Plan de tests

| Critère d'acceptation | Test |
|---|---|
| `GET /api/v1/statistiques/poste/{id}` calcule correctement le nombre de pièces en attente, ancienneté moyenne/max | `StatistiquesPosteIntegrationTest.consulter_avecPiecesConnues_shouldRetournerIndicateursCorrects` -- voir jeu de données ci-dessous |
| Endpoint réservé aux rôles du poste concerné | `StatistiquesPosteIntegrationTest` : cas 200 (AGENT/CHEF_POSTE du poste, ADMIN_REGIONAL même région, ADMIN_NATIONAL) et 403 (AGENT d'un autre poste, ADMIN_REGIONAL hors région, AUDITEUR) -- voir ci-dessous |
| Interface frontend affichant les indicateurs pour le poste de l'agent connecté | `DashboardPage.test.tsx` (Vitest) : mock de `fetch` pour `/api/v1/agents/moi` puis `/api/v1/statistiques/poste/{posteId}`, assertion sur l'affichage des indicateurs retournés ; complété par une vérification manuelle en local (pas de test end-to-end dans ce repo) |
| Alerte visuelle sur dépassement de seuil configurable | `DashboardPage.test.tsx` : badge `role="alert"` présent si `nombrePiecesDepassantSeuil > 0` dans la réponse mockée, absent sinon ; côté backend, `StatistiquesPosteIntegrationTest.consulter_avecPiecesConnues_shouldRetournerIndicateursCorrects` vérifie `nombrePiecesDepassantSeuil` et `seuilAncienneteJours` dans le JSON |
| Test d'intégration sur le calcul des indicateurs avec un jeu de données de test | `StatistiquesPosteIntegrationTest` (ci-dessous) |
| (Prérequis) `GET /api/v1/agents/moi` renvoie le profil de l'appelant | `AgentSelfIntegrationTest` (ci-dessous) |

### `StatistiquesPosteIntegrationTest` -- détail des cas

Structure de fichier : `@SpringBootTest @AutoConfigureMockMvc @Testcontainers`, `PostgreSQLContainer` via `@ServiceConnection`, `@BeforeEach` qui vide `piece_sequence` (via `JdbcTemplate`) **avant** `posteRepository.deleteAll()` (contrainte FK, cf. mémoire projet), puis `pieceRepository.deleteAll()`, `agentRepository.deleteAll()`, `posteRepository.deleteAll()`, `regionRepository.deleteAll()`. Toute lecture de corps de réponse contenant du texte accentué utilise `getContentAsString(StandardCharsets.UTF_8)`.

Calcul des dates -- capturer une seule fois `LocalDate aujourdHui = LocalDate.now();` en tête de chaque test qui en a besoin, dériver les `dateDepot` des `Piece` via `aujourdHui.minusDays(N)`, et calculer les valeurs attendues avec `ChronoUnit.DAYS.between(dateDepot, aujourdHui)` -- ne jamais recalculer un second `LocalDate.now()` dans les assertions ni coder un nombre de jours en dur (cf. mémoire projet).

Jeu de données pour le test de calcul (`consulter_avecPiecesConnues_shouldRetournerIndicateursCorrects`), sur un unique `Poste` :
- Pièce A : `dateDepot = aujourdHui.minusDays(30)`, statut `DISPONIBLE` (créée via `pieceRepository.save(...)`, statut positionné au constructeur -- `DISPONIBLE` est déjà le statut par défaut, pas besoin de `ReflectionTestUtils`).
- Pièce B : `dateDepot = aujourdHui.minusDays(200)`, statut `RECLAMEE` (nécessite `ReflectionTestUtils.setField(piece, "statut", StatutPiece.RECLAMEE)` après construction, comme dans `PieceIntegrationTest.creerPieceEnBase`).
- Pièce C (bruit, doit être exclue) : `dateDepot = aujourdHui.minusDays(5)`, statut `RETIREE`.

Assertions sur le JSON retourné par `GET /api/v1/statistiques/poste/{id}` (200) :
- `nombrePiecesEnAttente == 2` (A + B, C exclue).
- `ancienneteMoyenneJours` == moyenne de `ChronoUnit.DAYS.between(dateA, aujourdHui)` et `ChronoUnit.DAYS.between(dateB, aujourdHui)` (soit `(30+200)/2.0 = 115.0` avec les décalages ci-dessus, mais calculé dynamiquement dans le test, pas en dur).
- `ancienneteMaxJours` == `ChronoUnit.DAYS.between(dateB, aujourdHui)` (soit `200`).
- `seuilAncienneteJours == 180` (valeur par défaut de `application.yml`, aucune surcharge nécessaire côté test).
- `nombrePiecesDepassantSeuil == 1` (seule la pièce B, à 200 jours, dépasse 180 ; la pièce A à 30 jours ne dépasse pas).

Test dédié valeurs nulles à 0 pièce (`consulter_sansPieceEnAttente_shouldRetournerValeursNulles`) : `Poste` sans aucune `Piece` (ou uniquement des pièces `RETIREE`/`ARCHIVEE`) -> `nombrePiecesEnAttente == 0`, `ancienneteMoyenneJours` JSON `null`, `ancienneteMaxJours` JSON `null`, `nombrePiecesDepassantSeuil == 0`.

Cas RBAC (chacun un `@Test` séparé, réutilisant le jeu de données minimal nécessaire) :
- AGENT du poste cible -> 200.
- CHEF_POSTE du poste cible -> 200.
- AGENT d'un autre poste (même région) -> 403, `code == "ACCES_REFUSE"`.
- ADMIN_REGIONAL d'un poste de la même région -> 200.
- ADMIN_REGIONAL d'un poste d'une autre région -> 403.
- ADMIN_NATIONAL (n'importe quel poste) -> 200.
- AUDITEUR -> 403.
- `id` de poste inexistant (UUID aléatoire), appelant ADMIN_NATIONAL -> 404, `code == "POSTE_INTROUVABLE"`.

### `AgentSelfIntegrationTest` -- détail des cas

Structure de fichier : même pattern que `AgentAdminIntegrationTest.java` (package `sn.samapiece.iam`, `@SpringBootTest @AutoConfigureMockMvc @Testcontainers`, nettoyage `agentRepository.deleteAll()` / `posteRepository.deleteAll()` / `regionRepository.deleteAll()` -- pas de `Piece` créée dans ce test, donc pas besoin de vider `piece_sequence`).

- `moi_commeAgentActif_shouldRetourner200AvecPosteIdEtPosteNom` : un `AGENT` authentifié reçoit `$.posteId` et `$.posteNom` correspondant à son poste.
- `moi_commeAuditeur_shouldRetourner200` : confirme que l'endpoint est ouvert à tous les rôles authentifiés, contrairement à `GET /api/v1/statistiques/poste/{id}`.
- `moi_sansToken_shouldRetourner401`.
- `moi_commeAgentDesactiveApresEmissionDuToken_shouldRetourner403AccesRefuse` (même scénario que `PieceIntegrationTest.creer_commeAgentDesactiveApresEmissionDuToken_shouldRetourner403AccesRefuse`) : `code == "ACCES_REFUSE"`.

### `DashboardPage.test.tsx` -- détail des cas

Pattern `EnregistrementPiecePage.test.tsx` : `beforeEach` avec `window.localStorage.setItem('samapiece.accessToken', 'jeton-factice')` et `vi.stubGlobal('fetch', vi.fn())`, en mockant deux réponses successives (`/api/v1/agents/moi` puis `/api/v1/statistiques/poste/...`).

- Affiche `posteNom`, `nombrePiecesEnAttente`, ancienneté moyenne/max quand la réponse mockée contient des valeurs non nulles.
- Affiche un texte "Aucune pièce en attente" (pas de `NaN`/`null` brut à l'écran) quand `ancienneteMoyenneJours`/`ancienneteMaxJours` sont `null` dans la réponse mockée.
- Badge `role="alert"` présent quand `nombrePiecesDepassantSeuil > 0`.
- Badge absent quand `nombrePiecesDepassantSeuil === 0`.

## Écarts identifiés

Aucun écart entre le design et les critères d'acceptation du ticket n'a été détecté. Le seul point que l'architecte a explicitement signalé comme un débordement de périmètre (`GET /api/v1/agents/moi` dans le module `iam` plutôt que `reporting`) est un prérequis strictement nécessaire pour satisfaire le critère "Interface frontend simple affichant ces indicateurs pour le poste de l'agent connecté" -- sans lui, il est impossible de déterminer côté frontend quel poste interroger. Il est traité ici comme une extension mineure et justifiée du périmètre, pas comme un écart à trancher séparément.
