# Spec -- Ticket #9 : CRUD comptes agents (création/désactivation) côté admin

## Résumé

Ajout d'endpoints REST admin (`GET/POST/PATCH/DELETE /api/v1/agents`) pour créer, lister, modifier et désactiver logiquement des comptes agents avec génération de mot de passe temporaire, plus une page frontend minimale (liste + formulaire) pour les piloter.

## Tâches

### Backend

- [ ] `backend/src/main/java/sn/samapiece/iam/Agent.java` -- ajouter deux méthodes métier (pas de setter générique) :
  - `public void desactiver()` : met `actif = false` ; idempotente (aucune exception si déjà inactif).
  - `public void modifierInformations(String nom, Poste poste)` : remplace `nom` et `poste` par les valeurs fournies (non nulles, résolution des valeurs "inchangées" faite en amont par le service, pas par l'entité).
- [ ] `backend/src/test/java/sn/samapiece/iam/AgentTest.java` -- compléter avec :
  - `desactiver_shouldMettreActifAFaux`
  - `desactiver_appeleDeuxFois_shouldResterIdempotent` (pas d'exception, `isActif()` reste `false`)
  - `modifierInformations_shouldRemplacerNomEtPoste`
- [ ] `backend/src/main/java/sn/samapiece/config/SecurityConfig.java` -- ajouter `@EnableMethodSecurity` sur la classe (import `org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity`). Ne pas toucher à `authorizeHttpRequests` : `/api/v1/agents/**` reste couvert par `anyRequest().authenticated()`, le contrôle de rôle fin est délégué à `@PreAuthorize` sur le contrôleur. **Point de vigilance obligatoire pour le codeur** : sans cette annotation, `@PreAuthorize` est silencieusement ignoré (tout utilisateur authentifié passerait) -- à vérifier explicitement par un test 403 (voir plan de tests).
- [ ] `backend/src/main/java/sn/samapiece/iam/AgentIntrouvableException.java` (nouveau) -- `RuntimeException`, constructeur `AgentIntrouvableException(UUID id)`, message `"Agent introuvable : " + id`.
- [ ] `backend/src/main/java/sn/samapiece/iam/PosteIntrouvableException.java` (nouveau) -- `RuntimeException`, constructeur `PosteIntrouvableException(UUID id)`, message `"Poste introuvable : " + id`.
- [ ] `backend/src/main/java/sn/samapiece/iam/MatriculeDejaUtiliseException.java` (nouveau) -- `RuntimeException`, constructeur `MatriculeDejaUtiliseException(String matricule)`, message `"Matricule déjà utilisé : " + matricule`.
- [ ] `backend/src/main/java/sn/samapiece/iam/web/CreerAgentRequest.java` (nouveau, record) -- voir contrat technique.
- [ ] `backend/src/main/java/sn/samapiece/iam/web/CreerAgentResponse.java` (nouveau, record) -- voir contrat technique.
- [ ] `backend/src/main/java/sn/samapiece/iam/web/ModifierAgentRequest.java` (nouveau, record) -- voir contrat technique.
- [ ] `backend/src/main/java/sn/samapiece/iam/web/AgentResponse.java` (nouveau, record) -- utilisé par `GET` (liste) et `PATCH` (réponse). Ne jamais inclure `hashMotDePasse`.
- [ ] `backend/src/main/java/sn/samapiece/iam/AgentAdminService.java` (nouveau) -- logique création/liste/modification/désactivation, génération et hachage du mot de passe temporaire. Voir contrat technique pour les signatures exactes.
- [ ] `backend/src/main/java/sn/samapiece/iam/web/AgentAdminController.java` (nouveau) -- `GET/POST /api/v1/agents`, `PATCH/DELETE /api/v1/agents/{id}`, chaque méthode annotée `@PreAuthorize("hasAnyRole('CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')")`.
- [ ] `backend/src/main/java/sn/samapiece/iam/web/AgentAdminExceptionHandler.java` (nouveau, `@RestControllerAdvice`) -- mappe `AgentIntrouvableException`/`PosteIntrouvableException` -> 404, `MatriculeDejaUtiliseException` -> 409, `IllegalArgumentException` (validation métier simple, ex. `nom` fourni vide) -> 400.
- [ ] `backend/src/test/java/sn/samapiece/iam/AgentAdminIntegrationTest.java` (nouveau, style `AuthIntegrationTest`/`AgentIntegrationTest` : `@SpringBootTest`, `@AutoConfigureMockMvc`, `@Testcontainers`) -- couvre tous les scénarios du plan de tests ci-dessous, y compris le test bout-en-bout de régression sur la désactivation.
- [ ] Vérifier (pas de fichier à modifier a priori) qu'aucun `Logger`/`System.out` de la nouvelle chaîne (`AgentAdminController`, `AgentAdminService`, filtres Spring) ne trace le corps de requête/réponse de `POST /api/v1/agents` -- si un logging d'accès générique existe déjà dans le projet (aucun trouvé à ce jour), l'exclure explicitement pour cette route dans la même tâche que son ajout.

### Frontend

- [ ] `frontend/src/features/agents/types.ts` (nouveau) -- types `Role`, `Agent`, `CreerAgentPayload`, `CreerAgentResultat`, `ModifierAgentPayload`, `Poste` (forme minimale `{ id, nom }` pour le sélecteur de poste).
- [ ] `frontend/src/features/agents/agentsApi.ts` (nouveau) -- `listerAgents()`, `creerAgent(payload)`, `desactiverAgent(id)` en `fetch` direct vers `/api/v1/agents`. Le jeton porteur est lu depuis `window.localStorage.getItem('samapiece.accessToken')` (aucune UI de login dans ce ticket -- le jeton doit être déposé manuellement dans le `localStorage` du navigateur pour ce pilote ; limitation à documenter dans le composant, ex. message visible si le jeton est absent).
- [ ] `frontend/src/features/agents/AgentsPage.tsx` (nouveau) -- tableau des agents (matricule, nom, poste, rôle, statut actif/inactif, bouton "Désactiver" désactivé si déjà inactif) + formulaire de création inline (matricule, nom, rôle en `<select>` des 5 valeurs de `Role`, poste en `<select>` peuplé par un `fetch('/api/v1/postes')` direct au montage -- endpoint public existant, pas de wrapper `postesApi.ts` nécessaire pour ce scope minimal). Après création réussie, afficher le mot de passe temporaire retourné dans une bannière visible une seule fois (pas de re-fetch capable de le récupérer plus tard), avec message explicite "à communiquer à l'agent, ne sera plus affiché".
- [ ] `frontend/src/app/App.tsx` (modifié) -- remplacer le rendu unique de `HomePage` par un point d'entrée simple vers `AgentsPage` (pas de routeur installé -- affichage direct, `react-router` absent de `package.json`).

## Contrat technique

### Sécurité

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig { ... }
```

Rôle requis sur les 4 endpoints (identique) :
```java
@PreAuthorize("hasAnyRole('CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')")
```
Confirmé par lecture de `JwtAuthenticationFilter` : les autorités posées dans le `SecurityContext` sont `List.of(role)` où `Role implements GrantedAuthority` et `getAuthority()` renvoie `"ROLE_" + name()` -- compatible nativement avec `hasAnyRole(...)` de Spring Security (qui préfixe `ROLE_` en interne). Aucune adaptation du filtre JWT n'est nécessaire.

### `Agent.java` -- méthodes ajoutées

```java
public void desactiver() {
    this.actif = false;
}

public void modifierInformations(String nom, Poste poste) {
    this.nom = Objects.requireNonNull(nom, "nom");
    this.poste = Objects.requireNonNull(poste, "poste");
}
```

### DTOs (`sn.samapiece.iam.web`, records, style `LoginRequest`/`LoginResponse`)

```java
public record CreerAgentRequest(
        @NotNull UUID posteId,
        @NotBlank String matricule,
        @NotBlank String nom,
        @NotNull Role role) {
}

public record CreerAgentResponse(
        UUID id, String matricule, String nom, String role, UUID posteId,
        boolean actif, String motDePasseTemporaire) {

    public static CreerAgentResponse of(Agent agent, String motDePasseTemporaire) {
        return new CreerAgentResponse(
                agent.getId(), agent.getMatricule(), agent.getNom(), agent.getRole().name(),
                agent.getPoste().getId(), agent.isActif(), motDePasseTemporaire);
    }
}

public record ModifierAgentRequest(String nom, UUID posteId) {
    // Les deux champs sont optionnels (null = inchangé). Ni mot de passe ni rôle : hors
    // périmètre de cet endpoint (cf. Décisions du design, point 7).
}

public record AgentResponse(
        UUID id, String matricule, String nom, String role, UUID posteId, String posteNom,
        boolean actif, OffsetDateTime creeLe) {

    public static AgentResponse of(Agent agent) {
        return new AgentResponse(
                agent.getId(), agent.getMatricule(), agent.getNom(), agent.getRole().name(),
                agent.getPoste().getId(), agent.getPoste().getNom(), agent.isActif(), agent.getCreeLe());
    }
}
```

### `AgentAdminService.java` -- signatures

```java
@Service
public class AgentAdminService {

    private static final int LONGUEUR_MOT_DE_PASSE = 12;
    private static final String ALPHABET_MOT_DE_PASSE =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$%&*";

    private final AgentRepository agentRepository;
    private final PosteRepository posteRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public AgentAdminService(
            AgentRepository agentRepository, PosteRepository posteRepository, PasswordEncoder passwordEncoder) { ... }

    @Transactional
    public CreerAgentResponse creer(CreerAgentRequest request) {
        // 1. resoudre poste via posteRepository.findById -> PosteIntrouvableException si absent
        // 2. verifier absence prealable via agentRepository.findByMatricule -> MatriculeDejaUtiliseException si present
        // 3. generer motDePasseTemporaire (genererMotDePasseTemporaire())
        // 4. construire Agent avec passwordEncoder.encode(motDePasseTemporaire)
        // 5. persister via agentRepository.saveAndFlush(agent), capturer DataIntegrityViolationException
        //    (garde-fou contre une course concurrente sur la contrainte UNIQUE matricule)
        //    et la remapper en MatriculeDejaUtiliseException
        // 6. retourner CreerAgentResponse.of(agent, motDePasseTemporaire)
    }

    @Transactional(readOnly = true)
    public List<AgentResponse> lister() {
        return agentRepository.findAll().stream().map(AgentResponse::of).toList();
    }

    @Transactional
    public AgentResponse modifier(UUID id, ModifierAgentRequest request) {
        // 1. agentRepository.findById(id) -> AgentIntrouvableException si absent
        // 2. si request.nom() != null : rejeter avec IllegalArgumentException si isBlank()
        // 3. si request.posteId() != null : posteRepository.findById -> PosteIntrouvableException si absent,
        //    sinon reutiliser agent.getPoste()
        // 4. agent.modifierInformations(nomResolu, posteResolu) ; agentRepository.save(agent)
        // 5. retourner AgentResponse.of(agent)
    }

    @Transactional
    public void desactiver(UUID id) {
        Agent agent = agentRepository.findById(id).orElseThrow(() -> new AgentIntrouvableException(id));
        agent.desactiver();
        agentRepository.save(agent);
    }

    private String genererMotDePasseTemporaire() {
        // SecureRandom, LONGUEUR_MOT_DE_PASSE caracteres tires de ALPHABET_MOT_DE_PASSE
    }
}
```

Ne jamais journaliser `motDePasseTemporaire` ni le corps de `CreerAgentRequest`/`CreerAgentResponse` (aucun `log.info`/`log.debug` sur ces objets ; si un intercepteur de logging HTTP générique existe ou est ajouté ultérieurement, exclure `/api/v1/agents` de la trace du corps).

### Endpoints REST

Tous protégés par `@PreAuthorize("hasAnyRole('CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')")` -- rôle `AGENT` ou `AUDITEUR` -> `403 Forbidden`. Non authentifié -> `401` (déjà géré par `anyRequest().authenticated()` + `HttpStatusEntryPoint`).

**`GET /api/v1/agents`** (ajouté par cette spec, non listé explicitement dans les critères d'acceptation du ticket mais nécessaire au frontend -- décision tranchée, cf. Écarts identifiés)
- Réponse `200 OK` : `AgentResponse[]`.

**`POST /api/v1/agents`**
- Corps : `CreerAgentRequest` (`posteId`, `matricule`, `nom`, `role`).
- Succès : `201 Created`, corps `CreerAgentResponse` (inclut `motDePasseTemporaire` en clair, une seule fois).
- Erreurs : `400` (validation Bean Validation sur le corps), `404` `POSTE_INTROUVABLE` si `posteId` inconnu, `409` `MATRICULE_DEJA_UTILISE` si le matricule existe déjà.

**`PATCH /api/v1/agents/{id}`**
- Corps : `ModifierAgentRequest` (`nom` et/ou `posteId`, tous deux optionnels).
- Succès : `200 OK`, corps `AgentResponse`.
- Erreurs : `404` `AGENT_INTROUVABLE` si `id` inconnu, `404` `POSTE_INTROUVABLE` si `posteId` fourni mais inconnu, `400` si `nom` fourni vide.
- Ne modifie jamais `hashMotDePasse` ni `role` (non exposés dans `ModifierAgentRequest`).

**`DELETE /api/v1/agents/{id}`**
- Pas de corps.
- Succès : `204 No Content` (désactivation logique, idempotente -- un second appel sur un agent déjà inactif renvoie aussi `204`).
- Erreur : `404` `AGENT_INTROUVABLE` si `id` inconnu.

### Corps d'erreur (`AgentAdminExceptionHandler`, style `AuthExceptionHandler.ErreurReponse`)

```json
{ "code": "AGENT_INTROUVABLE", "message": "Agent introuvable." }
{ "code": "POSTE_INTROUVABLE", "message": "Poste introuvable." }
{ "code": "MATRICULE_DEJA_UTILISE", "message": "Ce matricule est deja utilise." }
```

### Frontend -- structure des fichiers

`frontend/src/features/agents/types.ts` :
```ts
export type Role = 'AGENT' | 'CHEF_POSTE' | 'ADMIN_REGIONAL' | 'ADMIN_NATIONAL' | 'AUDITEUR';

export interface Agent {
  id: string;
  matricule: string;
  nom: string;
  role: Role;
  posteId: string;
  posteNom: string;
  actif: boolean;
  creeLe: string;
}

export interface CreerAgentPayload {
  posteId: string;
  matricule: string;
  nom: string;
  role: Role;
}

export interface CreerAgentResultat extends Agent {
  motDePasseTemporaire: string;
}

export interface Poste {
  id: string;
  nom: string;
}
```

`frontend/src/features/agents/agentsApi.ts` :
```ts
const BASE_URL = '/api/v1/agents';

function enTeteAutorisation(): HeadersInit {
  const jeton = window.localStorage.getItem('samapiece.accessToken') ?? '';
  return { Authorization: `Bearer ${jeton}`, 'Content-Type': 'application/json' };
}

export async function listerAgents(): Promise<Agent[]> {
  const reponse = await fetch(BASE_URL, { headers: enTeteAutorisation() });
  if (!reponse.ok) throw new Error(`Erreur ${reponse.status}`);
  return reponse.json();
}

export async function creerAgent(payload: CreerAgentPayload): Promise<CreerAgentResultat> {
  const reponse = await fetch(BASE_URL, {
    method: 'POST',
    headers: enTeteAutorisation(),
    body: JSON.stringify(payload),
  });
  if (!reponse.ok) {
    const erreur = await reponse.json().catch(() => null);
    throw new Error(erreur?.message ?? `Erreur ${reponse.status}`);
  }
  return reponse.json();
}

export async function desactiverAgent(id: string): Promise<void> {
  const reponse = await fetch(`${BASE_URL}/${id}`, { method: 'DELETE', headers: enTeteAutorisation() });
  if (!reponse.ok) throw new Error(`Erreur ${reponse.status}`);
}
```

`frontend/src/features/agents/AgentsPage.tsx` (structure attendue, pas de code figé) :
- `useState<Agent[]>` pour la liste, `useState<Poste[]>` pour le sélecteur, `useState` pour l'état du formulaire, `useState<string | null>` pour la bannière de mot de passe temporaire et pour un message d'erreur.
- `useEffect` au montage : `listerAgents()` (catch -> message d'erreur si 401/403, ex. jeton absent/expiré) et `fetch('/api/v1/postes')` direct pour peupler le `<select>` de poste.
- Formulaire : champs `matricule`, `nom`, `<select role>` (5 valeurs de `Role`), `<select posteId>` (options `Poste[]`) ; soumission -> `creerAgent(payload)` -> en cas de succès, préfixer la liste locale avec le nouvel agent et afficher `motDePasseTemporaire` dans une bannière avec le texte "À communiquer à l'agent -- ne sera plus affiché".
- Tableau : colonnes matricule/nom/poste/rôle/statut, bouton "Désactiver" par ligne (`disabled` si `!agent.actif`), appelle `desactiverAgent(id)` puis met à jour l'entrée locale (`actif: false`) sans re-fetch complet.

`frontend/src/app/App.tsx` :
```tsx
import AgentsPage from '../features/agents/AgentsPage';

function App() {
  return <AgentsPage />;
}

export default App;
```
(`HomePage` reste dans le repo, simplement non montée par ce point d'entrée -- pas de suppression de fichier hors périmètre.)

## Plan de tests

| Critère d'acceptation | Test | Emplacement |
|---|---|---|
| `POST/PATCH/DELETE /api/v1/agents` réservés aux rôles admin | `creer_avecRoleAgent_shouldRetourner403`, `patch_avecRoleAuditeur_shouldRetourner403`, `desactiver_avecRoleAgent_shouldRetourner403` (login préalable avec un compte `Role.AGENT`/`AUDITEUR`, appel avec le token obtenu) | `AgentAdminIntegrationTest` |
| Génération d'un mot de passe temporaire à la création | `creer_avecDonneesValides_shouldRetourner201EtMotDePasseTemporaire` : vérifie `201`, `motDePasseTemporaire` non vide et de longueur >= 12, puis `POST /api/v1/auth/login` avec ce mot de passe -> `200` | `AgentAdminIntegrationTest` |
| Matricule dupliqué -> conflit explicite | `creer_avecMatriculeDejaUtilise_shouldRetourner409` | `AgentAdminIntegrationTest` |
| `posteId` invalide -> erreur explicite (pas 500 JPA brute) | `creer_avecPosteInconnu_shouldRetourner404`, `modifier_avecPosteInconnu_shouldRetourner404` | `AgentAdminIntegrationTest` |
| Agent/id inconnu sur `PATCH`/`DELETE` | `modifier_avecIdInconnu_shouldRetourner404`, `desactiver_avecIdInconnu_shouldRetourner404` | `AgentAdminIntegrationTest` |
| `PATCH` modifie nom/poste sans toucher au mot de passe/rôle | `modifier_avecNomEtPoste_shouldMettreAJourEtConserverHashEtRole` (relit l'agent en base, compare `hashMotDePasse` avant/après) | `AgentAdminIntegrationTest` |
| Désactivation idempotente | `desactiver_appeleDeuxFois_shouldRetourner204LesDeuxFois` | `AgentAdminIntegrationTest` |
| Un agent désactivé ne peut plus se connecter (401 explicite) | `desactiver_puisLogin_shouldRetourner401` : créer agent via `POST`, login OK, `DELETE` par un admin, nouveau login -> `401` `IDENTIFIANTS_INVALIDES` | `AgentAdminIntegrationTest` (régression bout-en-bout, réutilise le comportement déjà testé unitairement dans `AuthIntegrationTest.login_avecCompteInactif_...`) |
| Désactivation immédiate d'une session existante (test d'intégration explicite du ticket) | `desactiver_puisRefresh_shouldRetourner401Immediatement` : login (access + refresh token), `DELETE` par un admin, `POST /api/v1/auth/refresh` avec le refresh token émis avant désactivation -> `401` immédiat. Test complémentaire `desactiver_accessTokenDejaEmis_shouldResterValideJusquaExpiration` : après désactivation, une requête protégée avec l'access token émis avant désactivation passe toujours le filtre JWT (documente le comportement attendu -- pas de blacklist JWT, cf. Hors périmètre) | `AgentAdminIntegrationTest` |
| `@EnableMethodSecurity` effectivement actif | Le test `creer_avecRoleAgent_shouldRetourner403` échouerait silencieusement (200/201 au lieu de 403) si l'annotation est omise -- sert de garde-fou de non-régression pour ce risque identifié par le design | `AgentAdminIntegrationTest` |
| Entité `Agent.desactiver()`/`modifierInformations()` | `desactiver_shouldMettreActifAFaux`, `desactiver_appeleDeuxFois_shouldResterIdempotent`, `modifierInformations_shouldRemplacerNomEtPoste` | `AgentTest` (unitaire, pas de Spring context) |
| Interface frontend simple (liste + formulaire) | Pas de test automatisé : aucun outil de test frontend n'est présent dans le repo à ce jour (`frontend/package.json` ne référence ni `vitest`, ni `@testing-library/react`, ni `jest` -- seuls `eslint`/`prettier`/`tsc` sont outillés). Vérification manuelle : `npm run build` (type-check) doit passer, puis vérification manuelle en local (jeton déposé dans `localStorage`, liste affichée, création d'un agent, affichage du mot de passe temporaire une seule fois, désactivation reflétée dans le tableau) | Manuel, à consigner dans la description de la PR |

## Écarts identifiés

1. **`GET /api/v1/agents` absent des critères d'acceptation explicites du ticket** mais strictement nécessaire au frontend demandé par ces mêmes critères ("Interface frontend simple (liste + ...)"). Décision tranchée dans cette spec, conformément à la proposition du design : ajouté comme 4e endpoint, même contrôle de rôle (`hasAnyRole('CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')`), pas de pagination/recherche (hors périmètre explicite). À valider par le product owner si une exposition en lecture de tous les comptes agents à un `CHEF_POSTE` (y compris ceux d'autres postes) pose problème avant le ticket #8 de scoping RBAC fin.
2. **Absence de restriction sur le rôle attribuable à la création** : `CreerAgentRequest.role` accepte n'importe laquelle des 5 valeurs de `Role`, y compris `ADMIN_NATIONAL`, sans vérifier que l'appelant a un rôle au moins équivalent. Un `CHEF_POSTE` authentifié peut donc, avec l'implémentation telle que spécifiée, créer un compte `ADMIN_NATIONAL`. Le design a explicitement scopé le RBAC fin (poste/région) au ticket #8, mais ce point précis (élévation de privilège sur le rôle créé, pas seulement le poste) est plus sensible et n'est pas mentionné dans le design. Décision de cette spec : ne pas bloquer ce ticket dessus (le ticket ne demande pas de hiérarchie de rôles), mais signaler explicitement ce risque au reviewer et au product owner comme candidat prioritaire pour le ticket #8, plutôt que de le laisser passer inaperçu.
3. **Statut HTTP pour `posteId` invalide** : le design hésitait entre 400 et 404. Cette spec tranche pour `404 POSTE_INTROUVABLE` (cohérent avec `AgentIntrouvableException` -> 404, réserve le 400 aux erreurs de validation syntaxique du corps de la requête via `@Valid`).
4. **Traçabilité de la désactivation** (`desactive_le`/`desactive_par`, mentionnée par le design section 10.4) : confirmé hors périmètre de ce ticket, aucune migration Flyway ajoutée. Aucun écart avec les critères d'acceptation, qui ne demandent pas d'audit trail -- mentionné ici uniquement pour tracer la décision explicitement.
